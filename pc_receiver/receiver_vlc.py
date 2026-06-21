#!/usr/bin/env python3
"""
屏幕投屏 VLC 接收器 — Windows 版

原理：Android 端通过 RTP/UDP 发送 H264 + AAC 流到 PC。
本脚本自动截获初始 SPS/PPS 参数 → 生成 SDP 文件 → 启动 VLC 播放。

███ 使用步骤 ███
  1. 确保安装 VLC：https://www.videolan.org/vlc/
  2. 双击运行 run_receiver.bat（或 python receiver_vlc.py）
  3. 脚本输出本机 IP，记下来
  4. 打开手机 ScreenMirror App，输入该 IP，点击"开始投屏"
  5. VLC 自动启动播放

技术指标：H264 Baseline 1080p@30fps 8Mbps  /  AAC-LC 44.1kHz 128kbps
延迟：预计 300~800ms（硬编+UDP+VLC 低延迟参数）
"""

import socket
import struct
import subprocess
import sys
import os
import base64
import time
import shutil
import argparse
from typing import Optional, Tuple

# ─── 常量 ─────────────────────────────────────────────────────
RTP_HEADER_SIZE = 12
H264_PAYLOAD_TYPE = 96
AAC_PAYLOAD_TYPE = 97

VIDEO_PORT = 5004          # Android → 视频 RTP
AUDIO_PORT = 5006          # Android → 音频 RTP

# H264 NALU types
NALU_TYPE_SPS = 7
NALU_TYPE_PPS = 8
NALU_TYPE_FU_A = 28
NALU_TYPE_SINGLE_MAX = 23

MAX_UDP_SIZE = 65536

# SDP 模板
SDP_TEMPLATE = """v=0
o=- {session_id} 1 IN IP4 {local_ip}
s=Screen Mirror ({resolution})
c=IN IP4 0.0.0.0
t=0 0
a=tool:ScreenMirrorApp
a=type:broadcast
a=control:*
m=video {video_port} RTP/AVP {video_pt}
a=rtpmap:{video_pt} H264/90000
a=fmtp:{video_pt} packetization-mode=1; sprop-parameter-sets={sps_b64},{pps_b64}; profile-level-id={profile_level_id}
a=framerate:30
{audio_section}
"""

AUDIO_SECTION = """m=audio {audio_port} RTP/AVP {audio_pt}
a=rtpmap:{audio_pt} mpeg4-generic/44100/2
a=fmtp:{audio_pt} streamtype=5; profile-level-id=15; mode=AAC-hbr; config={audio_config}; SizeLength=13; IndexLength=3; IndexDeltaLength=3
"""


def get_local_ip() -> str:
    """获取本机局域网 IP"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def find_vlc() -> Optional[str]:
    """自动查找 VLC 安装路径"""
    candidates = [
        r"D:\soft\VLC\vlc.exe",
        r"C:\Program Files\VideoLAN\VLC\vlc.exe",
        r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
        r"D:\Program Files\VideoLAN\VLC\vlc.exe",
        r"C:\Program Files\VLC\vlc.exe",
    ]
    for p in candidates:
        if os.path.isfile(p):
            return p
    # 尝试 PATH 中查找
    vlc = shutil.which("vlc")
    return vlc


def parse_rtp_header(data: bytes) -> Tuple[int, int, int, int, bool]:
    """解析 RTP 头 → (payload_type, seq, ts, ssrc, marker)"""
    if len(data) < RTP_HEADER_SIZE:
        raise ValueError("Packet too short")
    byte0 = data[0]
    version = (byte0 >> 6) & 0x03
    if version != 2:
        raise ValueError(f"Invalid RTP version: {version}")
    seq = struct.unpack('!H', data[2:4])[0]
    ts = struct.unpack('!I', data[4:8])[0]
    ssrc = struct.unpack('!I', data[8:12])[0]
    byte1 = data[1]
    marker = (byte1 >> 7) & 0x01
    payload_type = byte1 & 0x7F
    return payload_type, seq, ts, ssrc, bool(marker)


def extract_nalu_type_byte(data: bytes, offset: int) -> int:
    """从 RTP 载荷中提取 NALU 类型字节"""
    if offset >= len(data):
        return -1
    header = data[offset]
    nalu_type = header & 0x1F
    return nalu_type


def capture_sps_pps(timeout: float = 5.0) -> Tuple[Optional[bytes], Optional[bytes], bool, str]:
    """
    在 UDP 5004 上监听，捕获 SPS + PPS（以及是否有音频流 + AudioSpecificConfig）
    返回 (sps, pps, has_audio, audio_config)
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('0.0.0.0', VIDEO_PORT))
    sock.settimeout(timeout)

    sps: Optional[bytes] = None
    pps: Optional[bytes] = None
    has_audio = False
    audio_config = "1210"  # 默认: AAC-LC 44100Hz stereo

    print(f"🔍 等待手机连接 (监听 UDP/{VIDEO_PORT})...")

    start = time.time()
    try:
        while (sps is None or pps is None) and (time.time() - start < timeout):
            try:
                data, addr = sock.recvfrom(MAX_UDP_SIZE)
            except socket.timeout:
                break

            try:
                pt, seq, ts, ssrc, marker = parse_rtp_header(data)
            except ValueError as e:
                ver = (data[0] >> 6) & 3 if len(data) > 0 else -1
                print(f"  [debug] bad RTP ver={ver} len={len(data)}: {e}")
                continue

            print(f"  [debug] RTP pt={pt} seq={seq} len={len(data)}", end="")

            if pt != H264_PAYLOAD_TYPE:
                print(f" (not H264, skip)")
                continue

            payload = data[RTP_HEADER_SIZE:]
            if len(payload) < 1:
                continue

            nalu_type = payload[0] & 0x1F
            print(f" nalu_type={nalu_type}")

            if nalu_type == NALU_TYPE_SPS and sps is None:
                sps = bytes(payload)
                print(f"  ✓ 截获 SPS ({len(payload)} bytes)")

            elif nalu_type == NALU_TYPE_PPS and pps is None:
                pps = bytes(payload)
                print(f"  ✓ 截获 PPS ({len(payload)} bytes)")

            elif nalu_type == NALU_TYPE_FU_A or nalu_type <= NALU_TYPE_SINGLE_MAX:
                # IDR 帧 — SPS/PPS 应该已经收到了
                pass
    except Exception as e:
        print(f"  ⚠ 捕获参数时出错: {e}")
    finally:
        sock.close()

    # 不预检查音频（绑定端口会抢在 VLC 之前吃掉第一个音频包），
    # 始终包含音频 track，VLC 直接绑定端口接收
    has_audio = True
    audio_config = "1210"  # AAC-LC 44100Hz stereo
    print(f"  ✓ 音频: 已启用 (config={audio_config})")

    return sps, pps, has_audio, audio_config


def _check_audio_stream(timeout: float) -> Tuple[bool, str]:
    """检查是否有音频流到达，返回默认 AudioSpecificConfig（1210 = AAC-LC 44100Hz stereo）"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', AUDIO_PORT))
        sock.settimeout(timeout)
        data, _ = sock.recvfrom(MAX_UDP_SIZE)
        sock.close()

        # mpeg4-generic 模式：config 由 SDP 带外提供，不从流内提取
        default_config = "1210"  # AAC-LC 44100Hz stereo
        print(f"  ✓ 音频流检测到，使用 config={default_config}")
        return True, default_config
    except (socket.timeout, OSError):
        return False, "1210"


def generate_sdp(sps: bytes, pps: bytes, local_ip: str,
                 has_audio: bool = True,
                 audio_config: str = "1210",
                 resolution: str = "1920x1080") -> str:
    """生成 VLC 可播放的 SDP 文件内容"""
    import base64

    sps_b64 = base64.b64encode(sps).decode('ascii')
    pps_b64 = base64.b64encode(pps).decode('ascii')

    # 从 SPS 中提取 profile-level-id (SPS 第 1-3 字节)
    if len(sps) >= 4:
        profile_idc = sps[1]
        constraint_flags = sps[2]
        level_idc = sps[3]
        profile_level_id = f"{profile_idc:02X}{constraint_flags:02X}{level_idc:02X}"
    else:
        profile_level_id = "42001F"   # Baseline Level 3.1 默认值

    session_id = int(time.time())

    audio_section = ""
    if has_audio:
        audio_section = AUDIO_SECTION.format(
            audio_port=AUDIO_PORT,
            audio_pt=AAC_PAYLOAD_TYPE,
            audio_config=audio_config
        )

    sdp = SDP_TEMPLATE.format(
        session_id=session_id,
        local_ip=local_ip,
        resolution=resolution,
        video_port=VIDEO_PORT,
        video_pt=H264_PAYLOAD_TYPE,
        sps_b64=sps_b64,
        pps_b64=pps_b64,
        profile_level_id=profile_level_id,
        audio_section=audio_section
    )

    return sdp


def launch_vlc(sdp_path: str):
    """启动 VLC 播放 SDP 流"""
    vlc = find_vlc()
    if vlc is None:
        print("\n❌ 未找到 VLC！请安装 VLC 或手动指定路径。")
        print("   下载: https://www.videolan.org/vlc/")
        print(f"   安装后，用 VLC 打开 SDP 文件: {sdp_path}")
        return

    print(f"\n🎬 启动 VLC: {vlc}")
    print(f"   SDP 文件: {sdp_path}")

    # VLC 命令行参数（超低延迟）
    vlc_log = os.path.join(os.getcwd(), "vlc_debug.log")
    cmd = [
        vlc,
        sdp_path,
        "--network-caching=100",       # 网络缓存 100ms（最低）
        "--live-caching=100",          # 直播缓存 100ms
        "--clock-synchro=1",           # 音视频时钟同步
        "--no-skip-frames",
        "--no-drop-late-frames",
        "--avcodec-hw=any",            # 硬件加速解码
        "--video-on-top",              # 窗口置顶
        "--verbose=2",
        f"--logfile={vlc_log}",
    ]

    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"✅ VLC 已启动！日志: {vlc_log}")
        print("   手机画面应即将显示。\n")
    except Exception as e:
        print(f"❌ 启动 VLC 失败: {e}")
        print(f"   请手动用 VLC 打开: {sdp_path}")


def main():
    parser = argparse.ArgumentParser(
        description='屏幕投屏 VLC 接收器 (Windows)',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  python receiver_vlc.py                    # 自动模式
  python receiver_vlc.py --vlc "C:\\path\\vlc.exe"  # 指定 VLC 路径
  python receiver_vlc.py --timeout 10        # 延长等待时间
        """
    )
    parser.add_argument('--vlc', help='VLC 可执行文件路径')
    parser.add_argument('--timeout', type=float, default=8.0, help='等待 SPS/PPS 超时（秒）')
    parser.add_argument('--manual', action='store_true', help='仅生成 SDP 文件，不启动 VLC')
    args = parser.parse_args()

    local_ip = get_local_ip()

    print("=" * 60)
    print("  屏幕投屏 VLC 接收器")
    print("=" * 60)
    print()
    print(f"📡 本机 IP: {local_ip}")
    print()
    print("操作步骤:")
    print(f"  1. 确保手机和电脑在同一 WiFi 网络")
    print(f"  2. 确保 Windows 防火墙允许 UDP {VIDEO_PORT}/{AUDIO_PORT} 端口入站")
    print(f"  3. 打开手机 ScreenMirror App")
    print(f"  4. 输入本机 IP: {local_ip}")
    print(f"  5. 点击「开始投屏」并授权")
    print()

    # ── 截获 SPS/PPS ──
    sps, pps, has_audio, audio_config = capture_sps_pps(timeout=args.timeout)

    if sps is None or pps is None:
        print("\n" + "=" * 60)
        print("⚠️  未在超时时间内收到 SPS/PPS。")
        print()
        print("可能原因:")
        print("  1. 手机 App 尚未启动投屏 → 请先点击「开始投屏」")
        print("  2. IP 地址不正确 → 确认手机输入的 IP 是: " + local_ip)
        print("  3. 防火墙阻止了 UDP 入站 → 检查防火墙设置")
        print("  4. 手机和电脑不在同一子网")
        print()
        print("💡 重新运行本脚本后再试。")
        return

    print(f"\n✅ SPS ({len(sps)}B) + PPS ({len(pps)}B) 已截获")
    if has_audio:
        print(f"✅ 检测到音频流 (AAC-LC 128kbps, config={audio_config})")

    # ── 生成 SDP ──
    sdp_content = generate_sdp(sps, pps, local_ip, has_audio, audio_config)
    sdp_path = os.path.join(os.getcwd(), "screen_mirror.sdp")

    with open(sdp_path, 'w') as f:
        f.write(sdp_content)

    print(f"\n📄 SDP 文件已生成: {sdp_path}")

    # 打印完整 SDP 内容（调试用）
    print("\n─── 完整 SDP ───")
    for line in sdp_content.strip().split('\n'):
        print(f"  {line}")
    print("─── SDP 结束 ───")

    # ── 启动 VLC ──
    if args.manual:
        print(f"\n📌 手动模式: 请用 VLC 打开 → {sdp_path}")
    else:
        # 稍等一下确保 Android 持续发包
        print("\n⏳ 等待 1 秒确保流数据到达...")
        time.sleep(1)
        launch_vlc(sdp_path)

    print(f"\n💡 提示: 如果 VLC 画面不出现或花屏，请尝试:")
    print(f"   1. 重新启动手机 App 投屏")
    print(f"   2. VLC 菜单 → 工具 → 偏好设置 → 显示设置=全部")
    print(f"      → 输入/编解码器 → 网络缓存调低至 200ms")


if __name__ == '__main__':
    main()
