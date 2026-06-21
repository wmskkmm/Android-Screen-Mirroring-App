package com.screenmirror.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Random

/**
 * RTP 流发送器 — 实现 RFC 3550 (RTP) + RFC 6184 (H264 FU-A 分片)
 *
 * 功能：
 * - 将 H264 NALU 按 MTU 做 FU-A 分片后封装为 RTP 包发送
 * - 将 AAC 帧封装为 RTP 包发送
 * - 使用 UDP 单播实现低延迟传输 (< 2s)
 *
 * 视频 RTP: port 5004, 音频 RTP: port 5006
 */
class RtpStreamer(
    private val targetHost: String,
    private val videoPort: Int = 5004,
    private val audioPort: Int = 5006,
    private val mtu: Int = 1400   // 典型局域网 MTU，给 IP+UDP 头留余量
) {
    companion object {
        private const val TAG = "RtpStreamer"
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_VERSION = 2
        private const val H264_PAYLOAD_TYPE = 96   // 动态类型
        private const val AAC_PAYLOAD_TYPE = 97    // 动态类型
        private const val H264_FU_A_TYPE = 28      // NALU type for FU-A
        private const val H264_STAP_A_TYPE = 24    // Single-Time Aggregation Packet
    }

    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null

    // RTP 序列号
    private var videoSequence = 0
    private var audioSequence = 0
    private val videoSsrc: Long
    private val audioSsrc: Long

    // 时间戳时钟频率
    private val videoClockRate = 90000   // H264: 90kHz
    private val audioClockRate = 44100   // AAC: 44.1kHz

    // 共享墙上时钟基准（用于视频和音频时间戳对齐）
    private var streamStartNanoTime = 0L

    @Volatile
    var isRunning = false
        private set

    init {
        val rng = Random()
        videoSsrc = rng.nextLong() and 0xFFFFFFFFL
        audioSsrc = rng.nextLong() and 0xFFFFFFFFL
    }

    /**
     * 初始化 UDP socket 并解析目标地址
     */
    fun start(): Boolean {
        return try {
            targetAddress = InetAddress.getByName(targetHost)
            videoSocket = DatagramSocket()
            audioSocket = DatagramSocket()
            videoSequence = 0
            audioSequence = 0
            streamStartNanoTime = System.nanoTime()
            isRunning = true
            Log.i(TAG, "RTP streamer started → ${targetHost}:$videoPort (video), $audioPort (audio)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTP streamer", e)
            false
        }
    }

    fun stop() {
        isRunning = false
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        audioSocket = null
        targetAddress = null
        Log.i(TAG, "RTP streamer stopped")
    }

    // (setFps 已废弃 — 时间戳基于墙上时钟，不再需要帧率)

    // ─── 墙上时钟 → RTP 时间戳 ──────────────────────────────────

    private fun getVideoTimestamp(): Int {
        val elapsedNs = System.nanoTime() - streamStartNanoTime
        return (elapsedNs * videoClockRate / 1_000_000_000L).toInt()
    }

    private fun getAudioTimestamp(): Int {
        val elapsedNs = System.nanoTime() - streamStartNanoTime
        return (elapsedNs * audioClockRate / 1_000_000_000L).toInt()
    }

    // ─── 视频 H264 发送 ────────────────────────────────────────

    /**
     * 发送一个 H264 NALU。
     * 如果 NALU 大小 <= MTU 则用 Single NALU 模式，
     * 否则用 FU-A 分片模式。
     */
    fun sendH264Nalu(nalu: ByteArray, offset: Int = 0, length: Int = nalu.size) {
        if (!isRunning) return
        val actualLength = if (length == nalu.size) length - offset else length
        if (actualLength <= 0) return

        val naluHeader = nalu[offset]
        val naluType = (naluHeader.toInt() and 0x1F)

        // 跳过 AU delimiter (type 9) — 不需要传输
        if (naluType == 9) return

        // 过滤掉 SPS/PPS 后面的非法 NALU
        if (naluType == 0) return

        if (actualLength <= mtu) {
            sendSingleNaluPacket(nalu, offset, actualLength)
        } else {
            sendFuAPackets(nalu, offset, actualLength)
        }
    }

    /**
     * 发送 SPS + PPS（组合在一个 STAP-A 包中发送，方便接收端初始化）
     */
    fun sendSpsPps(sps: ByteArray, pps: ByteArray) {
        if (!isRunning) return
        // 将 SPS 和 PPS 分别作为 Single NALU 包发送
        sendSingleNaluPacket(sps, 0, sps.size)
        sendSingleNaluPacket(pps, 0, pps.size)
        Log.d(TAG, "Sent SPS (${sps.size}B) + PPS (${pps.size}B)")
    }

    // ─── 音频 AAC 发送 ────────────────────────────────────────

    /**
     * 发送 AAC 编码帧（RFC 3640 mpeg4-generic / AAC-hbr 模式）
     *
     * RTP 载荷结构:
     *   [2 字节 AU-headers-length] [3 字节 AU-header] [裸 AAC 数据]
     *   AU-headers-length = 19 bits (13+3+3)
     */
    fun sendAacFrame(aacData: ByteArray, offset: Int = 0, length: Int = aacData.size) {
        if (!isRunning) return

        val actualLength = if (length == aacData.size) length - offset else length
        if (actualLength <= 0) return

        val sock = audioSocket ?: return
        val addr = targetAddress ?: return

        // AU-headers-length (16 bits) + AU-header (19 bits → 3 bytes)
        val auHeadersLengthBits = 19  // sizeLen(13) + indexLen(3) + deltaLen(3)
        val overhead = 2 + 3  // 2 bytes for length prefix + 3 bytes for AU-header
        try {
            val packetSize = RTP_HEADER_SIZE + overhead + actualLength
            val buffer = ByteArray(packetSize)

            // 写入 RTP 头（时间戳基于墙上时钟，与视频自动对齐）
            writeRtpHeader(
                buffer, 0,
                payloadType = AAC_PAYLOAD_TYPE,
                sequence = audioSequence++,
                timestamp = getAudioTimestamp(),
                ssrc = audioSsrc,
                marker = true
            )

            // 写入 AU-headers-length (16 bits, big-endian)
            buffer[RTP_HEADER_SIZE]     = ((auHeadersLengthBits shr 8) and 0xFF).toByte()
            buffer[RTP_HEADER_SIZE + 1] = (auHeadersLengthBits and 0xFF).toByte()

            // 写入 AU-header: 19 bits = [13-bit size][3-bit index][3-bit delta]
            val auHeaderValue = (actualLength.toLong() shl 6) or 0  // index=0, delta=0
            buffer[RTP_HEADER_SIZE + 2] = ((auHeaderValue shr 11) and 0xFF).toByte()
            buffer[RTP_HEADER_SIZE + 3] = ((auHeaderValue shr 3) and 0xFF).toByte()
            buffer[RTP_HEADER_SIZE + 4] = ((auHeaderValue and 0x07) shl 5).toByte()

            // 写入 AAC 载荷
            System.arraycopy(aacData, offset, buffer, RTP_HEADER_SIZE + overhead, actualLength)

            val dp = DatagramPacket(buffer, packetSize, addr, audioPort)
            synchronized(sock) { sock.send(dp) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send AAC frame", e)
        }
    }

    // ─── 内部实现 ──────────────────────────────────────────────

    /**
     * 发送单个 NALU 作为 RTP 包（无需分片的情况）
     */
    private fun sendSingleNaluPacket(nalu: ByteArray, offset: Int, length: Int) {
        val sock = videoSocket ?: return
        val addr = targetAddress ?: return

        try {
            val packetSize = RTP_HEADER_SIZE + length
            val buffer = ByteArray(packetSize)

            val naluHeader = nalu[offset]
            val isKeyFrame = ((naluHeader.toInt() and 0x1F) == 5)

            writeRtpHeader(
                buffer, 0,
                payloadType = H264_PAYLOAD_TYPE,
                sequence = videoSequence++,
                timestamp = getVideoTimestamp(),
                ssrc = videoSsrc,
                marker = isKeyFrame
            )

            System.arraycopy(nalu, offset, buffer, RTP_HEADER_SIZE, length)

            val dp = DatagramPacket(buffer, packetSize, addr, videoPort)
            synchronized(sock) { sock.send(dp) }

            Log.v(TAG, "Sent single NALU type=${naluHeader.toInt() and 0x1F} size=$length")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send single NALU", e)
        }
    }

    /**
     * FU-A 分片发送（RFC 6184）
     *
     * FU-A 结构：
     * ┌────────────────┬──────────────┬──────────────────────────┐
     * │ FU indicator   │ FU header    │ FU payload               │
     * │ (1 byte)       │ (1 byte)     │ (MTU - header_size 字节) │
     * └────────────────┴──────────────┴──────────────────────────┘
     *
     * FU indicator: F(1bit) | NRI(2bit) | Type=28(5bit)
     * FU header:    S(1bit) | E(1bit) | R(1bit) | NALU Type(5bit)
     *
     * S=1: start fragment, E=1: end fragment
     */
    private fun sendFuAPackets(nalu: ByteArray, offset: Int, length: Int) {
        val sock = videoSocket ?: return
        val addr = targetAddress ?: return

        val naluHeader = nalu[offset]
        val nri = (naluHeader.toInt() and 0x60) shr 5   // NRI bits (重要性)
        val naluType = naluHeader.toInt() and 0x1F
        val isKeyFrame = (naluType == 5)

        // FU indicator: F(0) | NRI | Type=28
        val fuIndicator = ((nri shl 5) or H264_FU_A_TYPE).toByte()

        // 跳过 NALU header byte，从 NALU 载荷开始
        val payloadOffset = offset + 1
        val payloadLength = length - 1
        val maxFuPayload = mtu - RTP_HEADER_SIZE - 2  // 2 bytes for FU indicator + FU header

        var fragmentOffset = 0
        while (fragmentOffset < payloadLength) {
            val fragRemaining = payloadLength - fragmentOffset
            val currentFragSize = minOf(fragRemaining, maxFuPayload)
            val isStart = (fragmentOffset == 0)
            val isEnd = (fragmentOffset + currentFragSize >= payloadLength)

            // FU header: S(1) | E(1) | R(0) | NALU Type(5)
            val fuHeader = (
                ((if (isStart) 1 else 0) shl 7) or
                ((if (isEnd)   1 else 0) shl 6) or
                naluType
            ).toByte()

            val packetSize = RTP_HEADER_SIZE + 2 + currentFragSize
            val buffer = ByteArray(packetSize)

            // RTP header — marker=1 on the last fragment of the NALU (RFC 6184)
            writeRtpHeader(
                buffer, 0,
                payloadType = H264_PAYLOAD_TYPE,
                sequence = videoSequence++,
                timestamp = getVideoTimestamp(),
                ssrc = videoSsrc,
                marker = isEnd
            )

            // FU indicator + FU header
            buffer[RTP_HEADER_SIZE] = fuIndicator
            buffer[RTP_HEADER_SIZE + 1] = fuHeader

            // FU payload
            System.arraycopy(nalu, payloadOffset + fragmentOffset,
                buffer, RTP_HEADER_SIZE + 2, currentFragSize)

            val dp = DatagramPacket(buffer, packetSize, addr, videoPort)
            synchronized(sock) { sock.send(dp) }

            fragmentOffset += currentFragSize
        }

        Log.v(TAG, "Sent FU-A NALU type=$naluType, fragments=${Math.ceil(payloadLength.toDouble() / maxFuPayload).toInt()}")
    }

    /**
     * 写入 RTP 固定头（12 字节，RFC 3550 §5.1）
     */
    private fun writeRtpHeader(
        buffer: ByteArray,
        offset: Int,
        payloadType: Int,
        sequence: Int,
        timestamp: Int,
        ssrc: Long,
        marker: Boolean
    ) {
        // Byte 0: V=2(bit7-6) | P=0(bit5) | X=0(bit4) | CC=0(bit3-0)
        buffer[offset] = (RTP_VERSION shl 6).toByte()

        // Byte 1: M(bit7) | PT(bit6-0)
        buffer[offset + 1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()

        // Byte 2-3: sequence number (网络字节序 / big-endian)
        buffer[offset + 2] = ((sequence shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (sequence and 0xFF).toByte()

        // Byte 4-7: timestamp (网络字节序)
        buffer[offset + 4] = ((timestamp shr 24) and 0xFF).toByte()
        buffer[offset + 5] = ((timestamp shr 16) and 0xFF).toByte()
        buffer[offset + 6] = ((timestamp shr 8) and 0xFF).toByte()
        buffer[offset + 7] = (timestamp and 0xFF).toByte()

        // Byte 8-11: SSRC (网络字节序)
        buffer[offset + 8] = ((ssrc shr 24) and 0xFF).toByte()
        buffer[offset + 9] = ((ssrc shr 16) and 0xFF).toByte()
        buffer[offset + 10] = ((ssrc shr 8) and 0xFF).toByte()
        buffer[offset + 11] = (ssrc and 0xFF).toByte()
    }

    /**
     * 已废弃 — 视频时间戳现在基于墙上时钟自动计算，无需手动推进。
     * 保留此方法以兼容旧调用点。
     */
    fun advanceVideoTimestamp(frameDurationUs: Long = 33333L) {
        // no-op: 时间戳在 getVideoTimestamp() 中基于 System.nanoTime() 实时计算
    }
}
