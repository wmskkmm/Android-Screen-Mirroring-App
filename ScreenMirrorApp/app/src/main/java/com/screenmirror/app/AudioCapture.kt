package com.screenmirror.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频采集 + AAC-LC 硬编码
 *
 * 流程: AudioRecord(PCM) → MediaCodec(AAC-LC) → RtpStreamer → UDP
 *
 * Android 10+ 优先用 MediaProjection 捕获内部音频（不含外界噪音）。
 * 旧版本或 MediaProjection 不可用时回退到麦克风。
 *
 * 参数: 44.1kHz / Stereo / 128kbps
 */
class AudioCapture(
    private val rtpStreamer: RtpStreamer,
    private val mediaProjection: MediaProjection? = null
) {
    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2              // 立体声
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITRATE = 128000               // 128 kbps
        private const val FRAMES_PER_BUFFER = 1024       // 每次读取的 PCM 采样数（≈23ms@44.1kHz，对齐 AAC 帧）
    }

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var recordingThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    // AAC 编码器输出缓冲区信息
    private var encoderBufferInfo = MediaCodec.BufferInfo()

    // 从编码器 CSD-0 中提取的 AudioSpecificConfig（hex 字符串）
    @Volatile
    var audioSpecificConfig: String = "1190"
        private set

    /**
     * 启动音频采集和编码
     */
    fun start(): Boolean {
        if (isCapturing.get()) return true

        return try {
            // 1. 计算 AudioRecord 缓冲区大小
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufferSize, FRAMES_PER_BUFFER * CHANNEL_COUNT * 2) // 16bit = 2 bytes

            // 2. 创建 AudioRecord（优先内部音频采集，回退到麦克风）
            audioRecord = createAudioRecord(bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // 3. 创建 AAC 编码器
            audioEncoder = createAacEncoder()
            audioEncoder?.start()

            // 4. 开始录音
            audioRecord?.startRecording()
            isCapturing.set(true)

            // 5. 启动录音线程（略高于普通优先级，但不抢占视频编码器）
            recordingThread = Thread(::recordingLoop, "AudioRecordingThread").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            Log.i(TAG, "Audio capture started: $SAMPLE_RATE Hz, $CHANNEL_COUNT ch, $BITRATE bps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stop()
            false
        }
    }

    // ─── AudioRecord 工厂 ───────────────────────────────────────

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        // Android 10+: 用 MediaProjection 捕获内部音频
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                return createInternalAudioRecord(bufferSize)
            } catch (e: Exception) {
                Log.w(TAG, "Internal audio capture failed: ${e.message}, fallback to MIC")
            }
        }
        // 旧版本 / 失败回退: 麦克风
        return createMicAudioRecord(bufferSize)
    }

    @Suppress("NewApi")
    private fun createInternalAudioRecord(bufferSize: Int): AudioRecord {
        // 捕获媒体和游戏音频（不含麦克风输入）
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)      // 视频/音乐
            .addMatchingUsage(AudioAttributes.USAGE_GAME)       // 游戏
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)    // 未分类
            .build()

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build()
            .also {
                Log.i(TAG, "Internal audio capture (AudioPlaybackCapture) initialized")
            }
    }

    private fun createMicAudioRecord(bufferSize: Int): AudioRecord {
        Log.w(TAG, "Using MIC (microphone) for audio capture")
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    fun stop() {
        isCapturing.set(false)

        recordingThread?.interrupt()
        recordingThread?.join(500)
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio encoder", e)
        }
        audioEncoder = null

        Log.i(TAG, "Audio capture stopped")
    }

    // ─── 录音循环 ──────────────────────────────────────────────

    private fun recordingLoop() {
        val record = audioRecord ?: return
        val encoder = audioEncoder ?: return

        // 每个采样帧的字节数: CHANNEL_COUNT × 2 bytes (16-bit)
        val bytesPerFrame = CHANNEL_COUNT * 2
        val pcmBuffer = ByteArray(FRAMES_PER_BUFFER * bytesPerFrame)

        Log.i(TAG, "Recording loop started, buffer=${pcmBuffer.size}B, recordState=${record.state}, recordSource=${record.audioSource}")

        var readCount = 0
        var encodeCount = 0

        // 先排空编码器初始输出（CSD）
        drainEncoderOutput(encoder)

        while (isCapturing.get() && !Thread.interrupted()) {
            try {
                // 读取 PCM 数据
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)

                if (bytesRead <= 0) {
                    readCount++
                    if (readCount <= 3 || readCount % 100 == 0) {
                        Log.w(TAG, "AudioRecord read=$bytesRead (count=$readCount, state=${record.state})")
                    }
                    Thread.sleep(5)
                    continue
                }
                readCount++

                if (readCount == 1) {
                    Log.i(TAG, "First PCM read OK: $bytesRead bytes")
                }

                // 送入 AAC 编码器
                val encoded = encodePcmToAac(encoder, pcmBuffer, bytesRead)
                if (encoded) encodeCount++

                if (readCount == 1) {
                    Log.i(TAG, "First encode result: $encoded")
                }

                if (readCount % 100 == 0) {
                    Log.d(TAG, "Read $readCount times, encoded $encodeCount frames")
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                break
            }
        }

        Log.i(TAG, "Recording loop ended, read=$readCount encoded=$encodeCount")
    }

    /**
     * 将 PCM 数据编码为 AAC 并发送。
     * 失败时重试最多 3 次，避免丢弃 PCM 导致编码器饥饿。
     */
    private fun encodePcmToAac(encoder: MediaCodec, pcmData: ByteArray, length: Int): Boolean {
        // 先排空输出，释放编码器内部缓冲
        drainEncoderOutput(encoder)

        for (attempt in 0 until 3) {
            try {
                // 超时递增: 10ms → 30ms → 50ms
                val timeoutUs = (10_000L * (attempt + 1))
                val inputBufferIndex = encoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex < 0) continue

                val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: continue
                inputBuffer.clear()
                val actualLen = minOf(length, inputBuffer.capacity())
                inputBuffer.put(pcmData, 0, actualLen)

                val presentationTimeUs = System.nanoTime() / 1000
                encoder.queueInputBuffer(inputBufferIndex, 0, actualLen, presentationTimeUs, 0)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error encoding PCM to AAC (attempt ${attempt + 1})", e)
            }
        }
        Log.w(TAG, "Failed to feed PCM after 3 attempts, dropping frame")
        return false
    }

    /**
     * 排空编码器输出，区分 CSD（AudioSpecificConfig）和普通 AAC 帧
     */
    private fun drainEncoderOutput(encoder: MediaCodec) {
        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 0) // 非阻塞
            if (outputBufferIndex < 0) break

            try {
                if (encoderBufferInfo.size > 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex) ?: continue
                    val data = ByteArray(encoderBufferInfo.size)
                    outputBuffer.position(encoderBufferInfo.offset)
                    outputBuffer.get(data, 0, encoderBufferInfo.size)

                    val isConfig = (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                    if (isConfig) {
                        // CSD-0 = AudioSpecificConfig，用于 SDP config 参数
                        // 不通过 RTP 发送（mpeg4-generic 模式由 SDP 带外提供 config）
                        val ascHex = data.joinToString("") { "%02X".format(it) }
                        audioSpecificConfig = ascHex
                        Log.i(TAG, "AudioSpecificConfig captured: $ascHex")
                    } else {
                        // 普通 AAC 帧 — 直接发送裸 AAC（mpeg4-generic 格式不允许 ADTS 头）
                        rtpStreamer.sendAacFrame(data)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error draining encoder output", e)
            } finally {
                encoder.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    // ─── AAC 编码器工厂 ────────────────────────────────────────

    private fun createAacEncoder(): MediaCodec {
        val codecName = findBestAacEncoder()
        val codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)  // Low Complexity

            // PCM 16-bit 输入
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            // 低延迟模式: 减少编码器内部缓冲
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun findBestAacEncoder(): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_AUDIO_AAC)) continue

            val isHardware = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                codecInfo.isHardwareAccelerated
            if (isHardware) {
                Log.i(TAG, "Selected AAC encoder: ${codecInfo.name}")
                return codecInfo.name
            }
        }
        return null
    }

    /**
     * 构建 ADTS 头（7 字节），VLC 需要此头才能解码 AAC 裸流
     * ADTS = Audio Data Transport Stream
     */
    private fun createAdtsHeader(aacFrameLen: Int): ByteArray {
        val totalLen = aacFrameLen + 7

        val freqIndex = when (SAMPLE_RATE) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> 4
        }

        val header = ByteArray(7)
        // Byte 0: syncword[11:4]
        header[0] = 0xFF.toByte()
        // Byte 1: syncword[3:0]=0xF, ID=0(MPEG-4), layer=0, protection=1(no CRC)
        header[1] = 0xF1.toByte()
        // Byte 2: profile=01(AAC-LC), freq_idx, private=0, channel[2]=0
        header[2] = ((1 shl 6) or (freqIndex shl 2) or ((CHANNEL_COUNT shr 2) and 1)).toByte()
        // Byte 3: channel[1:0], original=0, home=0, frame_len[12:9]
        header[3] = (((CHANNEL_COUNT and 3) shl 6) or ((totalLen shr 9) and 0x0F)).toByte()
        // Byte 4: frame_len[8:1]
        header[4] = ((totalLen shr 1) and 0xFF).toByte()
        // Byte 5: frame_len[0]=1bit, buffer_fullness[10:4]=7bits (VBR=0x7FF)
        header[5] = (((totalLen and 1) shl 7) or 0x7F).toByte()
        // Byte 6: buffer_fullness[3:0]=4bits, num_blocks=0, pad=0
        header[6] = 0xF0.toByte()

        return header
    }
}
