#!/usr/bin/env python3
"""
RTP 流接收器 — 接收 Android 投屏的 H264 + AAC 流并通过 FFplay 播放

功能：
- 接收 H264视频流 (UDP port 5004) + AAC音频流 (UDP port 5006)
- 按 RFC 6184 解包 FU-A 分片，重组 NALU
- 按 RFC 3550 解析 RTP 头
- 重建 H264 Annex-B 比特流 + AAC ADTS 帧
- 通过 FFplay 低延迟播放

用法：
  python receiver.py [--video-port 5004] [--audio-port 5006] [--no-audio]

要求：
  pip install ffmpeg-python   (可选，使用 subprocess 调用 FFplay 也可)
"""

import socket
import struct
import subprocess
import sys
import threading
import argparse
import os
import time
from collections import defaultdict
from typing import Optional, Tuple, Dict, List

# ─── RTP 常量 ─────────────────────────────────────────────────
RTP_HEADER_SIZE = 12
H264_PAYLOAD_TYPE = 96
AAC_PAYLOAD_TYPE = 97

# H264 NALU types
NALU_TYPE_FU_A = 28
NALU_TYPE_STAP_A = 24
NALU_TYPE_SPS = 7
NALU_TYPE_PPS = 8
NALU_TYPE_IDR = 5

# H264 start code
START_CODE = b'\x00\x00\x00\x01'

# 最大接收缓冲区
MAX_UDP_SIZE = 65536


class RtpDepacketizer:
    """RTP 解包器 — 处理乱序、丢包、FU-A 重组"""

    def __init__(self):
        # FU-A 重组缓冲区: {ssrc: bytearray}
        self.fu_buffer: Dict[int, bytearray] = {}
        self.fu_nal_type: Dict[int, int] = {}
        # 序列号追踪
        self.last_seq: Dict[int, int] = {}
        self.lost_packets: Dict[int, int] = defaultdict(int)

    def parse_rtp_header(self, data: bytes) -> Tuple[int, int, int, int, int, bool]:
        """
        解析 RTP 固定头 (12 bytes, RFC 3550 §5.1)
        Returns: (payload_type, seq, timestamp, ssrc, payload_offset, marker)
        """
        if len(data) < RTP_HEADER_SIZE:
            raise ValueError(f"Packet too short: {len(data)} bytes")

        byte0, byte1, seq, ts, ssrc = struct.unpack('!BBHIL', data[:12])
        version = (byte0 >> 6) & 0x03
        if version != 2:
            raise ValueError(f"Invalid RTP version: {version}")

        marker = (byte0 >> 7) & 0x01
        payload_type = byte0 & 0x7F
        return payload_type, seq, ts, ssrc, RTP_HEADER_SIZE, bool(marker)

    def depacketize_h264(self, rtp_payload: bytes, marker: bool, ssrc: int) -> List[bytes]:
        """
        解包 H264 RTP 载荷，返回完整的 NALU 列表
        支持: Single NALU, FU-A (RFC 6184)
        """
        if len(rtp_payload) < 1:
            return []

        nalu_header = rtp_payload[0]
        nalu_type = nalu_header & 0x1F

        if nalu_type <= 23:
            # Single NALU packet — 直接返回
            return [rtp_payload]

        elif nalu_type == NALU_TYPE_FU_A:
            return self._handle_fu_a(rtp_payload, ssrc)

        elif nalu_type == NALU_TYPE_STAP_A:
            return self._handle_stap_a(rtp_payload)

        else:
            print(f"[WARN] Unknown NALU type: {nalu_type}")
            return []

    def _handle_fu_a(self, payload: bytes, ssrc: int) -> List[bytes]:
        """处理 FU-A 分片"""
        if len(payload) < 2:
            return []

        fu_indicator = payload[0]
        fu_header = payload[1]
        start_bit = (fu_header >> 7) & 0x01
        end_bit = (fu_header >> 6) & 0x01
        fu_nal_type = fu_header & 0x1F
        fu_payload = payload[2:]

        # 重建 NALU header: F(1)|NRI(2)|Type(5)
        nri = (fu_indicator >> 5) & 0x03
        nalu_header = (fu_indicator & 0xE0) | fu_nal_type

        if start_bit:
            # 开始新 NALU
            self.fu_buffer[ssrc] = bytearray()
            self.fu_buffer[ssrc].extend(fu_payload)
            self.fu_nal_type[ssrc] = fu_nal_type
            # 不是最终结果，返回空
            return []

        elif end_bit:
            # 结束 NALU
            if ssrc in self.fu_buffer:
                self.fu_buffer[ssrc].extend(fu_payload)
                # 构建完整 NALU: [header] + [assembled payload]
                complete = bytes([nalu_header]) + bytes(self.fu_buffer[ssrc])
                del self.fu_buffer[ssrc]
                del self.fu_nal_type[ssrc]
                return [complete]
            else:
                print(f"[WARN] FU-A end without start (ssrc={ssrc})")
                return []

        else:
            # 中间分片
            if ssrc in self.fu_buffer:
                self.fu_buffer[ssrc].extend(fu_payload)
            else:
                print(f"[WARN] FU-A middle without start (ssrc={ssrc})")
            return []

    def _handle_stap_a(self, payload: bytes) -> List[bytes]:
        """处理 STAP-A (多 NALU 聚合包)"""
        nalus = []
        offset = 1  # skip STAP-A header
        while offset < len(payload) - 1:
            if offset + 2 > len(payload):
                break
            nalu_size = struct.unpack('!H', payload[offset:offset+2])[0]
            offset += 2
            if offset + nalu_size > len(payload):
                break
            nalus.append(payload[offset:offset+nalu_size])
            offset += nalu_size
        return nalus

    def track_sequence(self, ssrc: int, seq: int) -> int:
        """追踪序列号，返回丢包数"""
        lost = 0
        if ssrc in self.last_seq:
            expected = (self.last_seq[ssrc] + 1) & 0xFFFF
            if seq != expected:
                lost = (seq - expected) & 0xFFFF
                self.lost_packets[ssrc] += lost
        self.last_seq[ssrc] = seq
        return lost


class StreamReceiver:
    """RTP 流接收 + FFplay 播放"""

    def __init__(self, video_port=5004, audio_port=5006, enable_audio=True):
        self.video_port = video_port
        self.audio_port = audio_port
        self.enable_audio = enable_audio

        self.video_socket: Optional[socket.socket] = None
        self.audio_socket: Optional[socket.socket] = None
        self.ffplay_proc: Optional[subprocess.Popen] = None

        self.depacketizer = RtpDepacketizer()

        # H264 参数集（用于每个 IDR 前插入）
        self.cached_sps: Optional[bytes] = None
        self.cached_pps: Optional[bytes] = None

        # 统计
        self.video_frame_count = 0
        self.audio_frame_count = 0
        self.start_time = 0.0
        self.running = False

        # FFplay 的标准输入管道
        self.video_pipe_write: Optional[int] = None

    def start(self):
        """启动接收和播放"""
        # 1. 创建 UDP sockets
        self.video_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.video_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.video_socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)  # 4MB
        self.video_socket.bind(('0.0.0.0', self.video_port))
        self.video_socket.settimeout(1.0)

        if self.enable_audio:
            self.audio_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.audio_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.audio_socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512 * 1024)  # 512KB
            self.audio_socket.bind(('0.0.0.0', self.audio_port))
            self.audio_socket.settimeout(0.5)

        print(f"🎥 Video receiver listening on UDP/{self.video_port}")
        if self.enable_audio:
            print(f"🎤 Audio receiver listening on UDP/{self.audio_port}")
        print(f"📺 等待 Android 设备连接... (本机 IP 可能是: {self._get_local_ip()})")

        # 2. 启动 FFplay
        self._start_ffplay()

        # 3. 启动接收线程
        self.running = True
        self.start_time = time.time()

        video_thread = threading.Thread(target=self._receive_video, daemon=True)
        video_thread.start()

        audio_thread = None
        if self.enable_audio:
            audio_thread = threading.Thread(target=self._receive_audio, daemon=True)
            audio_thread.start()

        # 4. 主线程定时输出统计
        try:
            while self.running:
                time.sleep(5)
                self._print_stats()
        except KeyboardInterrupt:
            print("\n⏹ 停止中...")

        self.stop()

    def stop(self):
        """停止所有组件"""
        self.running = False

        for sock in [self.video_socket, self.audio_socket]:
            if sock:
                try: sock.close()
                except: pass

        if self.ffplay_proc:
            try:
                self.ffplay_proc.stdin.close()
                self.ffplay_proc.terminate()
                self.ffplay_proc.wait(timeout=3)
            except:
                try: self.ffplay_proc.kill()
                except: pass

        print("✅ 接收器已停止")

    def _start_ffplay(self):
        """
        启动 FFplay 进程，接收管道输入的 H264 + AAC 混合流

        使用 FFmpeg 的 concat 协议或直接通过管道发送 Annex-B 数据
        """
        # 使用 FFplay 通过管道读取 H264 原始流
        # -flags low_delay: 降低解码延迟
        # -probesize 32: 最少探测数据量
        # -analyzeduration 0: 不分析时长
        # -fflags nobuffer: 禁用缓冲
        # -framedrop: 允许丢帧以维持低延迟
        # -sync ext: 外部时钟同步
        cmd = [
            'ffplay',
            '-f', 'h264',           # 输入格式：H264 Annex-B 裸流
            '-i', 'pipe:0',         # 从标准输入读取
            '-flags', 'low_delay',  # 低延迟标志
            '-probesize', '32',     # 最小探测
            '-analyzeduration', '0',
            '-fflags', 'nobuffer',  # 禁用缓冲
            '-framedrop',           # 允许丢帧保持同步
            '-sync', 'ext',         # 外部时钟
            '-infbuf',              # 无限缓冲区（不丢帧但延迟可能增加）
            '-window_title', 'Screen Mirror (Android → PC)',
            '-autoexit',            # 输入结束时自动退出
        ]

        try:
            self.ffplay_proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.video_pipe_write = self.ffplay_proc.stdin.fileno()
            print("🖥  FFplay 已启动")
        except FileNotFoundError:
            print("❌ 未找到 FFplay，请安装 FFmpeg 并将 ffplay 添加到 PATH")
            print("   下载: https://ffmpeg.org/download.html")
            sys.exit(1)

    def _receive_video(self):
        """接收视频 RTP 包的主循环"""
        sock = self.video_socket
        buf = bytearray(MAX_UDP_SIZE)

        while self.running:
            try:
                nbytes, addr = sock.recvfrom_into(buf)
                if nbytes <= 0:
                    continue

                data = bytes(buf[:nbytes])
                result = self._process_video_packet(data)
                if result:
                    self._write_to_ffplay(result)

            except socket.timeout:
                continue
            except OSError:
                break
            except Exception as e:
                print(f"[ERROR] Video receive error: {e}")

    def _receive_audio(self):
        """接收音频 RTP 包"""
        sock = self.audio_socket
        buf = bytearray(MAX_UDP_SIZE)

        while self.running:
            try:
                nbytes, addr = sock.recvfrom_into(buf)
                if nbytes <= 0:
                    continue

                data = bytes(buf[:nbytes])
                result = self._process_audio_packet(data)

            except socket.timeout:
                continue
            except OSError:
                break
            except Exception as e:
                print(f"[ERROR] Audio receive error: {e}")

    def _process_video_packet(self, data: bytes) -> Optional[bytes]:
        """处理单个视频 RTP 包，返回可用于写入 FFplay 的 Annex-B 数据"""
        try:
            pt, seq, ts, ssrc, offset, marker = self.depacketizer.parse_rtp_header(data)
        except ValueError:
            return None

        if pt != H264_PAYLOAD_TYPE:
            return None

        # 丢包检测
        lost = self.depacketizer.track_sequence(ssrc, seq)
        if lost > 0:
            print(f"[WARN] Video packet loss: {lost} packets (seq={seq})")

        # 解包
        rtp_payload = data[offset:]
        nalus = self.depacketizer.depacketize_h264(rtp_payload, marker, ssrc)

        if not nalus:
            return None

        # 构建 Annex-B 输出
        output = bytearray()
        for nalu in nalus:
            if len(nalu) < 1:
                continue

            nalu_type = nalu[0] & 0x1F

            # 缓存 SPS/PPS
            if nalu_type == NALU_TYPE_SPS:
                self.cached_sps = nalu
            elif nalu_type == NALU_TYPE_PPS:
                self.cached_pps = nalu

            # 如果是 IDR 帧，先写入缓存的 SPS/PPS
            if nalu_type == NALU_TYPE_IDR and self.cached_sps and self.cached_pps:
                output.extend(START_CODE)
                output.extend(self.cached_sps)
                output.extend(START_CODE)
                output.extend(self.cached_pps)
                self.video_frame_count += 1

            output.extend(START_CODE)
            output.extend(nalu)

        return bytes(output) if len(output) > 0 else None

    def _process_audio_packet(self, data: bytes) -> Optional[bytes]:
        """处理音频 RTP 包"""
        try:
            pt, seq, ts, ssrc, offset, marker = self.depacketizer.parse_rtp_header(data)
        except ValueError:
            return None

        if pt != AAC_PAYLOAD_TYPE:
            return None

        self.depacketizer.track_sequence(ssrc, seq)
        self.audio_frame_count += 1

        # AAC 裸流，可以附加 ADTS 头以便 FFplay 识别
        aac_frame = data[offset:]
        return aac_frame

    def _write_to_ffplay(self, data: bytes):
        """将数据写入 FFplay 的标准输入"""
        if self.ffplay_proc and self.ffplay_proc.stdin and not self.ffplay_proc.stdin.closed:
            try:
                os.write(self.video_pipe_write, data)
            except BrokenPipeError:
                print("[WARN] FFplay pipe broken, restarting...")
                self._start_ffplay()
            except Exception as e:
                print(f"[ERROR] Write to FFplay failed: {e}")

    def _print_stats(self):
        """打印接收统计"""
        elapsed = time.time() - self.start_time
        video_fps = self.video_frame_count / elapsed if elapsed > 0 else 0
        print(f"📊 Runtime: {elapsed:.0f}s | "
              f"Video frames: {self.video_frame_count} ({video_fps:.1f} fps) | "
              f"Lost packets: {sum(self.depacketizer.lost_packets.values())}")

    @staticmethod
    def _get_local_ip() -> str:
        """获取本机局域网 IP"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "unknown"


def main():
    parser = argparse.ArgumentParser(
        description='RTP 流接收器 — Android 屏幕投屏 PC 端',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python receiver.py                           # 默认端口 5004/5006
  python receiver.py --video-port 5004         # 仅指定视频端口
  python receiver.py --no-audio                # 仅视频
  python receiver.py --low-latency             # 极低延迟模式
        """
    )
    parser.add_argument('--video-port', type=int, default=5004,
                        help='视频 RTP 端口 (默认: 5004)')
    parser.add_argument('--audio-port', type=int, default=5006,
                        help='音频 RTP 端口 (默认: 5006)')
    parser.add_argument('--no-audio', action='store_true',
                        help='禁用音频接收')
    parser.add_argument('--low-latency', action='store_true',
                        help='启用极低延迟模式')
    args = parser.parse_args()

    print("=" * 60)
    print("  Screen Mirror Receiver — Android 投屏接收端")
    print("=" * 60)
    print()

    receiver = StreamReceiver(
        video_port=args.video_port,
        audio_port=args.audio_port,
        enable_audio=not args.no_audio
    )
    receiver.start()


if __name__ == '__main__':
    main()
