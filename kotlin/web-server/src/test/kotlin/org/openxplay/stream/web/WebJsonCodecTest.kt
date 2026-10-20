// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.web

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.openxplay.stream.core.SessionId
import org.openxplay.stream.core.SessionPhase
import org.openxplay.stream.core.SessionState
import org.openxplay.stream.core.wire.WireFormatException
import kotlin.test.*

internal fun vector(name: String): String = checkNotNull(WebJsonCodecTest::class.java.getResource("/vectors/web/$name.json")).readText()

class WebJsonCodecTest {
    @Test fun everyVectorRoundTrips() {
        for (name in listOf("welcome-receiver-build", "display-settings-native", "hello-fullscreen-preferred", "view-area", "fullscreen-exit", "welcome-view-area", "hello-viewport", "welcome-relay", "welcome-display-settings", "display-settings-client", "display-settings-client-codec", "display-settings-relay", "hello", "hello-auto", "hello-fps-30", "hello-fps-35", "hello-fps-40", "hello-fps-45", "hello-fps-50", "hello-fps-55", "hello-fps-60", "hello-preferences", "hello-video-codec-h264", "hello-video-codec-auto", "restart", "welcome", "session", "video-format", "audio-format", "mic", "keyframe", "pause", "ping", "pong", "stats", "stats-diagnostics", "receiver-info", "error")) {
            val original = Json.parseToJsonElement(vector(name))
            assertEquals(original, Json.parseToJsonElement(WebJsonCodec.encode(WebJsonCodec.parse(vector(name)))))
            val obj = original.jsonObject
            val extended = JsonObject(obj + ("unknown" to JsonPrimitive(true)))
            if (obj["type"]?.jsonPrimitive?.content in setOf("stats", "receiver-info")) WebJsonCodec.validate(extended)
            else assertFailsWith<WireFormatException> { WebJsonCodec.validate(extended) }
        }
    }

    @Test fun fullscreenPreferenceIsOptionalAndStrictlyBoolean() {
        val hello = WebJsonCodec.parse(vector("hello-fullscreen-preferred"))
        val display = hello["displays"]!!.jsonArray[0].jsonObject
        for (value in listOf(JsonPrimitive(true), JsonPrimitive(false))) {
            WebJsonCodec.validate(JsonObject(hello + ("displays" to JsonArray(listOf(JsonObject(display + ("fullscreenPreferred" to value)))))))
        }
        WebJsonCodec.validate(JsonObject(hello + ("displays" to JsonArray(listOf(JsonObject(display - "fullscreenPreferred"))))))
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive("false"))) {
            assertFailsWith<WireFormatException> {
                WebJsonCodec.validate(JsonObject(hello + ("displays" to JsonArray(listOf(JsonObject(display + ("fullscreenPreferred" to value)))))))
            }
        }
    }

    @Test fun receiverInfoIsOptionalBoundedAndClientOnly() {
        val minimal = buildJsonObject { put("type", "receiver-info") }
        WebJsonCodec.validate(minimal, WebJsonCodec.Direction.CLIENT_TO_RELAY)
        assertFailsWith<WireFormatException> { WebJsonCodec.validate(minimal, WebJsonCodec.Direction.RELAY_TO_CLIENT) }
        assertFailsWith<WireFormatException> {
            WebJsonCodec.validate(buildJsonObject { put("type", "receiver-info"); put("userAgent", "字".repeat(22000)) })
        }
        WebJsonCodec.parse(vector("hello"), WebJsonCodec.Direction.CLIENT_TO_RELAY)
        WebJsonCodec.parse(vector("stats"), WebJsonCodec.Direction.CLIENT_TO_RELAY)
        val stats = WebJsonCodec.parse(vector("stats-diagnostics"))
        assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(stats + ("wsBytesPerSecond" to JsonPrimitive(-1)))) }
        assertEquals(1, stats["renderDepth"]!!.jsonPrimitive.int)
        WebJsonCodec.validate(JsonObject(stats - "renderDepth"))
        for (bad in listOf(JsonPrimitive(-1), JsonPrimitive("1"), JsonNull)) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(stats + ("renderDepth" to bad))) }
        }
    }

    @Test fun preferencesAndRestartAreStrict() {
        val hello = WebJsonCodec.parse(vector("hello"))
        val display = hello["displays"]!!.jsonArray[0].jsonObject
        for (mode in listOf("SMART", "QUALITY", "SPEED")) {
            WebJsonCodec.validate(JsonObject(hello + ("playback" to JsonPrimitive(mode))))
        }
        for (bad in listOf(JsonPrimitive("AUTO"), JsonNull, JsonPrimitive(1))) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(hello + ("playback" to bad))) }
        }
        for ((field, bad) in listOf("frameRate" to JsonPrimitive("FPS_120"), "frameRate" to JsonNull,
            "frameRate" to JsonPrimitive(30), "sustainedFps" to JsonPrimitive(0),
            "sustainedFps" to JsonPrimitive(-1), "sustainedFps" to JsonNull, "sustainedFps" to JsonPrimitive("60"))) {
            val changed = JsonObject(display + (field to bad))
            assertFailsWith<WireFormatException> {
                WebJsonCodec.validate(JsonObject(hello + ("displays" to JsonArray(listOf(changed)))))
            }
        }
        WebJsonCodec.parse(vector("restart"), WebJsonCodec.Direction.CLIENT_TO_RELAY)
        assertFailsWith<WireFormatException> { WebJsonCodec.parse(vector("restart"), WebJsonCodec.Direction.RELAY_TO_CLIENT) }
    }

    @Test fun pauseStateScreenAndDirection() {
        val pause = WebJsonCodec.parse(vector("pause"), WebJsonCodec.Direction.CLIENT_TO_RELAY)
        for (paused in listOf(true, false)) {
            val message = JsonObject(pause + ("paused" to JsonPrimitive(paused)))
            assertEquals(message, WebJsonCodec.parse(WebJsonCodec.encode(message), WebJsonCodec.Direction.CLIENT_TO_RELAY))
        }
        for (bad in listOf(JsonObject(pause - "paused"),
            JsonObject(pause + ("paused" to JsonPrimitive("true"))),
            JsonObject(pause + ("paused" to JsonPrimitive(1))),
            JsonObject(pause + ("paused" to JsonNull)),
            JsonObject(pause + ("screen" to JsonPrimitive(256))),
            JsonObject(pause + ("screen" to JsonPrimitive(-1))),
            JsonObject(pause + ("screen" to JsonPrimitive(0.5))))) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(bad) }
        }
        assertFailsWith<WireFormatException> { WebJsonCodec.parse(vector("pause"), WebJsonCodec.Direction.RELAY_TO_CLIENT) }
    }

    @Test fun optionalAudioStats() {
        val stats = WebJsonCodec.parse(vector("stats"))
        WebJsonCodec.validate(JsonObject(stats - "audioBufferedMs" - "audioUnderruns"))
        WebJsonCodec.validate(JsonObject(stats + ("audioBufferedMs" to JsonNull)))
        for (bad in listOf(JsonPrimitive(-1), JsonPrimitive(0.5), JsonNull, JsonPrimitive("1"))) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(stats + ("audioUnderruns" to bad))) }
        }
        for (bad in listOf(JsonPrimitive(-1), JsonPrimitive("1"))) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(stats + ("audioBufferedMs" to bad))) }
        }
    }

    @Test fun strictTypesNestedFieldsDirectionsAndInt64() {
        for (bad in listOf("{\"type\":\"ping\",\"t0Us\":1}", "{\"type\":\"ping\",\"t0Us\":\"01\"}",
            "{\"type\":\"ping\",\"t0Us\":\"9223372036854775808\"}", "{\"type\":\"keyframe\",\"screen\":\"0\"}")) {
            assertFailsWith<WireFormatException> { WebJsonCodec.parse(bad) }
        }
        assertFailsWith<WireFormatException> { WebJsonCodec.parse(vector("welcome"), WebJsonCodec.Direction.CLIENT_TO_RELAY) }
        val hello = WebJsonCodec.parse(vector("hello"))
        val decoder = hello["decoders"]!!.jsonArray[0].jsonObject
        assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(hello + ("decoders" to JsonArray(listOf(JsonObject(decoder + ("extra" to JsonPrimitive(1)))))))) }
        for (type in listOf("video-format", "audio-format", "mic")) {
            val original = WebJsonCodec.parse(vector(type))
            val field = if (type == "mic") "request" else "format"
            val nullable = JsonObject(original + (field to JsonNull))
            assertEquals(nullable, WebJsonCodec.parse(WebJsonCodec.encode(nullable)))
        }
        assertEquals("-9223372036854775808", WebJsonCodec.parse("{\"type\":\"ping\",\"t0Us\":\"-9223372036854775808\"}")["t0Us"]!!.jsonPrimitive.content)
    }

    @Test fun welcomeNamesTheRelayOnlyWhenTheShellGivesIt() {
        val state = SessionState(SessionId(42), SessionPhase.ACTIVE)
        val url = "https://github.com/OpenXPlay/OpenXPlay-Relay"
        assertEquals(Json.parseToJsonElement(vector("welcome-relay")),
            receiverWelcome("browser-1", state, url, relay = RelayInfo("Android", "0.1.3 (1003)")))
        assertNull(receiverWelcome("browser-1", state, url)["relay"])
        assertFailsWith<IllegalArgumentException> { RelayInfo("Android", " ") }
    }
}
