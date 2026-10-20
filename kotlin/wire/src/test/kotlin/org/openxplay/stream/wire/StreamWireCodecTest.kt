// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.wire

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*
import kotlin.test.*

class StreamWireCodecTest {
    @Test fun everyFramedVectorRoundTrips() {
        listOf("hello", "capabilities", "capabilities-preferences", "capabilities-h264", "capabilities-auto", "capabilities-fps-30", "capabilities-fps-35", "capabilities-fps-40", "capabilities-fps-45", "capabilities-fps-50", "capabilities-fps-55", "capabilities-fps-60", "restart", "client", "video", "touch", "audio-format", "mic", "audio", "microphone").forEach { name ->
            val bytes = resource("wire/$name.hex").hexBytes()
            val input = ByteArrayInputStream(bytes)
            val message = assertNotNull(StreamWireCodec.read(input))
            assertNull(StreamWireCodec.read(input))
            val output = ByteArrayOutputStream()
            StreamWireCodec.write(output, message)
            assertContentEquals(bytes, output.toByteArray(), name)
        }
    }
    @Test fun everyJsonVectorRoundTrips() {
        listOf("capabilities-viewport", "display-settings-auto", "display-settings-native", "display-settings-1080p", "display-settings-720p", "display-settings-screen", "view-area", "view-area-clear", "hello", "capabilities", "capabilities-preferences", "capabilities-h264", "capabilities-auto", "capabilities-fps-30", "capabilities-fps-35", "capabilities-fps-40", "capabilities-fps-45", "capabilities-fps-50", "capabilities-fps-55", "capabilities-fps-60", "restart", "client", "input-capabilities", "audio-format", "mic").forEach { name ->
            val text = resource("wire/$name.json").trim()
            val message = StreamWireCodec.decode(text.toByteArray())
            assertEquals(Json.parseToJsonElement(text),
                Json.parseToJsonElement(StreamWireCodec.encode(message).toString(Charsets.UTF_8)))
        }
    }
    @Test fun settingsAreasAndViewportAreStrict() {
        val settings = Json.parseToJsonElement(resource("wire/display-settings-auto.json")).jsonObject
        for ((key, bad) in listOf("resolution" to JsonPrimitive("bad"), "screenWidth" to JsonPrimitive(150),
            "videoCodec" to JsonPrimitive("H265"), "videoCodec" to JsonNull)) {
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(JsonObject(settings + (key to bad)).toString().toByteArray()) }
        }
        for (key in listOf("resolution", "screenWidth", "videoCodec")) {
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(JsonObject(settings - key).toString().toByteArray()) }
        }
        val view = Json.parseToJsonElement(resource("wire/view-area.json")).jsonObject
        val area = view.getValue("area").jsonObject
        for ((key, bad) in listOf("x" to JsonPrimitive(-1), "width" to JsonPrimitive(0),
            "height" to JsonPrimitive(1041), "canvasWidth" to JsonNull, "x" to JsonPrimitive(Int.MAX_VALUE))) {
            val invalid = JsonObject(view + ("area" to JsonObject(area + (key to bad))))
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(invalid.toString().toByteArray()) }
        }
        val caps = Json.parseToJsonElement(resource("wire/capabilities-viewport.json")).jsonObject
        val display = caps.getValue("displays").jsonObject.getValue("0").jsonObject
        for ((key, bad) in listOf("viewport" to JsonNull, "fullscreen" to JsonPrimitive(1), "fullscreenPreferred" to JsonNull,
            "viewport" to buildJsonObject { put("widthPx", 0); put("heightPx", 100) })) {
            val invalid = JsonObject(caps + ("displays" to buildJsonObject { put("0", JsonObject(display + (key to bad))) }))
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(invalid.toString().toByteArray()) }
        }
    }

    @Test fun existingWebJsonFieldsRoundTrip() {
        listOf("restart", "session", "video-format", "audio-format", "mic", "keyframe", "stats", "error").forEach { name ->
            val text = resource("web/$name.json")
            val parsed = StreamWireCodec.decode(text.toByteArray())
            assertEquals(normalized(Json.parseToJsonElement(text)),
                normalized(Json.parseToJsonElement(StreamWireCodec.encode(parsed).toString(Charsets.UTF_8))))
        }
    }
    @Test fun preferencesMapToCoreAndDefaultsStayAbsent() {
        val caps = (StreamWireCodec.decode(resource("wire/capabilities-preferences.json").toByteArray()) as WireMessage.Capabilities).value
        val display = caps.displays.getValue(ScreenId.MAIN)
        assertEquals(FrameRatePreference.FPS_60, display.frameRate)
        assertEquals(59.5, display.sustainedFps)
        val defaults = WireMessage.Capabilities(caps.copy(displays = mapOf(ScreenId.MAIN to display.copy(
            frameRate = FrameRatePreference.AUTO, sustainedFps = null))))
        val encoded = Json.parseToJsonElement(StreamWireCodec.encode(defaults).toString(Charsets.UTF_8)).jsonObject
        val d = encoded["displays"]!!.jsonObject["0"]!!.jsonObject
        assertFalse("frameRate" in d); assertFalse("sustainedFps" in d)
        for ((field, bad) in listOf("frameRate" to JsonPrimitive("FPS_120"), "frameRate" to JsonNull,
            "frameRate" to JsonPrimitive(60), "sustainedFps" to JsonPrimitive(0),
            "sustainedFps" to JsonPrimitive(-1), "sustainedFps" to JsonNull, "sustainedFps" to JsonPrimitive("60"),
            "unknown" to JsonPrimitive(true))) {
            val invalid = JsonObject(encoded + ("displays" to buildJsonObject { put("0", JsonObject(d + (field to bad))) }))
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(invalid.toString().toByteArray()) }
        }
        assertSame(WireMessage.Restart, StreamWireCodec.decode(resource("wire/restart.json").toByteArray()))
        assertFailsWith<WireFormatException> { StreamWireCodec.decode("{\"type\":\"restart\",\"screen\":0}".toByteArray()) }
    }

    @Test fun allMessageModelsRoundTrip() {
        val format = testFormat()
        val messages = listOf(
            WireMessage.Session(SessionState(SessionId(Long.MAX_VALUE), SessionPhase.ACTIVE, "test")),
            WireMessage.VideoFormatMessage(ScreenId.MAIN, format),
            WireMessage.VideoFormatMessage(ScreenId.MAIN, null),
            WireMessage.AudioFormatMessage(0, AudioFormat(0, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2, byteArrayOf(0x11, 0x90.toByte()))),
            WireMessage.AudioFormatMessage(255, AudioFormat(255, AudioRole.VOICE, AudioCodec.PCM_S16LE, 16000, 1)),
            WireMessage.AudioFormatMessage(255, null),
            WireMessage.Microphone(1, MicRequest(1, AudioCodec.PCM_S16LE, 16000, 1, 20)),
            WireMessage.Microphone(1, null),
            WireMessage.Keyframe(ScreenId.CLUSTER),
            WireMessage.Error("test", "failed"),
            WireMessage.Stats(OutputStats(Origin("ignored", "ignored"), ScreenId.MAIN, 2000, 2, 3, 4, 5000)),
            WireMessage.Binary(BinaryMessage(BinaryHeader(BinaryKind.KEY, 0, 0, UInt.MAX_VALUE, -1, -2),
                OxpWebCodec.encodeKey(KeyPayload(KeyCode.VOICE, true)))),
        )
        messages.forEach {
            val encoded = StreamWireCodec.encode(it)
            assertContentEquals(encoded, StreamWireCodec.encode(StreamWireCodec.decode(encoded)))
        }
    }
    @Test fun audioJsonValidation() {
        listOf("audio-format" to "format", "mic" to "request").forEach { (name, field) ->
            val good = Json.parseToJsonElement(resource("wire/$name.json")).jsonObject
            fun rejected(value: JsonObject) {
                assertFailsWith<WireFormatException> { StreamWireCodec.decode(value.toString().toByteArray()) }
            }
            rejected(JsonObject(good + ("stream" to JsonPrimitive(256))))
            rejected(JsonObject(good - field))
            rejected(JsonObject(good + ("extra" to JsonPrimitive(true))))
            listOf("sampleRate", "channels", "codec").forEach { key ->
                val values = if (key == "codec") listOf(JsonPrimitive("FLAC"), JsonPrimitive(1)) else
                    listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("48000"), JsonNull, JsonPrimitive(2147483648L))
                values.forEach { value ->
                    rejected(JsonObject(good + (field to JsonObject(good.getValue(field).jsonObject + (key to value)))))
                }
            }
            rejected(JsonObject(good + (field to JsonObject(good.getValue(field).jsonObject + ("extra" to JsonPrimitive(true))))))
        }
        listOf(
            """{"type":"audio-format","stream":0,"format":{"role":"MEDIA","codec":"AAC_LC","sampleRate":48000,"channels":2,"config":"EZB="}}""",
            """{"type":"audio-format","stream":0,"format":{"role":"BAD","codec":"AAC_LC","sampleRate":48000,"channels":2}}""",
            """{"type":"mic","stream":0,"request":{"codec":"PCM_S16LE","sampleRate":16000,"channels":1,"frameMs":0}}""",
            """{"type":"mic","stream":0,"request":{"codec":"PCM_S16LE","sampleRate":16000,"channels":1,"frameMs":1.5}}""",
        ).forEach { assertFailsWith<WireFormatException> { StreamWireCodec.decode(it.toByteArray()) } }
        assertFailsWith<WireFormatException> { StreamWireCodec.encode(WireMessage.AudioFormatMessage(1,
            AudioFormat(0, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2))) }
        assertFailsWith<WireFormatException> { StreamWireCodec.encode(WireMessage.Microphone(1,
            MicRequest(0, AudioCodec.PCM_S16LE, 16000, 1, 20))) }
    }
    @Test fun framingLimitsAndPartialReads() {
        assertNull(StreamWireCodec.read(ByteArrayInputStream(byteArrayOf())))
        listOf(byteArrayOf(1), byteArrayOf(1, 0, 0), byteArrayOf(2, 0, 0, 0, 1),
            byteArrayOf(0, 0, 0, 0), byteArrayOf(-1, -1, -1, -1), byteArrayOf(1, 0, 0, 1)).forEach {
            assertFailsWith<WireFormatException> { StreamWireCodec.read(ByteArrayInputStream(it)) }
        }
        val payload = ByteArray(StreamWireCodec.MAX_PAYLOAD - OxpWebCodec.HEADER_SIZE)
        val max = WireMessage.Binary(BinaryMessage(BinaryHeader(BinaryKind.VIDEO, 0, 1, 0u, 0, 0), payload))
        val output = ByteArrayOutputStream()
        StreamWireCodec.write(output, max)
        assertEquals(StreamWireCodec.MAX_PAYLOAD + 4, output.size())
        assertNotNull(StreamWireCodec.read(ByteArrayInputStream(output.toByteArray())))
        val tooBig = WireMessage.Binary(BinaryMessage(max.value.header, ByteArray(payload.size + 1)))
        val untouched = ByteArrayOutputStream()
        assertFailsWith<WireFormatException> { StreamWireCodec.write(untouched, tooBig) }
        assertEquals(0, untouched.size())
        val bytes = resource("wire/hello.hex").hexBytes()
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertIs<WireMessage.Hello>(StreamWireCodec.read(fragmented))
    }
    @Test fun malformedJsonAndBinaryAreRejected() {
        listOf("{}", "{", "{\"type\":\"unknown\"}",
            "{\"type\":\"hello\",\"version\":1,\"role\":\"producer\",\"name\":\"p\",\"client\":\"c\"}",
            "{\"type\":\"client\",\"client\":3}",
            "{\"type\":\"keyframe\",\"screen\":256}",
            "{\"type\":\"keyframe\",\"screen\":0,\"extra\":true}").forEach {
            assertFailsWith<WireFormatException> { StreamWireCodec.decode(it.toByteArray()) }
        }
        assertFailsWith<WireFormatException> { StreamWireCodec.decode(byteArrayOf(123, -1, 125)) }
        assertFailsWith<WireFormatException> { StreamWireCodec.decode(byteArrayOf(1)) }
        val touch = resource("wire/touch.hex").hexBytes().drop(4).toByteArray()
        touch[3] = 1
        assertFailsWith<WireFormatException> { StreamWireCodec.decode(touch) }
    }
}

internal fun resource(name: String): String = checkNotNull(StreamWireCodecTest::class.java.getResource("/vectors/$name")).readText()
internal fun String.hexBytes(): ByteArray = trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
internal fun testFormat(): VideoFormat = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264,
    resource("avc-baseline-640x480.hex").hexBytes(), 60.0)

private fun normalized(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { normalized(it.value) })
    is JsonArray -> JsonArray(value.map(::normalized))
    is JsonPrimitive -> if (!value.isString && value.doubleOrNull != null) JsonPrimitive(value.double) else value
}
