@echo off
chcp 65001 >nul
title 屏幕投屏 VLC 接收器

echo.
echo =============================================
echo   屏幕投屏 VLC 接收器 - 启动脚本
echo =============================================
echo.

REM ── 检查 Python ──
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python！请安装 Python 3.8+
    echo        下载: https://www.python.org/downloads/
    pause
    exit /b 1
)

REM ── 检查 VLC ──
set VLC_FOUND=0
if exist "C:\Program Files\VideoLAN\VLC\vlc.exe" set VLC_FOUND=1
if exist "C:\Program Files (x86)\VideoLAN\VLC\vlc.exe" set VLC_FOUND=1
if exist "D:\Program Files\VideoLAN\VLC\vlc.exe" set VLC_FOUND=1

if %VLC_FOUND% == 0 (
    echo [警告] 未找到 VLC！请安装 VLC：
    echo        下载: https://www.videolan.org/vlc/
    echo.
    echo 安装完成后重新运行本脚本。
    pause
    exit /b 1
)

REM ── 获取本机 IP 并提示 ──
echo [信息] 本机网络信息：
ipconfig | findstr /i "IPv4"
echo.

REM ── 防火墙提示 ──
echo [信息] 检查防火墙规则...
netsh advfirewall firewall show rule name="Screen Mirror RTP (UDP 5004-5006)" >nul 2>&1
if %errorlevel% neq 0 (
    echo [警告] 防火墙规则不存在，正在添加...
    netsh advfirewall firewall add rule name="Screen Mirror RTP (UDP 5004-5006)" ^
        dir=in action=allow protocol=UDP localport=5004-5006 >nul 2>&1
    if %errorlevel% == 0 (
        echo         ✓ 防火墙规则已添加 (UDP 5004-5006 入站)
    ) else (
        echo         ⚠ 请以管理员身份运行以自动配置防火墙！
        echo         或者手动开放 UDP 端口 5004、5006
    )
) else (
    echo         ✓ 防火墙规则已存在
)

echo.
echo [启动] 正在运行接收器...
echo.

REM ── 启动 Python 接收器 ──
python "%~dp0receiver_vlc.py" %*

echo.
echo =============================================
echo   接收器已退出。
echo   按任意键关闭窗口...
echo =============================================
pause >nul
