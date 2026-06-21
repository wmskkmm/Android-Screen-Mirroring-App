package com.screenmirror.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务 — 负责屏幕采集 + H264 硬编码 + RTP 发送
 *
 * 架构：
 *   MediaProjection → VirtualDisplay(Surface) → MediaCodec(H264) → RtpStreamer → UDP
 *
 * 使用 MediaCodec 异步回调模式，避免轮询延迟
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_mirror_channel"

        // 视频参数
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_BITRATE = 8_000_000   // 8 Mbps
        const val VIDEO_FRAMERATE = 30
        const val VIDEO_I_FRAME_INTERVAL = 2   // I 帧间隔（秒）

        // Actions
        const val ACTION_START = "com.screenmirror.app.START"
        const val ACTION_STOP = "com.screenmirror.app.STOP"
        const val EXTRA_TARGET_HOST = "target_host"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        // 广播: 状态更新
        const val BROADCAST_STATUS = "com.screenmirror.app.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
    }

    enum class Status { IDLE, CONNECTING, STREAMING, ERROR }

    // ─── Binder ────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    // ─── 核心组件 ──────────────────────────────────────────────
    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var rtpStreamer: RtpStreamer? = null
    private var audioCapture: AudioCapture? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private val isStreaming = AtomicBoolean(false)
    private var targetHost: String = ""
    private var streamWidth = VIDEO_WIDTH
    private var streamHeight = VIDEO_HEIGHT

    // SPS/PPS 缓存（需要在每个 I 帧前发送给新客户端）
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null
    private var spsPpsSent = AtomicBoolean(false)

    // 帧时间戳
    private var lastFrameTimeNs = 0L
    private var frameCount = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_TARGET_HOST) ?: return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                targetHost = host
                startForeground(NOTIFICATION_ID, buildNotification(Status.CONNECTING))
                startStreaming(resultCode, data)
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ─── 开始投屏 ──────────────────────────────────────────────

    private fun startStreaming(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            broadcastStatus(Status.ERROR, "需要屏幕录制权限")
            return
        }

        try {
            // 1. 获取 MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    stopStreaming()
                }
            }, null)

            // 2. 创建编码器工作线程
            encoderThread = HandlerThread("VideoEncoder").apply { start() }
            encoderHandler = Handler(encoderThread!!.looper)

            // 3. 配置并启动视频编码器
            videoEncoder = createVideoEncoder()
            inputSurface = videoEncoder!!.createInputSurface()

            // 4. 启动 RTP 发送器
            rtpStreamer = RtpStreamer(targetHost).also {
                if (!it.start()) {
                    broadcastStatus(Status.ERROR, "无法连接到目标设备")
                    return
                }
            }

            // 5. 创建 VirtualDisplay（将屏幕内容导向编码器 Surface）
            val metrics = DisplayMetrics()
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            defaultDisplay?.getRealMetrics(metrics)

            // 根据实际屏幕宽高计算等比例缩放后的分辨率
            val actualWidth = metrics.widthPixels
            val actualHeight = metrics.heightPixels
            if (actualWidth < actualHeight) {
                // 竖屏 → 保持宽高比，宽度缩放至 1080p
                streamWidth = VIDEO_WIDTH
                streamHeight = (VIDEO_WIDTH.toFloat() / actualWidth * actualHeight).toInt()
                // 对齐到 16
                streamHeight = (streamHeight / 16) * 16
            } else {
                streamWidth = VIDEO_WIDTH
                streamHeight = VIDEO_HEIGHT
            }

            // 6. 设置异步编码回调（必须在 start() 之前！）
            videoEncoder!!.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // 使用 Surface 输入时不需手动输入 buffer，忽略
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    processEncodedOutput(index, info)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Encoder error", e)
                    broadcastStatus(Status.ERROR, "编码器错误: ${e.message}")
                    stopStreaming()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Encoder output format changed: $format")
                }
            }, encoderHandler)

            // 7. 启动视频编码器
            videoEncoder!!.start()
            spsPpsSent.set(false)

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenMirror",
                streamWidth, streamHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            // 8. 启动音频采集（失败不影响视频投屏）
            try {
                audioCapture = AudioCapture(rtpStreamer!!, mediaProjection)
                if (audioCapture?.start() != true) {
                    Log.w(TAG, "Audio capture init failed, continuing video-only")
                    audioCapture = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture error: ${e.message}, continuing video-only")
                audioCapture = null
            }

            isStreaming.set(true)
            broadcastStatus(Status.STREAMING)
            Log.i(TAG, "Streaming started: ${streamWidth}x${streamHeight} → $targetHost")

        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to start streaming: $detail", e)
            broadcastStatus(Status.ERROR, "启动失败: $detail")
            stopStreaming()
        }
    }

    // ─── 处理编码输出 ──────────────────────────────────────────

    private fun processEncodedOutput(index: Int, info: MediaCodec.BufferInfo) {
        val encoder = videoEncoder ?: return
        if (info.size <= 0) return

        try {
            val outputBuffer = encoder.getOutputBuffer(index) ?: return
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data, 0, info.size)

            // 检查是否是配置数据（SPS/PPS）
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (isConfig) {
                parseAndCacheSpsPps(data)
            } else {
                // 在每个关键帧前确保 SPS/PPS 已发送
                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isKeyFrame && !spsPpsSent.get()) {
                    sendSpsPps()
                }
                // 解析并发送 NALU
                parseAndSendNalus(data, isKeyFrame)
            }

            encoder.releaseOutputBuffer(index, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing encoded output", e)
            try { encoder.releaseOutputBuffer(index, false) } catch (_: Exception) {}
        }
    }

    /**
     * 从 CSD-0 / CSD-1 缓冲区解析 SPS 和 PPS
     * MediaCodec 的 CSD 数据格式: [start_code] sps [start_code] pps
     */
    private fun parseAndCacheSpsPps(csdData: ByteArray) {
        val startCode = byteArrayOf(0, 0, 0, 1)
        val spsStart = csdData.findPattern(startCode, 0)
        if (spsStart < 0) {
            Log.w(TAG, "Cannot find SPS start code in CSD")
            return
        }

        val spsDataStart = spsStart + 4
        val ppsStart = csdData.findPattern(startCode, spsDataStart)

        if (ppsStart >= 0) {
            cachedSps = csdData.copyOfRange(spsDataStart, ppsStart)
            cachedPps = csdData.copyOfRange(ppsStart + 4, csdData.size)
        } else {
            // Only SPS found, PPS might be in the next CSD buffer
            cachedSps = csdData.copyOfRange(spsDataStart, csdData.size)
        }
        Log.d(TAG, "Cached SPS=${cachedSps?.size}B, PPS=${cachedPps?.size}B")
    }

    private fun sendSpsPps() {
        val sps = cachedSps ?: return
        val pps = cachedPps ?: return
        rtpStreamer?.sendSpsPps(sps, pps)
        spsPpsSent.set(true)
    }

    /**
     * 从编码器输出中提取各个 NALU 并发送
     * H264 Annex-B 格式: 00 00 00 01 [NAL] 00 00 00 01 [NAL] ...
     */
    private fun parseAndSendNalus(data: ByteArray, isKeyFrame: Boolean) {
        val startCode = byteArrayOf(0, 0, 0, 1)
        val startCode3 = byteArrayOf(0, 0, 1)  // 3 字节 start code 也可能出现

        var offset = 0

        // 如果数据以 3 字节 start code 开头，跳过
        while (offset < data.size) {
            var remaining = data.size - offset

            // 检查是否有 4 字节 start code
            var scLen = 0
            if (remaining >= 4 && data[offset] == 0.toByte() && data[offset+1] == 0.toByte()
                && data[offset+2] == 0.toByte() && data[offset+3] == 1.toByte()) {
                scLen = 4
            } else if (remaining >= 3 && data[offset] == 0.toByte() && data[offset+1] == 0.toByte()
                && data[offset+2] == 1.toByte()) {
                scLen = 3
            }

            if (scLen == 0) {
                // 没有 start code，可能是编码器配置问题，尝试作为单 NALU 发送
                rtpStreamer?.sendH264Nalu(data, offset, remaining)
                break
            }

            val naluStart = offset + scLen
            val nextSc = data.findPattern(startCode, naluStart)
            val nextSc3 = data.findPattern(startCode3, naluStart)

            val naluEnd = when {
                nextSc >= 0 && nextSc3 >= 0 -> minOf(nextSc, nextSc3)
                nextSc >= 0 -> nextSc
                nextSc3 >= 0 -> nextSc3
                else -> data.size
            }

            if (naluEnd > naluStart) {
                val naluType = (data[naluStart].toInt() and 0x1F)

                // 过滤掉 filler data (12) 和 unspecified (0)
                if (naluType != 12 && naluType != 0) {
                    rtpStreamer?.sendH264Nalu(data, naluStart, naluEnd - naluStart)
                }
            }

            offset = naluEnd
        }

        // 更新时间戳（每个 frame 一次）
        rtpStreamer?.advanceVideoTimestamp()
    }

    // ─── 停止投屏 ──────────────────────────────────────────────

    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return

        Log.i(TAG, "Stopping streaming...")

        // 停止音频
        audioCapture?.stop()
        audioCapture = null

        // 停止编码器
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
        videoEncoder = null
        inputSurface = null

        // 释放 VirtualDisplay
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null

        // 释放 MediaProjection
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        // 停止 RTP
        rtpStreamer?.stop()
        rtpStreamer = null

        // 停止编码线程
        encoderHandler?.looper?.quitSafely()
        encoderHandler = null
        encoderThread = null

        cachedSps = null
        cachedPps = null

        broadcastStatus(Status.IDLE)
    }

    // ─── 视频编码器工厂 ────────────────────────────────────────

    private fun createVideoEncoder(): MediaCodec {
        // 检查是否支持 Surface 输入 + H264 High Profile
        val codecName = findBestH264Encoder()
        val codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            streamWidth, streamHeight
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAMERATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // 低延迟配置
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // KEY_COMPLEXITY: 0=fastest (适合实时编码), 1=balanced, 2=best quality
            setInteger(MediaFormat.KEY_COMPLEXITY, 0)

            // 编码优先级：0=实时优先
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time
            }

            // Profile: Baseline (低延迟，B 帧会增加延迟)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)  // 1080p@30fps
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * 查找最优的 H264 硬件编码器（优先选 Surface 输入支持、Baseline profile）
     */
    private fun findBestH264Encoder(): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue

            val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val supportsSurface = caps.colorFormats?.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) == true

            if (supportsSurface) {
                val isHardware = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    codecInfo.isHardwareAccelerated
                if (isHardware) {
                    Log.i(TAG, "Selected encoder: ${codecInfo.name} (HW accelerated)")
                    return codecInfo.name
                }
            }
        }
        Log.w(TAG, "No ideal HW encoder found, using default")
        return null
    }

    // ─── 通知 + 状态广播 ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕投屏服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: Status): Notification {
        val statusText = when (status) {
            Status.IDLE -> "已停止"
            Status.CONNECTING -> "连接中…"
            Status.STREAMING -> "正在投屏 ${streamWidth}x${streamHeight}"
            Status.ERROR -> "错误"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕投屏")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(status == Status.STREAMING || status == Status.CONNECTING)
            .build()
    }

    private fun broadcastStatus(status: Status, error: String = "") {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status.name)
            if (error.isNotEmpty()) putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)

        // 更新通知
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))

        if (status == Status.ERROR || status == Status.IDLE) {
            stopForeground(status == Status.IDLE)
        }
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}

// ─── ByteArray 工具扩展 ────────────────────────────────────────

private fun ByteArray.findPattern(pattern: ByteArray, startIndex: Int = 0): Int {
    outer@ for (i in startIndex..(this.size - pattern.size)) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}
