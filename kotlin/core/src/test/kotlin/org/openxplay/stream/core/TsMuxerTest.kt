// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.core

import org.junit.jupiter.api.Test
import org.openxplay.stream.core.audio.Adts
import org.openxplay.stream.core.audio.AudioSpecificConfig
import org.openxplay.stream.core.codec.AvcConfig
import org.openxplay.stream.core.codec.Nal
import org.openxplay.stream.core.codec.TsMuxer
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TsMuxerTest {
    private val config = AvcConfig.parse(vector("avc-baseline-640x480"))

    @Test fun avcBytesMatchPreHevcMuxer() {
        // Captured from the pre-HEVC muxer for the same frames, gaps, keyframes and AAC packets.
        val golden = listOf("3ecff05fe0d5fb49b7d30fe9a99b2fe460f82e5521e91efb51cc5d954fff8334",
            "099d56261755fc89412d8b131f75e00a3aa620aad98249a78ff3cc7888de66ff",
            "dca9ccef09f333f3617fb96491777bcbfd37ec71b0b2bf46bccfbde70eed1ac4",
            "b6f87fe763dac4d774025a8268c96b51bbf0060bb5cf038a12d118755a9ab00c")
        var index = 0
        for (aud in listOf(false, true)) for (audio in listOf(false, true)) {
            val actual = java.io.ByteArrayOutputStream()
            val mux = TsMuxer({ actual.write(it) }, insertAud = aud, audioEnabled = audio)
            repeat(35) { index ->
                val key = index % 12 == 0
                mux.writeVideo(frame(500, if (key) 5 else 1), config, index * 100_000L, key)
                if (audio) mux.writeAudio(adts(), index * 100_000L)
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(actual.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(golden[index++], hash)
        }
    }

    @Test fun hevcPmtAndEveryIrapStartsWithParameterSetsWithoutAud() {
        val config = org.openxplay.stream.core.codec.HevcConfig.parse(vector("hevc-main-1920x1080"))
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets += it.copyOf() }, videoCodec = VideoCodec.H265, pmtVersion = 7)
        fun nal(type: Int) = byteArrayOf(0, 0, 0, 3, (type shl 1).toByte(), 1, 0x80.toByte())
        for (type in 16..21) muxer.writeVideo(nal(35) + nal(39) + nal(type) + nal(40), config, (type - 16) * 33_333L, false)
        val pmt = section(packets.first { pid(it) == 0x100 })
        assertEquals(0x24, u8(pmt[12])); assertEquals(7, (u8(pmt[5]) ushr 1) and 31)
        assertEquals(0L, independentCrc(pmt))
        for ((index, pes) in pesPackets(packets, 0x101).withIndex()) {
            assertEquals(0xe0, u8(pes[3]))
            val units = Nal.units(pes.copyOfRange(14, pes.size), Nal.Format.ANNEX_B).toList()
            assertEquals(listOf(32, 33, 34, 16 + index, 40), units.map { (u8(it[0]) ushr 1) and 63 })
            (config.vps + config.sps + config.pps).zip(units).forEach { (a, b) -> assertContentEquals(a, b) }
        }
        assertEquals(6, packets.count { pid(it) == 0 })
        assertTrue(packets.filter(::hasPcr).map { pcr(it).first }.zipWithNext().all { (a, b) -> b - a <= 40_000 * 27 })
    }

    @Test
    fun packetSizesSyncStuffingAndBorrowedBuffer() {
        val packets = mutableListOf<ByteArray>()
        val borrowed = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ borrowed.add(it); packets.add(it.copyOf()) }, insertAud = false)
        // Exercise zero-length adaptation fields, flags-only fields and multi-packet PES.
        for (size in listOf(163, 164, 165, 166, 167, 168, 169, 1000)) {
            muxer.writeVideo(frame(size), config, 0, false)
        }
        assertTrue(borrowed.all { it === borrowed.first() })
        for (packet in packets) {
            assertEquals(188, packet.size)
            assertEquals(0x47, u8(packet[0]))
            assertEquals(0, u8(packet[1]) and 0x80)
            if (control(packet) == 3 && u8(packet[4]) > 0) {
                val start = if (u8(packet[5]) and 0x10 != 0) 12 else 6
                assertTrue((start until payloadOffset(packet)).all { u8(packet[it]) == 0xff })
            }
        }
        val pes = pesPackets(packets, 0x101)
        assertEquals(8, pes.size)
        for ((index, size) in listOf(163, 164, 165, 166, 167, 168, 169, 1000).withIndex()) {
            assertContentEquals(Nal.avccToAnnexB(frame(size)), pes[index].copyOfRange(14, pes[index].size))
        }
        assertTrue(packets.any { control(it) == 3 && u8(it[4]) == 0 })
    }

    @Test
    fun patPmtFieldsCrcAndConfiguredPids() {
        assertEquals(0x0376e6e7, TsMuxer.crc32Mpeg2("123456789".toByteArray()))
        for (audio in listOf(false, true)) {
            val packets = mutableListOf<ByteArray>()
            val muxer = TsMuxer({ packets.add(it.copyOf()) }, 0x120, 0x121, 0x122, 42, audio)
            muxer.writeVideo(frame(), config, 0, true)
            val pat = section(packets.single { pid(it) == 0 })
            val pmt = section(packets.single { pid(it) == 0x120 })
            assertEquals(0, u8(pat[0]))
            assertEquals(42, word(pat, 8))
            assertEquals(0x120, word(pat, 10) and 0x1fff)
            assertEquals(2, u8(pmt[0]))
            assertEquals(42, word(pmt, 3))
            assertEquals(0x121, word(pmt, 8) and 0x1fff)
            assertEquals(0x1b, u8(pmt[12]))
            assertEquals(0x121, word(pmt, 13) and 0x1fff)
            assertEquals(if (audio) 26 else 21, pmt.size)
            if (audio) {
                assertEquals(0x0f, u8(pmt[17]))
                assertEquals(0x122, word(pmt, 18) and 0x1fff)
            }
            for (bytes in listOf(pat, pmt)) {
                assertEquals(0L, independentCrc(bytes))
                assertEquals(bytes.size - 3, word(bytes, 1) and 0xfff)
            }
        }
    }

    @Test
    fun pesPtsMarkersRoundingAndWrap() {
        val timestamps = listOf(0L, 1L, 12_345_678L, 95_443_717_700L, Long.MAX_VALUE)
        for (timestamp in timestamps) {
            val packets = mutableListOf<ByteArray>()
            TsMuxer({ packets.add(it.copyOf()) }).writeVideo(frame(), config, timestamp, false)
            val pes = pesPackets(packets, 0x101).single()
            assertContentEquals(hex("00 00 01 e0 00 00 80 80 05"), pes.copyOfRange(0, 9))
            assertEquals(0x20, u8(pes[9]) and 0xf0)
            for (offset in listOf(9, 11, 13)) assertEquals(1, u8(pes[offset]) and 1)
            val expected = BigInteger.valueOf(timestamp).multiply(BigInteger.valueOf(90_000))
                .divide(BigInteger.valueOf(1_000_000)).and(BigInteger.valueOf(0x1ffffffffL)).toLong()
            assertEquals(expected, pts(pes))
            assertEquals(expected, TsMuxer.to90kHz(timestamp))
            assertEquals(expected, pcr(packets.first { hasPcr(it) }).first / 300)
        }
    }

    @Test
    fun continuityCountersWrapPerPidAndIgnorePcrOnlyPackets() {
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets.add(it.copyOf()) }, audioEnabled = true, psiIntervalFrames = 1)
        repeat(40) { index ->
            muxer.writeVideo(frame(1500), config, index * 100_000L, index % 10 == 0)
            muxer.writeAudio(adts(), index * 100_000L)
        }
        val next = mutableMapOf<Int, Int>()
        for (packet in packets) {
            val pid = pid(packet)
            val counter = u8(packet[3]) and 15
            if (control(packet) and 1 != 0) {
                assertEquals(next[pid] ?: 0, counter, "PID $pid")
                next[pid] = (counter + 1) and 15
            } else {
                assertEquals(((next[pid] ?: 1) + 15) and 15, counter)
            }
        }
        assertEquals(setOf(0, 0x100, 0x101, 0x102), next.keys)
        assertEquals(40, packets.count { pid(it) == 0 })
        assertEquals(40, packets.count { pid(it) == 0x100 })
        assertTrue(packets.any { control(it) == 2 })
    }

    @Test
    fun audParameterSetsRandomAccessAndPsiCadence() {
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets.add(it.copyOf()) })
        val keys = setOf(0, 7, 16)
        repeat(18) { index -> muxer.writeVideo(frame(type = if (index in keys) 5 else 1), config, index * 33_333L, index in keys) }
        val pes = pesPackets(packets, 0x101)
        for (index in pes.indices) {
            val units = Nal.units(pes[index].copyOfRange(14, pes[index].size), Nal.Format.ANNEX_B).toList()
            assertEquals(if (index in keys) listOf(9, 7, 8, 5) else listOf(9, 1), units.map { u8(it[0]) and 31 })
            if (index in keys) {
                assertContentEquals(config.sps.single(), units[1])
                assertContentEquals(config.pps.single(), units[2])
            }
        }
        val starts = packets.filter { pid(it) == 0x101 && start(it) }
        assertEquals(keys, starts.indices.filter { u8(starts[it][5]) and 0x40 != 0 && control(starts[it]) == 3 }.toSet())
        val tableFrames = mutableListOf<Int>()
        var frameIndex = 0
        for ((index, packet) in packets.withIndex()) {
            if (pid(packet) == 0) {
                tableFrames.add(frameIndex)
                assertEquals(0x100, pid(packets[index + 1]))
            }
            if (pid(packet) == 0x101 && start(packet)) frameIndex++
        }
        assertEquals(listOf(0, 7, 15, 16), tableFrames)
        val withoutAud = mutableListOf<ByteArray>()
        TsMuxer({ withoutAud.add(it.copyOf()) }, insertAud = false).writeVideo(frame(type = 5), config, 0, true)
        val bytes = pesPackets(withoutAud, 0x101).single().drop(14).toByteArray()
        assertEquals(listOf(7, 8, 5), Nal.units(bytes, Nal.Format.ANNEX_B).map { u8(it[0]) and 31 }.toList())
    }

    @Test
    fun pcrCadenceAcrossSparseVideoAndAudio() {
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets.add(it.copyOf()) }, audioEnabled = true)
        muxer.writeVideo(frame(type = 5), config, 0, true)
        muxer.writeVideo(frame(), config, 33_333, false)
        muxer.writeVideo(frame(), config, 66_666, false)
        muxer.writeVideo(frame(type = 5), config, 100_001, true)
        muxer.writeAudio(adts(), 140_001)
        muxer.writeVideo(frame(), config, 300_001, false)
        val clocks = packets.filter(::hasPcr).map {
            assertEquals(0x101, pid(it))
            assertEquals(0x7e, u8(it[10]) and 0x7e)
            pcr(it).first
        }
        val expected = listOf(0L, 40_000L, 80_000L, 100_001L, 140_001L, 180_001L, 220_001L, 260_001L, 300_001L)
        assertEquals(expected.map { it * 27 }, clocks)
        assertTrue(clocks.zipWithNext().all { (a, b) -> b - a <= 40_000 * 27 })
        // Audio can initialize the program clock before the first video frame.
        val audioFirst = mutableListOf<ByteArray>()
        TsMuxer({ audioFirst.add(it.copyOf()) }, audioEnabled = true).writeAudio(adts(), 0)
        assertEquals(0, u8(audioFirst.first { hasPcr(it) }[3]) and 15)
    }

    @Test
    fun audioPesAndRejectedInputs() {
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets.add(it.copyOf()) }, audioEnabled = true)
        muxer.writeAudio(adts(), 1_000_000)
        val pes = pesPackets(packets, 0x102).single()
        assertEquals(0xc0, u8(pes[3]))
        assertEquals(adts().size + 8, word(pes, 4))
        assertEquals(90_000L, pts(pes))
        assertContentEquals(adts(), pes.copyOfRange(14, pes.size))
        assertFailsWith<IllegalArgumentException> { TsMuxer({}).writeAudio(adts(), 0) }
        assertFailsWith<IllegalArgumentException> { muxer.writeAudio(byteArrayOf(1), 0) }
        assertFailsWith<IllegalArgumentException> { muxer.writeAudio(adts().copyOf(8), 0) }
        val count = packets.size
        assertFailsWith<IllegalArgumentException> { muxer.writeVideo(hex("00 00 00 08 65"), config, 0, true) }
        assertFailsWith<IllegalArgumentException> { muxer.writeVideo(frame(), config, -1, true) }
        assertEquals(count, packets.size)
        assertFailsWith<IllegalArgumentException> { TsMuxer({}, pmtPid = 0) }
        assertFailsWith<IllegalArgumentException> { TsMuxer({}, videoPid = 0x100) }
        assertFailsWith<IllegalArgumentException> { TsMuxer({}, psiIntervalFrames = 0) }
    }

    private fun frame(size: Int = 2, type: Int = 1): ByteArray {
        val nal = ByteArray(size) { 0x55 }.also { it[0] = (0x60 or type).toByte() }
        return Nal.annexBToAvcc(hex("00 00 00 01") + nal)
    }

    private fun adts(): ByteArray = hex("ff f1 50 80 01 3f fc 11 22")

    private fun section(packet: ByteArray): ByteArray {
        val payload = payload(packet)
        val offset = 1 + u8(payload[0])
        val size = 3 + (word(payload, offset + 1) and 0xfff)
        return payload.copyOfRange(offset, offset + size)
    }

    private fun independentCrc(bytes: ByteArray): Long {
        var value = 0xffffffffL
        for (byte in bytes) {
            for (bit in 7 downTo 0) {
                val feedback = ((value ushr 31) xor (u8(byte).toLong() ushr bit)) and 1
                value = (value shl 1) and 0xffffffffL
                if (feedback != 0L) value = value xor 0x04c11db7L
            }
        }
        return value
    }
}

internal fun u8(byte: Byte): Int = byte.toInt() and 0xff
internal fun word(bytes: ByteArray, offset: Int): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])
internal fun pid(packet: ByteArray): Int = word(packet, 1) and 0x1fff
internal fun control(packet: ByteArray): Int = (u8(packet[3]) ushr 4) and 3
internal fun start(packet: ByteArray): Boolean = u8(packet[1]) and 0x40 != 0
internal fun payloadOffset(packet: ByteArray): Int = if (control(packet) and 2 == 0) 4 else 5 + u8(packet[4])
internal fun payload(packet: ByteArray): ByteArray = packet.copyOfRange(payloadOffset(packet), 188)
internal fun hasPcr(packet: ByteArray): Boolean = control(packet) and 2 != 0 && u8(packet[4]) >= 7 && u8(packet[5]) and 0x10 != 0

internal fun pcr(packet: ByteArray): Pair<Long, Int> {
    val base = (u8(packet[6]).toLong() shl 25) or (u8(packet[7]).toLong() shl 17) or
        (u8(packet[8]).toLong() shl 9) or (u8(packet[9]).toLong() shl 1) or (u8(packet[10]).toLong() ushr 7)
    val extension = ((u8(packet[10]) and 1) shl 8) or u8(packet[11])
    return (base * 300 + extension) to extension
}

internal fun pts(pes: ByteArray): Long = (((u8(pes[9]) ushr 1) and 7).toLong() shl 30) or
    (u8(pes[10]).toLong() shl 22) or ((u8(pes[11]) ushr 1).toLong() shl 15) or
    (u8(pes[12]).toLong() shl 7) or (u8(pes[13]) ushr 1).toLong()

internal fun pesPackets(packets: List<ByteArray>, pid: Int): List<ByteArray> {
    val result = mutableListOf<java.io.ByteArrayOutputStream>()
    for (packet in packets.filter { pid(it) == pid && control(it) and 1 != 0 }) {
        if (start(packet)) result.add(java.io.ByteArrayOutputStream())
        result.last().write(payload(packet))
    }
    return result.map { it.toByteArray() }

    @Test fun aTimestampJumpOfHoursIsADiscontinuityNotMillionsOfPcrPackets() {
        val packets = mutableListOf<ByteArray>()
        val muxer = TsMuxer({ packets += it.copyOf() }, audioEnabled = true)
        val adts = Adts.buildHeader(AudioSpecificConfig(2, 48000, 2), 4) + byteArrayOf(1, 2, 3, 4)
        muxer.writeAudio(adts, 0)
        val before = packets.size
        muxer.writeAudio(adts, 6L * 3600 * 1_000_000) // six hours later
        assertTrue(packets.size - before < 10, "packets written: ${packets.size - before}")
        // Ordinary gaps are still filled with PCR packets.
        val mid = packets.size
        muxer.writeAudio(adts, 6L * 3600 * 1_000_000 + 400_000)
        assertTrue(packets.size - mid >= 9)
    }
}
