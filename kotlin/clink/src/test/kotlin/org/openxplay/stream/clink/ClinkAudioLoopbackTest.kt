// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14.

package org.openxplay.stream.clink

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.openxplay.stream.core.*
import org.openxplay.stream.core.audio.Adts
import kotlin.test.*

@Timeout(15)
class ClinkAudioLoopbackTest {
    private class Loop : AutoCloseable {
        val ports = freePorts()
        val vehicle = SimulatedHeadUnit(ports)
        val errors = CopyOnWriteArrayList<Throwable>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val events = LinkedBlockingQueue<ControlEvent>()
        val caps = CopyOnWriteArrayList<OutputCapabilities>()
        var expectError = false
        val ended = CountDownLatch(1)
        val session = SessionId(7)
        val output = ClinkOutput(StaticClinkLink(ClinkAddresses("127.0.0.1", "127.0.0.1", ports.auth, "audio-loop")),
            PinProvider { "012345" }, MemoryTrustStore(), ports = ports, listener = object : ClinkListener {
                override fun error(error: Throwable) { errors += error }
                override fun diagnostic(message: String) { diagnostics += message }
                override fun state(state: ClinkState) { if (state == ClinkState.ENDED) ended.countDown() }
            })
        init {
            output.open(object : OutputHost {
                override fun capabilities(capabilities: OutputCapabilities) { caps += capabilities }
                override fun control(event: ControlEvent) { events.add(event) }
                override fun stats(stats: OutputStats) = Unit
            })
            output.onSession(SessionState(session, SessionPhase.ACTIVE),
                SessionConfig(mapOf(ScreenId.MAIN to ScreenConfig(VideoCodec.H264, 1280, 720, 30.0))))
            vehicle.await("audio peer ready") { vehicle.playing && vehicle.accessUnits.isNotEmpty() && vehicle.requestedMediaPort == ports.media }
            events.clear()
        }
        fun packet(stream: Int, data: ByteArray, pts: Long = 0) = output.onAudioPacket(AudioPacket(stream, session, data, pts))
        fun pcm(value: Int, samples: Int): ByteArray = ByteArray(samples * 2) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() }
        fun voice(stream: Int, rate: Int = 16000, channels: Int = 1) =
            output.onAudioFormat(stream, AudioFormat(stream, AudioRole.VOICE, AudioCodec.PCM_S16LE, rate, channels))
        fun mic(): ControlEvent.MicPacket {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val event = events.poll(20, TimeUnit.MILLISECONDS)
                if (event is ControlEvent.MicPacket) return event
            }
            error("No microphone packet")
        }
        override fun close() {
            vehicle.expectRelayClose(); output.close()
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            vehicle.close()
            if (!expectError) assertTrue(errors.isEmpty(), errors.joinToString())
        }
    }

    @Test fun `AAC access units share the video clock and RTP transport`() = Loop().use { loop ->        val clip = BaselineClip()
        val base = 3_000_000_000_000L
        loop.output.onVideoFormat(ScreenId.MAIN, clip.format)
        fun video(index: Int) = loop.output.onVideoFrame(VideoFrame(ScreenId.MAIN, loop.session,
            clip.frames[index], base + index * 33_333L, index == 0, System.nanoTime()))
        video(0); video(1)
        loop.vehicle.await("live video") { loop.vehicle.accessUnits.any { it.size == 640 to 360 } }
        loop.output.onAudioFormat(0, AudioFormat(0, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte())))
        val au = byteArrayOf(0x21, 0x10, 0x56, 0x78)
        loop.packet(0, au, base + 33_333)
        loop.vehicle.await("AAC PES") { loop.vehicle.audioUnits.size == 1 }
        video(2)
        loop.vehicle.await("second live video PES") { loop.vehicle.accessUnits.count { it.size == 640 to 360 } >= 2 }
        val audio = loop.vehicle.audioUnits.single()
        assertContentEquals(au, Adts.parse(audio.data).single().data)
        val videoPts = loop.vehicle.accessUnits.filter { it.size == 640 to 360 }[1].pts90kHz
        assertTrue(kotlin.math.abs(audio.pts90kHz - videoPts) <= 4500)
        assertEquals("AAC 00000001 00", loop.vehicle.selectedAudio)
        assertEquals(setOf(AudioCodec.AAC_LC, AudioCodec.PCM_S16LE), loop.caps.last().audio)
        assertTrue(loop.caps.last().microphone)
        // Every AAC stream shares the transport's single audio PID, so a second one is added to it
        // rather than replacing the first.
        loop.output.onAudioFormat(3, AudioFormat(3, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte())))
        loop.packet(0, byteArrayOf(1), base + 66_666)
        loop.packet(3, au, base + 66_666)
        loop.vehicle.await("replacement AAC stream") { loop.vehicle.audioUnits.size == 2 }
        loop.output.onAudioFormat(4, AudioFormat(4, AudioRole.MEDIA, AudioCodec.PCM_S16LE, 16000, 1))
        // Progress on a worker delivers the unsupported-format diagnostic.
        loop.vehicle.await("diagnostic", condition = { "unsupported_media_audio:4" in loop.diagnostics })
        assertContains(loop.diagnostics, "unsupported_media_audio:4")
    }

    @Test fun `a navigation prompt plays over the music on the same audio track`() = Loop().use { loop ->
        // The phone sends a navigation prompt as AAC on a second stream, with the same shape as
        // the music. Both reach the head unit on the transport's single audio PID, so the prompt
        // is heard over the music instead of taking the voice channel, which would duck it.
        val clip = BaselineClip()
        val base = 3_000_000_000_000L
        loop.output.onVideoFormat(ScreenId.MAIN, clip.format)
        loop.output.onVideoFrame(VideoFrame(ScreenId.MAIN, loop.session, clip.frames[0], base, true, System.nanoTime()))
        loop.output.onVideoFrame(VideoFrame(ScreenId.MAIN, loop.session, clip.frames[1], base + 33_333L, false, System.nanoTime()))
        loop.vehicle.await("live video") { loop.vehicle.accessUnits.any { it.size == 640 to 360 } }
        val music = AudioFormat(10, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte()))
        val prompt = AudioFormat(13, AudioRole.OTHER, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte()))
        loop.output.onAudioFormat(10, music)
        loop.output.onAudioFormat(13, prompt)
        val musicAu = byteArrayOf(0x21, 0x10, 0x56, 0x78)
        val promptAu = byteArrayOf(0x21, 0x10, 0x12, 0x34)
        loop.packet(10, musicAu, base + 33_333)
        loop.vehicle.await("music AAC PES") { loop.vehicle.audioUnits.size == 1 }
        loop.packet(13, promptAu, base + 50_000)
        loop.vehicle.await("prompt AAC PES") { loop.vehicle.audioUnits.size == 2 }
        // Both reached the head unit; neither replaced the other.
        val payloads = loop.vehicle.audioUnits.map { Adts.parse(it.data).single().data }
        assertTrue(payloads.any { it.contentEquals(musicAu) }, "music missing: ${payloads.size} units")
        assertTrue(payloads.any { it.contentEquals(promptAu) }, "prompt missing: ${payloads.size} units")
    }

    @Test fun `media audio works during the placeholder and a full audio queue cannot stall video`() = Loop().use { loop ->
        loop.output.onAudioFormat(0, AudioFormat(0, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte())))
        loop.packet(0, byteArrayOf(1, 2, 3), 8_000_000_000L)
        loop.vehicle.await("placeholder AAC") { loop.vehicle.audioUnits.size == 1 }
        val before = loop.vehicle.accessUnits.size
        repeat(1000) { index -> loop.packet(0, ByteArray(400), 8_000_000_000L + (index + 1) * 21_333L) }
        loop.vehicle.await("video while AAC queue is full") { loop.vehicle.accessUnits.size >= before + 3 }
        assertTrue(loop.vehicle.accessUnits.all { it.size == 1280 to 720 })
    }

    @Test fun `voice tail ACK and playback delay precede stop without stalling video`() = Loop().use { loop ->
        loop.vehicle.tailAckDelayMillis = 150
        loop.voice(1)
        loop.packet(1, loop.pcm(1234, 320 * 8))
        loop.output.onAudioFormat(1, null)
        loop.vehicle.await("voice SEND_SYNC tail") { loop.vehicle.voiceFrames.any { it.kind == 3 } }
        val videoBefore = loop.vehicle.accessUnits.size
        loop.vehicle.await("video progresses during voice ACK wait") { loop.vehicle.accessUnits.size >= videoBefore + 2 }
        loop.vehicle.await("voice stop") { loop.vehicle.audioCommands.any { it.method == 8 && it.fields.number(2) == 1uL } }
        val commands = loop.vehicle.audioCommands.filter { it.method == 8 }
        assertEquals(listOf(0uL, 1uL), commands.map { it.fields.number(2) })
        assertEquals(16000uL, commands.first().fields.number(3))
        assertTrue(commands.first().receivedNanos <= loop.vehicle.voiceFrames.first().receivedNanos)
        assertEquals(listOf(0, 0, 0, 0, 0, 3), loop.vehicle.voiceFrames.map { it.kind })
        val tail = loop.vehicle.voiceFrames.last()
        assertEquals(640, tail.data.size)
        assertEquals(1920, loop.vehicle.voiceFrames.first().data.size)
        assertTrue(commands.last().receivedNanos - loop.vehicle.tailAckNanos >= 20_000_000)
        assertContentEquals(loop.pcm(1234, 320 * 8), loop.vehicle.voiceFrames.fold(byteArrayOf()) { a, b -> a + b.data })
    }

    @Test fun `empty voice format can be replaced before the first packet`() = Loop().use { loop ->
        loop.voice(1, 8000)
        loop.voice(1)
        loop.packet(1, loop.pcm(2345, 320 * 4))
        loop.output.onAudioFormat(1, null)
        loop.vehicle.await("empty replacement voice stop") { loop.vehicle.audioCommands.any { it.method == 8 && it.fields.number(2) == 1uL } }
        assertContentEquals(loop.pcm(2345, 320 * 4),
            loop.vehicle.voiceFrames.fold(byteArrayOf()) { a, b -> a + b.data })
    }

    @Test fun `voice replacement drains old PCM before an immediate end marker`() = Loop().use { loop ->
        loop.voice(1)
        loop.packet(1, loop.pcm(1234, 320 * 4))
        loop.voice(1)
        loop.packet(1, loop.pcm(2345, 320 * 4))
        loop.output.onAudioFormat(1, null)
        loop.vehicle.await("replacement voice stop") { loop.vehicle.audioCommands.any { it.method == 8 && it.fields.number(2) == 1uL } }
        assertContentEquals(loop.pcm(1234, 320 * 4) + loop.pcm(2345, 320 * 4),
            loop.vehicle.voiceFrames.fold(byteArrayOf()) { a, b -> a + b.data })
        assertEquals(1920, loop.vehicle.voiceFrames.first().data.size)
        assertEquals(1, loop.vehicle.audioCommands.count { it.method == 8 && it.fields.number(2) == 0uL })
    }

    @Test fun `two voice streams mix with saturation and resampling`() = Loop().use { loop ->
        loop.voice(1); loop.voice(2, 48000, 2)
        loop.packet(1, loop.pcm(20000, 320 * 10))
        loop.packet(2, loop.pcm(20000, 960 * 10 * 2))
        loop.output.onAudioFormat(1, null); loop.output.onAudioFormat(2, null)
        loop.vehicle.await("mixed voice stop") { loop.vehicle.audioCommands.any { it.method == 8 && it.fields.number(2) == 1uL } }
        val data = loop.vehicle.voiceFrames.fold(byteArrayOf()) { a, b -> a + b.data }
        assertTrue(data.indices.step(2).any { (data[it].toInt() and 255) == 255 && data[it + 1] == 127.toByte() })
        assertEquals(1, loop.vehicle.audioCommands.count { it.method == 8 && it.fields.number(2) == 0uL })
        assertTrue(loop.vehicle.voiceFrames.filter { it.kind == 0 }.drop(1).all { it.data.size == 640 })
    }

    @Test fun `microphone stops on input session end and output close`() = Loop().use { loop ->
        loop.output.onMicrophone(2, MicRequest(2, AudioCodec.PCM_S16LE, 16000, 1, 20))
        loop.mic()
        val config = SessionConfig(mapOf(ScreenId.MAIN to ScreenConfig(VideoCodec.H264, 1280, 720, 30.0)))
        loop.output.onSession(SessionState(loop.session, SessionPhase.ENDED), config)
        loop.vehicle.await("session microphone off") { loop.vehicle.audioCommands.count { it.method == 6 && it.fields.number(1) == 0uL } == 1 }
        loop.vehicle.microphone(ByteArray(640))
        // Other controls (such as keyframe requests) may still arrive; only microphone packets must not.
        val deadline = System.nanoTime() + 100_000_000
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val event = loop.events.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            assertFalse(event is ControlEvent.MicPacket, "Microphone packet after session end: $event")
        }
        loop.output.onSession(SessionState(SessionId(8), SessionPhase.ACTIVE), config)
        loop.output.onMicrophone(2, MicRequest(2, AudioCodec.PCM_S16LE, 16000, 1, 20))
        loop.mic()
        loop.vehicle.expectRelayClose(); loop.output.close()
        assertTrue(loop.ended.await(3, TimeUnit.SECONDS))
        loop.vehicle.await("close microphone off") { loop.vehicle.audioCommands.count { it.method == 6 && it.fields.number(1) == 0uL } == 2 }
    }

    @Test fun `microphone rejects partial samples and packets longer than 100 ms`() {
        for (size in listOf(1, 3202)) Loop().use { loop ->
            loop.output.onMicrophone(2, MicRequest(2, AudioCodec.PCM_S16LE, 16000, 1, 20))
            loop.mic()
            loop.expectError = true; loop.vehicle.expectRelayClose()
            loop.vehicle.microphone(ByteArray(size))
            assertTrue(loop.ended.await(3, TimeUnit.SECONDS))
            assertTrue(loop.errors.any { it.message == "invalid_microphone_frame" })
            assertNull(loop.events.filterIsInstance<ControlEvent.MicPacket>().firstOrNull())
        }
    }

    @Test fun `RAW microphone frames split and join and m16 emits voice key edges`() = Loop().use { loop ->
        val request = MicRequest(2, AudioCodec.PCM_S16LE, 24000, 2, 20)
        loop.output.onMicrophone(2, request)
        val automatic = loop.mic()
        assertEquals(2, automatic.stream); assertEquals(1920, automatic.data.size)
        assertEquals(Origin("clink", "audio-loop"), automatic.origin)
        loop.vehicle.microphone(ByteArray(960) { 7 })
        loop.vehicle.microphone(ByteArray(4800) { 9 })
        val joined = loop.mic(); val second = loop.mic(); val third = loop.mic()
        assertContentEquals(ByteArray(960) { 7 } + ByteArray(960) { 9 }, joined.data)
        assertContentEquals(ByteArray(1920) { 9 }, second.data)
        assertContentEquals(ByteArray(1920) { 9 }, third.data)
        assertEquals(listOf(20000L, 20000L), listOf(second.ptsUs - joined.ptsUs, third.ptsUs - second.ptsUs))
        loop.output.onMicrophone(2, null)
        loop.vehicle.await("microphone off") { loop.vehicle.audioCommands.any { it.method == 6 && it.fields.number(1) == 0uL } }
        for (raw in listOf(false, true)) {
            loop.vehicle.awakenVoiceAssistant(raw)
            val down = loop.events.poll(3, TimeUnit.SECONDS)
            val up = loop.events.poll(3, TimeUnit.SECONDS)
            assertEquals(ControlEvent.Key(KeyCode.VOICE, true, automatic.origin), down)
            assertEquals(ControlEvent.Key(KeyCode.VOICE, false, automatic.origin), up)
        }
        assertTrue(loop.vehicle.playing)
    }
}
