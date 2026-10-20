// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.openxplay.stream.core.VideoCodec
import org.openxplay.stream.core.audio.Adts
import org.openxplay.stream.core.codec.AvcConfig
import org.openxplay.stream.core.codec.Nal

/** Received Annex B access unit and the most recent SPS, copied from the wire. */
public data class CapturedAccessUnit(
    public val data: ByteArray,
    public val sps: ByteArray,
    public val keyFrame: Boolean,
    public val pts90kHz: Long,
) {
    public val size: Pair<Int, Int> get() = AvcConfig.parseSps(sps)
}

public data class CapturedAudioUnit(public val data: ByteArray, public val pts90kHz: Long)

/** Independent single-program PSI/PES validator; no muxer implementation is reused. */
internal class TsCapture {
    val audio = CopyOnWriteArrayList<CapturedAudioUnit>()
    private var audioPid = -1
    private var audioPes = ByteArrayOutputStream()
    val units = CopyOnWriteArrayList<CapturedAccessUnit>()
    @Volatile var patCount = 0
        private set
    @Volatile var pmtCount = 0
        private set
    @Volatile var pcrCount = 0
        private set
    private var pmtPid = -1
    private var videoPid = -1
    private val continuity = mutableMapOf<Int, Int>()
    private var rtpSequence: Int? = null
    private var ssrc: Int? = null
    private var pes = ByteArrayOutputStream()
    private var currentSps: ByteArray? = null
    private val tablesSinceStart = mutableSetOf<Int>()
    private var pesTables = emptySet<Int>()

    fun packet(bytes: ByteArray) {
        fun u8(n: Int) = bytes[n].toInt() and 255
        require(u8(0) == 0x80 && u8(1) and 127 == 33 && (bytes.size - 12) % 188 == 0)
        val sequence = (u8(2) shl 8) or u8(3)
        rtpSequence?.let { require(sequence == (it + 1) and 65535) { "RTP sequence gap" } }
        rtpSequence = sequence
        val source = java.nio.ByteBuffer.wrap(bytes, 8, 4).int
        ssrc?.let { require(it == source) }; ssrc = source
        for (offset in 12 until bytes.size step 188) ts(bytes.copyOfRange(offset, offset + 188))
    }
    private fun ts(bytes: ByteArray) {
        fun u8(n: Int) = bytes[n].toInt() and 255
        require(u8(0) == 0x47 && u8(1) and 0x80 == 0 && u8(3) and 0xc0 == 0)
        val pid = ((u8(1) and 31) shl 8) or u8(2)
        val start = u8(1) and 0x40 != 0
        val adaptation = (u8(3) shr 4) and 3; require(adaptation != 0)
        val counter = u8(3) and 15
        val payload = adaptation and 1 != 0
        if (pid != 0x1fff) {
            continuity[pid]?.let { previous ->
                require(counter == (if (payload) (previous + 1) and 15 else previous)) { "TS continuity PID $pid" }
            }
            continuity[pid] = counter
        }
        var offset = 4
        if (adaptation and 2 != 0) {
            val length = u8(4); require(length <= 183)
            if (length > 0 && u8(5) and 0x10 != 0) { require(length >= 7); pcrCount++ }
            offset += length + 1
        }
        if (!payload) return
        require(offset < 188)
        if (pid == 0 || pid == pmtPid) {
            require(start)
            offset += 1 + u8(offset)
            require(offset + 3 <= 188)
            val length = ((u8(offset + 1) and 15) shl 8) or u8(offset + 2)
            require(offset + 3 + length <= 188)
            val section = bytes.copyOfRange(offset, offset + 3 + length)
            require(crc(section) == 0) { "PSI CRC" }
            require(section[5].toInt() and 1 == 1 && section[6] == 0.toByte() && section[7] == 0.toByte())
            if (pid == 0) {
                require(section.size == 16 && section[0] == 0.toByte())
                require((section[8].toInt() and 255) * 256 + (section[9].toInt() and 255) == 1)
                pmtPid = ((section[10].toInt() and 31) shl 8) or (section[11].toInt() and 255)
                patCount++; tablesSinceStart += 0
            } else {
                require(section[0] == 2.toByte() && section.size >= 21)
                val infoLength = ((section[10].toInt() and 15) shl 8) or (section[11].toInt() and 255)
                val stream = 12 + infoLength
                require(section[stream] == 0x1b.toByte()) { "PMT must advertise H.264" }
                videoPid = ((section[stream + 1].toInt() and 31) shl 8) or (section[stream + 2].toInt() and 255)
                require(videoPid == ((section[8].toInt() and 31) shl 8) or (section[9].toInt() and 255))
                if (section.size >= stream + 14) {
                    require(section[stream + 5] == 0x0f.toByte())
                    audioPid = ((section[stream + 6].toInt() and 31) shl 8) or (section[stream + 7].toInt() and 255)
                    require(audioPid == 0x102)
                }
                pmtCount++; tablesSinceStart += pid
            }
        } else if (pid == audioPid) {
            if (start) { require(audioPes.size() == 0); audioPes = ByteArrayOutputStream() }
            audioPes.write(bytes, offset, 188 - offset)
            val data = audioPes.toByteArray()
            if (data.size >= 6) {
                val length = ((data[4].toInt() and 255) shl 8) or (data[5].toInt() and 255)
                if (data.size >= length + 6) {
                    require(data.size == length + 6 && data[3] == 0xc0.toByte() && data[8] == 5.toByte())
                    fun u(n: Int) = data[n].toLong() and 255
                    val pts = ((u(9) shr 1 and 7) shl 30) or (u(10) shl 22) or
                        ((u(11) shr 1) shl 15) or (u(12) shl 7) or (u(13) shr 1)
                    val adts = data.copyOfRange(14, data.size)
                    require(Adts.parse(adts).size == 1)
                    audio += CapturedAudioUnit(adts, pts); audioPes = ByteArrayOutputStream()
                }
            }
        } else if (pid == videoPid) {
            if (start) {
                flush()
                pes = ByteArrayOutputStream()
                pesTables = tablesSinceStart.toSet(); tablesSinceStart.clear()
            }
            require(pes.size() + 188 - offset <= 4 * 1024 * 1024)
            pes.write(bytes, offset, 188 - offset)
        } else require(pid == 0x1fff) { "Unexpected TS PID $pid" }
    }
    private fun flush() {
        if (pes.size() == 0) return
        val bytes = pes.toByteArray()
        fun u8(n: Int) = bytes[n].toInt() and 255
        require(bytes.size >= 14 && u8(0) == 0 && u8(1) == 0 && u8(2) == 1 && u8(3) == 0xe0)
        require(u8(7) and 0x80 != 0 && u8(8) >= 5 && u8(9) and 1 == 1 && u8(11) and 1 == 1 && u8(13) and 1 == 1)
        val pts = ((u8(9).toLong() shr 1 and 7) shl 30) or (u8(10).toLong() shl 22) or
            ((u8(11).toLong() shr 1) shl 15) or (u8(12).toLong() shl 7) or (u8(13).toLong() shr 1)
        val data = bytes.copyOfRange(9 + u8(8), bytes.size)
        val nals = Nal.units(data, Nal.Format.ANNEX_B).toList()
        // Carlink head units reject an access unit that starts with an AUD, so the muxer keeps them
        // out (2026-10-19); accept either form and only require a real video NAL first.
        require(nals.first()[0].toInt() and 31 in setOf(1, 5, 7, 8, 9)) { "video NAL required" }
        nals.lastOrNull { it[0].toInt() and 31 == 7 }?.let { currentSps = it.copyOf() }
        val key = Nal.isKeyFrame(data, VideoCodec.H264, Nal.Format.ANNEX_B)
        if (key) {
            require(nals.any { it[0].toInt() and 31 == 7 } && nals.any { it[0].toInt() and 31 == 8 })
            require(0 in pesTables && pmtPid in pesTables) { "PAT/PMT required before IDR" }
        }
        require(nals.any { it[0].toInt() and 31 in setOf(1, 5) })
        units += CapturedAccessUnit(data, checkNotNull(currentSps).copyOf(), key, pts)
    }
    private fun crc(bytes: ByteArray): Int {
        var value = -1
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl 24)
            repeat(8) { value = (value shl 1) xor if (value < 0) 0x04c11db7 else 0 }
        }
        return value
    }
}
