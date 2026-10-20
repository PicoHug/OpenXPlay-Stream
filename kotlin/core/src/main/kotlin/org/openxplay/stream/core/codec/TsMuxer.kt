// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.core.codec

import java.io.ByteArrayOutputStream
import org.openxplay.stream.core.audio.Adts
import org.openxplay.stream.core.VideoCodec

/**
 * Single-program H.264 or H.265/AAC transport stream. Calls must be serialized and non-reentrant.
 * The sink consumes a borrowed 188-byte buffer synchronously; copy it to retain packets.
 * Timestamps use a shared, nonnegative microsecond timeline without B-frames.
 * Submit calls in timestamp order. PCR cadence follows media time, including gaps;
 * this muxer does not schedule output while no input is arriving.
 */
public class TsMuxer(
    private val sink: (ByteArray) -> Unit,
    public val pmtPid: Int = 0x100,
    public val videoPid: Int = 0x101,
    public val audioPid: Int = 0x102,
    public val programNumber: Int = 1,
    public val audioEnabled: Boolean = false,
    /**
     * Carlink callers must pass false: the head unit detects keyframes only when the access unit
     * starts with parameter sets or an IDR slice. A leading AUD hides the keyframe.
     */
    public val insertAud: Boolean = true,
    public val psiIntervalFrames: Int = 15,
    public val videoCodec: VideoCodec = VideoCodec.H264,
    public val pmtVersion: Int = 0,
) {
    private val packet = ByteArray(188)
    private val counters = IntArray(8192)
    private var videoPayloadWritten = false
    private var videoFrames = 0
    private var tablesWritten = false
    private var lastPcrUs: Long? = null
    private val pat: ByteArray
    private val pmt: ByteArray

    init {
        require(listOf(pmtPid, videoPid, audioPid).all { it in 0x20..0x1ffe }) { "Invalid elementary or PMT PID" }
        require(setOf(pmtPid, videoPid, audioPid).size == 3) { "PIDs must be distinct" }
        require(programNumber in 1..0xffff) { "Invalid program number" }
        require(pmtVersion in 0..31) { "Invalid PMT version" }
        require(psiIntervalFrames > 0) { "PSI interval must be positive" }
        pat = section(byteArrayOf(
            0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0,
            (programNumber ushr 8).toByte(), programNumber.toByte(),
            (0xe0 or (pmtPid ushr 8)).toByte(), pmtPid.toByte(),
        ))
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(
            2, 0xb0.toByte(), (if (audioEnabled) 23 else 18).toByte(),
            (programNumber ushr 8).toByte(), programNumber.toByte(), (0xc1 or (pmtVersion shl 1)).toByte(), 0, 0,
            (0xe0 or (videoPid ushr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0,
        ))
        fun stream(type: Int, pid: Int) {
            body.write(byteArrayOf(type.toByte(), (0xe0 or (pid ushr 8)).toByte(), pid.toByte(), 0xf0.toByte(), 0))
        }
        stream(if (videoCodec == VideoCodec.H265) 0x24 else 0x1b, videoPid)
        if (audioEnabled) stream(0x0f, audioPid)
        pmt = section(body.toByteArray())
    }

    /** AVCC always uses four-byte lengths, independently of the source avcC length size. */
    public fun writeVideo(frame: ByteArray, config: AvcConfig, ptsUs: Long, keyFrame: Boolean) {
        require(videoCodec == VideoCodec.H264) { "Muxer codec mismatch" }
        require(ptsUs >= 0) { "Negative timestamp" }
        require(frame.isNotEmpty()) { "Empty video access unit" }
        val annexB = Nal.avccToAnnexB(frame) // Validate before emitting anything.
        val payload = ByteArrayOutputStream()
        if (insertAud) payload.write(byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte()))
        if (keyFrame) {
            for (nal in config.sps + config.pps) {
                payload.write(byteArrayOf(0, 0, 0, 1))
                payload.write(nal)
            }
        }
        payload.write(annexB)
        writeVideoPayload(payload.toByteArray(), ptsUs, keyFrame)
    }

    /** Four-byte length-prefixed HEVC; every IRAP starts with hvcC parameter sets, never an AUD. */
    public fun writeVideo(frame: ByteArray, config: HevcConfig, ptsUs: Long, keyFrame: Boolean) {
        require(videoCodec == VideoCodec.H265) { "Muxer codec mismatch" }
        require(ptsUs >= 0) { "Negative timestamp" }
        require(frame.isNotEmpty()) { "Empty video access unit" }
        val units = Nal.units(frame).toList()
        require(units.all { it.size >= 2 }) { "Truncated HEVC NAL header" }
        fun type(nal: ByteArray): Int = (nal[0].toInt() ushr 1) and 63
        val irap = units.any { type(it) in 16..21 }
        val payload = ByteArrayOutputStream()
        fun append(nal: ByteArray) {
            payload.write(byteArrayOf(0, 0, 0, 1)); payload.write(nal)
        }
        if (irap) (config.vps + config.sps + config.pps).forEach(::append)
        // Replace in-band parameter sets and omit optional prefix SEI on IRAPs so the slice follows
        // VPS/SPS/PPS directly. Prefix SEI must not be moved behind the slice as if it were suffix SEI.
        units.filter { type(it) != 35 && (!irap || type(it) !in 32..34 && type(it) != 39) }.forEach(::append)
        writeVideoPayload(payload.toByteArray(), ptsUs, irap || keyFrame)
    }

    private fun writeVideoPayload(payload: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!tablesWritten || keyFrame || videoFrames == 0) writeTables()
        videoFrames = (videoFrames + 1) % psiIntervalFrames
        advancePcr(ptsUs)
        val pcr = if (lastPcrUs == null || keyFrame || ptsUs - lastPcrUs!! >= PCR_INTERVAL_US) ptsUs else null
        writePes(videoPid, 0xe0, payload, ptsUs, keyFrame, pcr)
        if (pcr != null) lastPcrUs = pcr
    }

    /** Accepts one complete AAC ADTS frame, including its header. */
    public fun writeAudio(adtsFrame: ByteArray, ptsUs: Long) {
        require(audioEnabled) { "AAC stream is disabled" }
        require(ptsUs >= 0) { "Negative timestamp" }
        Adts.validateFrame(adtsFrame)
        if (!tablesWritten) writeTables()
        advancePcr(ptsUs, includeEndpoint = true)
        if (lastPcrUs == null) {
            writePcrOnly(ptsUs)
            lastPcrUs = ptsUs
        }
        writePes(audioPid, 0xc0, adtsFrame, ptsUs, false, null)
    }

    private fun writeTables() {
        writePayload(0, byteArrayOf(0) + pat, false, null)
        writePayload(pmtPid, byteArrayOf(0) + pmt, false, null)
        tablesWritten = true
    }

    // Fill timestamp gaps on the video PID, including gaps advanced by audio calls.
    private fun advancePcr(ptsUs: Long, includeEndpoint: Boolean = false) {
        var previous = lastPcrUs ?: return
        // A jump beyond MAX_PCR_FILL_US is a timestamp discontinuity, not a gap to fill: filling a jump
        // of hours generated millions of PCR packets and exhausted the heap (2026-10-15).
        if (ptsUs - previous > MAX_PCR_FILL_US) { lastPcrUs = ptsUs - PCR_INTERVAL_US; return }
        while (ptsUs > previous && (ptsUs - previous > PCR_INTERVAL_US ||
                includeEndpoint && ptsUs - previous == PCR_INTERVAL_US)) {
            previous += PCR_INTERVAL_US
            writePcrOnly(previous)
            lastPcrUs = previous
        }
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, ptsUs: Long, randomAccess: Boolean, pcr: Long?) {
        val pes = ByteArray(14 + payload.size)
        pes[2] = 1
        pes[3] = streamId.toByte()
        val size = if (streamId == 0xe0) 0 else payload.size + 8
        require(size <= 0xffff) { "Audio PES is too large" }
        pes[4] = (size ushr 8).toByte()
        pes[5] = size.toByte()
        pes[6] = 0x80.toByte()
        pes[7] = 0x80.toByte() // PTS only: DTS is implicitly equal to PTS.
        pes[8] = 5
        val pts = to90kHz(ptsUs)
        pes[9] = (0x21 or (((pts ushr 30) and 7).toInt() shl 1)).toByte()
        pes[10] = (pts ushr 22).toByte()
        pes[11] = ((((pts ushr 15) and 0x7f).toInt() shl 1) or 1).toByte()
        pes[12] = (pts ushr 7).toByte()
        pes[13] = (((pts and 0x7f).toInt() shl 1) or 1).toByte()
        payload.copyInto(pes, 14)
        writePayload(pid, pes, randomAccess, pcr)
    }

    private fun writePayload(pid: Int, bytes: ByteArray, randomAccess: Boolean, pcrUs: Long?) {
        var offset = 0
        while (offset < bytes.size) {
            val first = offset == 0
            val pcr = if (first) pcrUs else null
            val random = first && randomAccess
            val minimumAdaptation = if (pcr != null) 8 else if (random) 2 else 0
            val count = minOf(bytes.size - offset, 184 - minimumAdaptation)
            val adaptation = 184 - count
            header(pid, first, if (adaptation == 0) 1 else 3, counters[pid])
            counters[pid] = (counters[pid] + 1) and 15
            if (pid == videoPid) videoPayloadWritten = true
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    packet[5] = ((if (random) 0x40 else 0) or (if (pcr != null) 0x10 else 0)).toByte()
                    if (pcr != null) encodePcr(pcr)
                }
            }
            bytes.copyInto(packet, 4 + adaptation, offset, offset + count)
            sink(packet)
            offset += count
        }
    }

    private fun writePcrOnly(ptsUs: Long) {
        header(videoPid, false, 2, if (videoPayloadWritten) (counters[videoPid] + 15) and 15 else 0)
        packet[4] = 183.toByte()
        packet[5] = 0x10
        encodePcr(ptsUs)
        sink(packet)
    }

    private fun header(pid: Int, start: Boolean, control: Int, counter: Int) {
        packet.fill(0xff.toByte())
        packet[0] = 0x47
        packet[1] = ((pid ushr 8) or if (start) 0x40 else 0).toByte()
        packet[2] = pid.toByte()
        packet[3] = ((control shl 4) or counter).toByte()
    }

    private fun encodePcr(ptsUs: Long) {
        val base = to90kHz(ptsUs)
        packet[6] = (base ushr 25).toByte()
        packet[7] = (base ushr 17).toByte()
        packet[8] = (base ushr 9).toByte()
        packet[9] = (base ushr 1).toByte()
        val extension = ((ptsUs % 100) * 27 % 300).toInt()
        packet[10] = (((base and 1).toInt() shl 7) or 0x7e or (extension ushr 8)).toByte()
        packet[11] = extension.toByte()
    }

    public companion object {
        private const val PCR_INTERVAL_US: Long = 40_000
        private const val MAX_PCR_FILL_US: Long = 2_000_000
        private const val TIMESTAMP_MASK: Long = 0x1ffffffffL

        /** Integer conversion avoids overflowing even for Long.MAX_VALUE microseconds. */
        public fun to90kHz(ptsUs: Long): Long {
            require(ptsUs >= 0) { "Negative timestamp" }
            return (((ptsUs / 100) and TIMESTAMP_MASK) * 9 + (ptsUs % 100) * 9 / 100) and TIMESTAMP_MASK
        }

        /** Non-reflected CRC-32/MPEG-2: polynomial 0x04C11DB7, initial all ones, no final XOR. */
        public fun crc32Mpeg2(bytes: ByteArray): Int {
            var crc = -1
            for (byte in bytes) {
                crc = crc xor (u8(byte) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            return crc
        }

        private fun section(body: ByteArray): ByteArray {
            val crc = crc32Mpeg2(body)
            return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xff
    }
}
