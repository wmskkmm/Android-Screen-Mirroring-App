# PC Receiver — Android 屏幕投屏接收端 (Windows + VLC)

## 功能

接收 Android 手机通过 RTP/UDP 发送的 H264 视频 + AAC 音频流，通过 VLC 播放。

## 环境要求

- **Windows 10/11**
- **Python 3.8+** → [下载](https://www.python.org/downloads/)
- **VLC Media Player** → [下载](https://www.videolan.org/vlc/)

## 一键启动

```
双击 run_receiver.bat
```

脚本会自动：
1. 检查 Python + VLC 是否安装
2. 显示本机 IP 地址
3. 自动添加 Windows 防火墙规则 (UDP 5004-5006 入站)
4. 等待手机连接
5. 生成 SDP 文件 → 启动 VLC 播放

## 使用步骤

```
    PC（本机）                      Android 手机
    ─────────────────────────────────────────────
    1. 双击 run_receiver.bat
       屏幕显示本机 IP: 192.168.x.x
                                     2. 打开 ScreenMirror App
                                     3. 输入 PC 的 IP 地址
                                     4. 点击「开始投屏」
                                     5. 授权屏幕录制
    6. 自动截获视频参数
       自动启动 VLC
       ┌─────────────┐
       │  VLC 窗口   │ ← 实时显示手机画面
       │  手机投屏   │
       └─────────────┘
```

## 命令行参数

```bash
# 自动模式（推荐）
python receiver_vlc.py

# 指定 VLC 路径
python receiver_vlc.py --vlc "D:\VLC\vlc.exe"

# 延长等待时间（手机启动慢时）
python receiver_vlc.py --timeout 15

# 仅生成 SDP 文件（手动打开 VLC）
python receiver_vlc.py --manual
```

## 技术规格

| 指标 | 数值 |
|------|------|
| 视频编码 | H.264 Baseline Profile |
| 分辨率 | 1920×1080 @ 30fps |
| 视频码率 | 8 Mbps (CBR) |
| 音频编码 | AAC-LC |
| 音频规格 | 44.1kHz Stereo 128kbps |
| 传输协议 | RTP/UDP (RFC 3550 + RFC 6184 FU-A) |
| 延迟 | < 2 秒（局域网） |

## 手动防火墙配置

如果脚本无法自动配置防火墙（需要管理员权限），请手动执行：

```powershell
# 以管理员身份运行 PowerShell
New-NetFirewallRule -DisplayName "Screen Mirror RTP" `
    -Direction Inbound -Protocol UDP -LocalPort 5004-5006 -Action Allow
```

## 故障排查

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 等待超时，未收到数据 | 手机 IP 填错 | 确认手机输入的是 PC 的局域网 IP |
| 等待超时，未收到数据 | 不在同一 WiFi | 手机和电脑连同一个 WiFi |
| 等待超时，未收到数据 | 防火墙拦截 | 关闭 Windows 防火墙测试 |
| VLC 花屏/绿屏 | SPS/PPS 丢失 | 重新运行脚本，手机重新开始投屏 |
| VLC 画面卡顿 | WiFi 信号弱 | 靠近路由器，减少干扰 |
| 延迟 > 2 秒 | VLC 缓存太大 | VLC → 工具 → 偏好设置 → 网络缓存 → 200ms |
| VLC 无声音 | AAC 格式不兼容 | 设备 AAC 编码器差异，先用 `--no-audio` |

## 文件说明

| 文件 | 用途 |
|------|------|
| `receiver_vlc.py` | VLC SDP 接收器主程序 |
| `receiver.py` | FFplay 管道接收器（备用方案） |
| `run_receiver.bat` | Windows 一键启动脚本 |
| `screen_mirror.sdp` | 自动生成的 SDP 描述文件 |
