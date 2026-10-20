// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.openxplay.stream.clink.media.RtpPacketizer
import org.openxplay.stream.clink.protocol.*
import org.openxplay.stream.clink.uibc.UibcDecoder
import org.openxplay.stream.clink.wfd.*
import org.openxplay.stream.core.*

class SessionPartsTest {
    private val origin = Origin("clink", "hu")
    private fun response(request: RtspMessage, body: String = ""): RtspMessage =
        RtspMessage("RTSP/1.0 200 OK", mapOf("cseq" to request.headers.getValue("cseq")), body)
    private fun fixture(): String = javaClass.getResourceAsStream("/head-unit-wfd.txt")!!.bufferedReader().use { it.readText() }

    @Test fun `RTSP round trip handles fragmentation and pipelining`() {
        val request = RtspMessage("GET_PARAMETER * RTSP/1.0", mapOf("cseq" to "7"), fixture())
        val bytes = request.encode() + response(request).encode()
        val reader = RtspReader()
        val messages = bytes.flatMap { reader.feed(byteArrayOf(it)) }
        assertEquals(listOf(request, response(request)), messages)
        assertFailsWith<IllegalArgumentException> {
            RtspReader().feed("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\ncseq: 2\r\n\r\n".toByteArray())
        }
    }
    @Test fun `real capabilities select baseline and M4 contains encrypted input and source ports`() {
        val ports = ClinkPorts(rtsp = 17236, uibc = 14321, rtp = 15551)
        val source = WfdSource("127.0.0.1", ports) { DisplayInfo(1072, 2320, 320, 30.0) }
        val get = source.handle(response(source.start())).single()
        val m4 = source.handle(response(get, fixture())).single()
        assertEquals("SET_PARAMETER * RTSP/1.0", m4.line)
        assertEquals("avc1.42E01F", source.decoders.single().codecString)
        assertEquals(WfdMode(1280, 720, 30, 5, 1, 1), source.mode)
        assertContains(m4.body, "wfd_video_formats: 28 00 01 01 00000020")
        assertContains(m4.body, "port=14321;uibc_encrypted=true")
        assertContains(m4.body, "display=1280:720;dpi=320;fps=30")
        assertContains(m4.body, "rtsp://127.0.0.1:17236/wfd1.0/streamid=0 none")
        assertContains(m4.body, "wfd_audio_codecs: AAC 00000001 00")
        val trigger = source.handle(response(m4)).single()
        assertEquals("wfd_trigger_method: SETUP\r\n", trigger.body)
        source.handle(response(trigger))
        val setup = source.handle(RtspMessage("SETUP rtsp://127.0.0.1/wfd1.0/streamid=0 RTSP/1.0",
            mapOf("cseq" to "20", "transport" to "RTP/AVP/TCP;unicast;client_port=15550"))).single()
        assertEquals("RTSP/1.0 200 OK", setup.line)
        assertEquals(15550, source.rtpPort)
        assertContains(setup.headers.getValue("transport"), "server_port=15551-15552")
        val session = setup.headers.getValue("session")
        source.handle(RtspMessage("PLAY * RTSP/1.0", mapOf("cseq" to "21", "session" to session)))
        assertTrue(source.playing); assertTrue(source.takeKeyframe()); assertFalse(source.takeKeyframe())
        source.handle(RtspMessage("SET_PARAMETER * RTSP/1.0", mapOf("cseq" to "22"), "wfd_idr_request\r\n"))
        assertTrue(source.takeKeyframe())
        assertEquals("RTSP/1.0 454 Session Not Found", source.handle(RtspMessage("PLAY * RTSP/1.0",
            mapOf("cseq" to "23", "session" to "wrong"))).single().line)
    }
    @Test fun `HEVC modes require peer support and only SIGNAL adds one M4 line`() {
        fun negotiate(mode: ClinkHevcMode, flag: String?): Pair<WfdSource, String> {
            val source = WfdSource("127.0.0.1", ClinkPorts(), mode)
            val get = source.handle(response(source.start())).single()
            val body = fixture() + (flag?.let { "\r\niccoa_support_hevc: $it\r\n" } ?: "")
            return source to source.handle(response(get, body)).single().body
        }
        val baseline = negotiate(ClinkHevcMode.OFF, null).second
        for (mode in ClinkHevcMode.entries) for (flag in listOf(null, "supported", "unsupported")) {
            val (source, m4) = negotiate(mode, flag)
            val enabled = mode != ClinkHevcMode.OFF && flag == "supported"
            assertEquals(enabled, source.hevcEnabled)
            if (enabled) assertEquals(listOf(org.openxplay.stream.core.DecoderSupport("hvc1.1.6.L153.B0", true)), source.decoders)
            else assertTrue(source.decoders.isNotEmpty() && source.decoders.all { it.codecString.startsWith("avc1.") })
            // H.265 claims Main Level 4.1, which covers 1080p30, so the mode moves up; H.264 keeps 720p30.
            val expected = if (enabled) {
                assertEquals(WfdMode(1920, 1080, 60, 8, 1, 1), source.mode)
                baseline.replace("display=1280:720;dpi=160;fps=30", "display=1920:1080;dpi=160;fps=60")
                    .replace("28 00 01 01 00000020", "40 00 01 01 00000100")
            } else {
                assertEquals(WfdMode(1280, 720, 30, 5, 1, 1), source.mode)
                baseline
            }
            assertEquals((if (enabled && mode == ClinkHevcMode.SIGNAL) "iccoa_support_hevc: supported\r\n" else "") + expected, m4)
            assertContains(source.takeReports(), "video caps iccoa_support_hevc=${flag ?: "absent"}")
        }
    }
    @Test fun `default mode locks advertised HEVC without signalling M4`() {
        val source = WfdSource("127.0.0.1", ClinkPorts())
        val get = source.handle(response(source.start())).single()
        val m4 = source.handle(response(get, fixture() + "\r\niccoa_support_hevc: supported\r\n")).single()
        assertEquals(listOf("hvc1.1.6.L153.B0"), source.decoders.map { it.codecString })
        assertFalse(m4.body.contains("iccoa_support_hevc"))
    }
    @Test fun `HEVC placeholder matches AVC playback and has no AUD`() {
        val avc = BundledPlaceholderVideo()
        val clip = BundledHevcPlaceholderVideo()
        assertEquals(avc.frames.size, clip.frames.size); assertEquals(avc.fps, clip.fps)
        assertEquals(avc.format.width to avc.format.height, clip.format.width to clip.format.height)
        val config = org.openxplay.stream.core.codec.HevcConfig.parse(clip.format.config)
        assertEquals(1, config.profile); assertEquals(93, config.level)
        assertTrue(org.openxplay.stream.core.codec.Nal.isKeyFrame(clip.frames.first(), VideoCodec.H265))
        assertTrue(clip.frames.flatMap { org.openxplay.stream.core.codec.Nal.units(it).toList() }
            .none { (it[0].toInt() ushr 1) and 63 == 35 })
    }
    @Test fun `1080p placeholder bundles load and match the negotiated mode`() {
        for (size in listOf(1280 to 720, 1920 to 1080)) {
            val avc = BundledPlaceholderVideo(size.first, size.second)
            val clip = BundledHevcPlaceholderVideo(size.first, size.second)
            assertEquals(size, avc.format.width to avc.format.height)
            assertEquals(size, clip.format.width to clip.format.height)
            assertEquals(30, avc.frames.size); assertEquals(30, clip.frames.size)
            assertEquals(avc.fps, clip.fps)
            assertTrue(org.openxplay.stream.core.codec.Nal.isKeyFrame(avc.frames.first(), VideoCodec.H264))
            assertTrue(org.openxplay.stream.core.codec.Nal.isKeyFrame(clip.frames.first(), VideoCodec.H265))
            // 1080p needs Main Level 5.1; the 720p bundle keeps level 3.1.
            val config = org.openxplay.stream.core.codec.HevcConfig.parse(clip.format.config)
            assertEquals(1, config.profile)
            assertEquals(if (size.first >= 1920) 153 else 93, config.level)
        }
    }
    @Test fun `HEVC placeholder TS decodes all thirty frames`() {
        val ffprobe = java.nio.file.Path.of("/opt/homebrew/bin/ffprobe")
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(ffprobe))
        val clip = BundledHevcPlaceholderVideo()
        val config = org.openxplay.stream.core.codec.HevcConfig.parse(clip.format.config)
        val path = java.nio.file.Files.createTempFile("placeholder-hevc-", ".ts")
        try {
            java.nio.file.Files.newOutputStream(path).use { output ->
                val muxer = org.openxplay.stream.core.codec.TsMuxer({ output.write(it) }, videoCodec = VideoCodec.H265)
                clip.frames.forEachIndexed { index, data -> muxer.writeVideo(data, config, index * 1_000_000L / clip.fps, index == 0) }
            }
            val process = ProcessBuilder(ffprobe.toString(), "-v", "error", "-count_frames", "-show_entries",
                "stream=codec_name,width,height,nb_read_frames", "-of", "default=nw=1", path.toString()).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                val report = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), report)
                assertEquals(setOf("codec_name=hevc", "width=1280", "height=720", "nb_read_frames=30"),
                    report.lineSequence().filter { it.isNotBlank() }.toSet())
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { java.nio.file.Files.deleteIfExists(path) }
    }
    @Test fun `AAC negotiation requires bitmap bit zero`() {
        for ((codecs, expected) in listOf("AAC 00000001 00" to true, "LPCM 00000003 00, AAC 00000003 00" to true,
            "AAC 00000002 00" to false, "AAC invalid 00" to false, "none" to false)) {
            val source = WfdSource("127.0.0.1", ClinkPorts())
            val get = source.handle(response(source.start())).single()
            val body = fixture().replace(Regex("wfd_audio_codecs:[^\r\n]*"), "wfd_audio_codecs: $codecs")
            val m4 = source.handle(response(get, body)).single()
            assertEquals(expected, source.audio)
            assertContains(m4.body, "wfd_audio_codecs: ${if (expected) "AAC 00000001 00" else "none"}")
        }
    }
    @Test fun `RTP groups seven TS packets with wrapping sequence and RFC4571 prefix`() {
        val ts = List(15) { n -> ByteArray(188) { n.toByte() }.also { it[0] = 0x47 } }
        val packets = RtpPacketizer(sequence = 65535).packetize(ts, 1_000_000)
        assertEquals(listOf(1328, 1328, 200), packets.map { it.size })
        packets.forEachIndexed { i, packet ->
            val b = ByteBuffer.wrap(packet)
            assertEquals(0x80, b.get().toInt() and 255); assertEquals(33, b.get().toInt() and 255)
            assertEquals((65535 + i) and 65535, b.short.toInt() and 65535)
            assertEquals(90000, b.int); assertEquals(0x434c4144, b.int)
            val framed = RtpPacketizer.frame(packet)
            assertEquals(packet.size, ByteBuffer.wrap(framed).short.toInt() and 65535)
            assertContentEquals(packet, framed.copyOfRange(2, framed.size))
        }
        assertContentEquals(ts.take(7).reduce { a, b -> a + b }, packets[0].copyOfRange(12, packets[0].size))
    }
    @Test fun `touch uses source pixels and preserves two contacts through pointer index up`() {
        val decoder = UibcDecoder(origin) { 1280 to 720 }
        fun touch(json: String) = decoder.decode(json.toByteArray()) as ControlEvent.Touch
        val down = touch("""{"type":1,"action":0,"count":1,"width":1072,"height":2320,"trackID0":100,"x0":640,"y0":360}""")
        assertEquals(listOf(TouchContact(0, .5, .5, true)), down.contacts)
        val second = touch("""{"type":1,"action":261,"count":2,"trackID0":100,"x0":640,"y0":360,"trackID1":200,"x1":1280,"y1":720}""")
        assertEquals(listOf(0, 1), second.contacts.map { it.id }); assertTrue(second.contacts.all { it.pressed })
        val up = touch("""{"type":1,"action":262,"count":2,"trackID0":100,"x0":640,"y0":360,"trackID1":200,"x1":1280,"y1":720}""")
        assertEquals(listOf(true, false), up.contacts.map { it.pressed })
        assertTrue(touch("""{"type":1,"action":3,"count":0}""").contacts.isEmpty())
    }
    @Test fun `keys map Android codes and unknown keys are ignored`() {
        val codes = listOf(3, 4, 23, 66, 19, 20, 21, 22, 85, 87, 88, 231)
        val expected = listOf(KeyCode.HOME, KeyCode.BACK, KeyCode.SELECT, KeyCode.SELECT,
            KeyCode.KNOB_UP, KeyCode.KNOB_DOWN, KeyCode.KNOB_LEFT, KeyCode.KNOB_RIGHT,
            KeyCode.PLAY_PAUSE, KeyCode.NEXT, KeyCode.PREVIOUS, KeyCode.VOICE)
        val decoder = UibcDecoder(origin) { 1280 to 720 }
        codes.zip(expected).forEach { (code, key) ->
            assertEquals(ControlEvent.Key(key, true, origin), decoder.decode("""{"type":2,"keycode":$code,"action":0,"metaState":0}""".toByteArray()))
            assertEquals(ControlEvent.Key(key, false, origin), UibcDecoder.key(code, 1, origin))
        }
        assertNull(UibcDecoder.key(999, 0, origin)); assertNull(UibcDecoder.key(3, 2, origin))
        assertFailsWith<IllegalArgumentException> { decoder.decode("""{"type":2,"type":1,"keycode":3}""".toByteArray()) }
    }
    @Test fun `encrypted UIBC records tolerate fragmentation`() {
        val key = ByteArray(16) { it.toByte() }
        val record = SecureRecord(key).seal("""{"type":2,"keycode":4,"action":1}""".toByteArray())
        val reader = SecureRecord(key)
        val decoder = UibcDecoder(origin) { 1280 to 720 }
        val events = record.flatMap { reader.feed(byteArrayOf(it)) }.map { decoder.decode(it) }
        assertEquals(listOf(ControlEvent.Key(KeyCode.BACK, false, origin)), events)
    }
    @Test fun `CERT checks signed-byte envelope structure without checking RSA signature`() {
        val metadata = """{"short_name":"car","product_name":"model","vendor_name":"vendor","vendor_id":"v1","product_id":"p1"}""".toByteArray()
        val content = metadata.joinToString(",") { it.toString() }
        val signature = List(256) { "0" }.joinToString(",")
        val envelope = """{"content":[$content],"signature":[$signature],"version":1,"signAlgorithm":"SHA256withRSA","cryptoConfigIndex":"v1"}"""
        fun body(json: String) = Fields(mapOf(1 to FieldValue.Bytes(java.util.Base64.getEncoder().encode(json.toByteArray())))).encode()
        assertEquals(mapOf("short_name" to "car", "product_name" to "model", "vendor_name" to "vendor",
            "vendor_id" to "v1", "product_id" to "p1"), Certificate.check(body(envelope)))
        assertFailsWith<IllegalArgumentException> { Certificate.check(body(envelope.replace("\"version\":1", "\"version\":2"))) }
        assertFailsWith<IllegalArgumentException> { Certificate.check(body(envelope.replace("\"signature\":[0", "\"signature\":[255"))) }
    }
    @Test fun `CONTROL m25 includes display and projection mode`() {
        val display = DisplayInfo(1072, 2320, 320, 30.0)
        val body = Control.displayBody(display)
        val fields = Fields.decode(body)
        for ((id, value) in mapOf(2 to 1072, 3 to 2320, 4 to 320, 5 to 1072, 6 to 2320, 7 to 30, 10 to 0, 14 to 1))
            assertEquals(value.toULong(), fields.number(id))
        assertEquals(display, Control.display(body, DisplayInfo(1280, 720, 160, 30.0)))
        assertFailsWith<IllegalArgumentException> { Control.display(Fields(mapOf(10 to FieldValue.Varint(1uL))).encode(), display) }
        assertEquals(2uL, Fields.decode(Control.serverResponse(0)).number(1))
    }
    @Test fun `placeholder yields parsed AVCC clip and live switches only at keyframes`() {
        val placeholder = BundledPlaceholderVideo()
        assertEquals(30, placeholder.frames.size); assertEquals(30, placeholder.fps)
        assertEquals(1280, placeholder.format.width); assertEquals(720, placeholder.format.height)
        assertEquals(66, placeholder.avcConfig.profile); assertEquals(31, placeholder.avcConfig.level)
        val gate = VideoSwitch()
        assertFalse(gate.accept(true)); gate.play(true)
        assertTrue(gate.placeholder); assertFalse(gate.accept(false)); assertTrue(gate.placeholder)
        assertTrue(gate.accept(true)); assertFalse(gate.placeholder); assertTrue(gate.accept(false))
        gate.reset(); assertTrue(gate.placeholder); assertFalse(gate.accept(false))
        assertTrue(gate.accept(true)); gate.play(false); assertFalse(gate.placeholder)
    }
    @Test fun `overflow drops dependent frames and retains next keyframe without copying`() {
        fun frame(pts: Long, key: Boolean, size: Int = 4) = VideoFrame(ScreenId.MAIN, SessionId(1), ByteArray(size), pts, key, 0)
        val queue = VideoQueue(maxBytes = 12, maxDurationUs = 100)
        queue.offer(frame(0, false)); assertNull(queue.poll())
        queue.offer(frame(0, true)); queue.offer(frame(20, false)); queue.offer(frame(40, false))
        assertTrue(queue.offer(frame(60, false))); assertTrue(queue.takeGap()); assertNull(queue.poll())
        queue.offer(frame(80, false)); assertNull(queue.poll())
        val key = frame(100, true); queue.offer(key); assertSame(key, queue.poll())
        queue.offer(frame(110, true)); assertTrue(queue.offer(frame(250, true)))
        assertTrue(queue.poll()!!.keyFrame); assertNull(queue.poll())
        assertTrue(queue.offer(frame(300, true, 20))); assertNull(queue.poll())
        assertTrue(queue.droppedCount() >= 7)
    }
}

class CanvasTest {
    @org.junit.jupiter.api.Test fun canvasMatchesTheNegotiatedModeExactly() {
        // Real car 2026-10-16: control channel reported 1156x764, WFD mode 1280x720.
        val fitted = canvas(DisplayInfo(1156, 764, 160, 30.0, SafeArea(0, 40, 0, 0)), 1280, 720)
        kotlin.test.assertEquals(1280 to 720, fitted.widthPx to fitted.heightPx)
        kotlin.test.assertEquals(37, fitted.safeArea.top)
        val same = DisplayInfo(1280, 720, 160, 30.0)
        kotlin.test.assertSame(same, canvas(same, 1280, 720))
        kotlin.test.assertEquals(1280 to 720, canvas(DisplayInfo(1024, 600, 160, 30.0), 1280, 720).let { it.widthPx to it.heightPx })
    }
}
