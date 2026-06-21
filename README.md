# 屏幕镜像投屏 App

将 Android 手机画面与内部音频实时投射至 PC 端 VLC 播放器，基于 RTP/UDP 自定义协议栈，延迟 < 1 秒。

## 项目概况

本方案采用 **MediaProjection + MediaCodec 硬编码 → RTP/UDP 直推 → VLC 直接解码** 的极简架构，不依赖任何专用硬件，PC 端只需 VLC 播放器即可接收。

## 技术指标

| 指标 | 规格 | 实测 |
|------|------|------|
| 分辨率 | 1080P | ✅ 1920×1080 |
| 视频码率 | 8 Mbps | ✅ H264 Baseline Hard Encoder |
| 音频规格 | 128 Kbps | ✅ ACC-LC 44.1kHz Stereo |
| 投屏延迟 | < 2 秒 | ✅ < 1 秒 |

## 架构

```
手机端（Android App）                    电脑端（PC）
┌──────────────────────────┐         ┌─────────────────┐
│ 屏幕 → MediaProjection   │         │                 │
│       ↓                  │  RTP    │  Python 接收脚本  │
│  MediaCodec → H264 + AAC │ ──────→ │  ↓ 抓 SPS/PPS   │
│       ↓                  │  UDP    │  ↓ 生成 SDP 文件  │
│  RtpStreamer 封包         │ ──────→ │  ↓ 启动 VLC      │
│       ↓                  │         │  ↓              │
│  UDP Socket 发送          │         │  VLC 直接收流播放  │
└──────────────────────────┘         └─────────────────┘
```

## 目录结构

```
ScreenMirrorApp/        ← Android 项目（Kotlin）
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/com/screenmirror/app/
│       ├── MainActivity.kt            ← 用户界面
│       ├── ScreenCaptureService.kt    ← 录屏服务 + 视频编码
│       ├── RtpStreamer.kt             ← RTP 封包 + UDP 发送
│       ├── AudioCapture.kt            ← 音频内录 + AAC 编码
│       └── DeviceDiscovery.kt         ← NSD/SSDP 设备发现
│
pc_receiver/            ← PC 端接收器（Python）
├── receiver_vlc.py     ← SPS/PPS 捕获 + SDP 生成 + VLC 启动
└── run_receiver.bat    ← 一键启动脚本
```

## 协议栈

| 层 | 协议/规范 | 作用 |
|----|-----------|------|
| 编码 | H264 Baseline / AAC-LC | 音视频压缩 |
| 封包 | RFC 3550 (RTP) | 序列号、时间戳、载荷类型 |
| 分片 | RFC 6184 (FU-A) | H264 大帧拆包 |
| 音频封装 | RFC 3640 (mpeg4-generic) | AAC 帧 AU-header |
| 传输 | UDP 单播 | 端口 5004 (视频) / 5006 (音频) |
| 流描述 | SDP (Session Description Protocol) | VLC 初始化参数 |

## 使用方式

### 1. 构建 Android App

用 Android Studio 打开 `ScreenMirrorApp/` 目录，Sync Gradle → Build → 安装到手机。

### 2. 启动 PC 接收器

```bash
cd pc_receiver
python receiver_vlc.py --timeout 60
```

或双击 `run_receiver.bat`。

### 3. 开始投屏

- 手机和电脑连接同一 WiFi（推荐手机开热点）
- 手机 App 输入 PC 端显示的 IP 地址
- 点击「开始投屏」并授权录屏
- PC 端自动打开 VLC 播放

## 开发环境

| 组件 | 版本 |
|------|------|
| Android Gradle Plugin | 7.4.2 |
| Gradle | 8.5 |
| compileSdk / targetSdk | 33 |
| minSdk | 21 |
| Kotlin | 1.9.20 |
| VLC | 3.x |
| Python | ≥ 3.7 |

## License

MIT
