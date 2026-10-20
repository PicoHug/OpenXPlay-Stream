// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18
package org.openxplay.stream.web

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.WireFormatException
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class DisplaySettingsTest {
    @TempDir lateinit var root: Path
    private class Port : DisplaySettingsPort {
        @Volatile var value = DisplaySettings()
        val changes = CopyOnWriteArrayList<DisplaySettings>()
        val listeners = CopyOnWriteArrayList<(DisplaySettings) -> Unit>()
        override fun current() = value
        override fun set(settings: DisplaySettings, origin: Origin) {
            value = settings; changes.add(settings); listeners.forEach { it(settings) }
        }
        override fun listen(callback: (DisplaySettings) -> Unit): AutoCloseable {
            listeners.add(callback); return AutoCloseable { listeners.remove(callback) }
        }
    }
    private fun output(port: DisplaySettingsPort? = null) = WebOutput(WebOutputConfig(
        port = 0, staticAssetRoot = root,
        sourceUrl = "https://example.org/source", displaySettings = port))

    @Test fun negotiatesPortReportsViewportAndBroadcastsBothDirectionsWithOneListener() {
        val port = Port()
        val output = output(port)
        val caps = CopyOnWriteArrayList<OutputCapabilities>()
        output.open(object : OutputHost {
            override fun capabilities(value: OutputCapabilities) { caps.add(value) }
            override fun control(event: ControlEvent) = Unit
            override fun stats(stats: OutputStats) = Unit
        })
        try {
            TestClient(Socket("127.0.0.1", output.awaitListening())).use { primary ->
                primary.send(1, vector("hello-viewport").toByteArray())
                val welcome = primary.json()
                assertEquals(listOf("display-settings"), welcome["features"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(WebJsonCodec.parse(vector("display-settings-relay")), primary.json())
                eventually { caps.lastOrNull()?.displays?.get(ScreenId.MAIN)?.viewport == Viewport(1533, 906) }
                assertEquals(false, caps.last().displays.getValue(ScreenId.MAIN).fullscreen)
                assertEquals(1, port.listeners.size)
                primary.send(1, vector("hello-viewport").toByteArray())
                TestClient(Socket("127.0.0.1", output.awaitListening())).use { secondary ->
                    secondary.hello(); secondary.json(); secondary.json()
                    secondary.send(1, vector("display-settings-client").toByteArray())
                    secondary.send(1, vector("ping").toByteArray()); assertEquals("pong", secondary.json()["type"]!!.jsonPrimitive.content)
                    output.awaitHostIdle(); assertTrue(port.changes.isEmpty())
                    primary.send(1, vector("display-settings-client").toByteArray())
                    val expected = DisplaySettings("720p", 125).message()
                    assertEquals(expected, primary.json()); assertEquals(expected, secondary.json())
                    assertEquals(listOf(DisplaySettings("720p", 125)), port.changes.toList())
                    primary.send(1, """{"type":"display-settings","resolution":"screen","screenWidth":300}""".toByteArray())
                    primary.send(1, vector("ping").toByteArray()); assertEquals("pong", primary.json()["type"]!!.jsonPrimitive.content)
                    output.awaitHostIdle(); assertEquals(1, port.changes.size)
                    port.set(DisplaySettings("native", 300), Origin("phone", "settings"))
                    assertEquals(port.current().message(), primary.json())
                    assertEquals(port.current().message(), secondary.json())
                    assertEquals(1, port.listeners.size)
                }
            }
        } finally { output.close() }
        assertTrue(port.listeners.isEmpty())
    }

    @Test fun absentPortPreservesLegacyWelcomeAndIgnoresUnsolicitedSettings() {
        val output = output(); output.open(NoopHost)
        try {
            TestClient(Socket("127.0.0.1", output.awaitListening())).use { client ->
                client.hello(); assertNull(client.json()["features"])
                client.send(1, vector("display-settings-client").toByteArray())
                client.send(1, vector("ping").toByteArray())
                assertEquals("pong", client.json()["type"]!!.jsonPrimitive.content)
            }
        } finally { output.close() }
    }

    @Test fun validatesNewFieldsStrictlyAndAcceptsBothSettingsDirections() {
        for (name in listOf("display-settings-client-codec", "display-settings-relay", "display-settings-native")) {
            for (direction in WebJsonCodec.Direction.entries) WebJsonCodec.parse(vector(name), direction)
        }
        assertEquals(WebJsonCodec.parse(vector("display-settings-native")), DisplaySettings("native").message())
        val hello = WebJsonCodec.parse(vector("hello-viewport"))
        val display = hello["displays"]!!.jsonArray[0].jsonObject
        for ((field, bad) in listOf("viewport" to JsonNull, "fullscreen" to JsonPrimitive("false"),
            "viewport" to buildJsonObject { put("width", 0); put("height", 1) },
            "viewport" to buildJsonObject { put("width", 1.5); put("height", 2) })) {
            assertFailsWith<WireFormatException> {
                WebJsonCodec.validate(JsonObject(hello + ("displays" to JsonArray(listOf(JsonObject(display + (field to bad)))))))
            }
        }
        for (field in listOf("resolution", "screenWidth", "videoCodec")) {
            val message = WebJsonCodec.parse(vector("display-settings-client"))
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(message + (field to JsonPrimitive("invalid")))) }
        }
        val welcome = WebJsonCodec.parse(vector("welcome-display-settings"))
        assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(welcome + ("features" to JsonPrimitive("display-settings")))) }
    }
    @Test fun legacyClientKeepsTheCurrentCodecAndNewClientChangesItWithoutAnotherHello() {
        val port = Port().apply { value = DisplaySettings(videoCodec = "h265") }
        val output = output(port); output.open(NoopHost)
        try {
            TestClient(Socket("127.0.0.1", output.awaitListening())).use { client ->
                client.hello(); client.json(); client.json()
                client.send(1, vector("display-settings-client").toByteArray())
                assertEquals(DisplaySettings("720p", 125, "h265").message(), client.json())
                assertEquals("h265", port.current().videoCodec)
            }
        } finally { output.close() }
        val nextPort = Port()
        val next = this.output(nextPort); next.open(NoopHost)
        try {
            TestClient(Socket("127.0.0.1", next.awaitListening())).use { client ->
                client.hello(); client.json(); client.json()
                client.send(1, vector("display-settings-client-codec").toByteArray())
                assertEquals(DisplaySettings("720p", 125, "h265").message(), client.json())
                assertEquals("h265", nextPort.current().videoCodec)
            }
        } finally { next.close() }
    }
    @Test fun codecIsRequiredOnlyFromTheRelayAndStrictWhenPresent() {
        val old = WebJsonCodec.parse(vector("display-settings-client"), WebJsonCodec.Direction.CLIENT_TO_RELAY)
        assertFailsWith<WireFormatException> { WebJsonCodec.validate(old, WebJsonCodec.Direction.RELAY_TO_CLIENT) }
        for (codec in listOf("auto", "h265", "h264")) for (direction in WebJsonCodec.Direction.entries) {
            val message = DisplaySettings(videoCodec = codec).message()
            assertEquals(message, WebJsonCodec.validate(message, direction))
        }
        for (bad in listOf(JsonNull, JsonPrimitive("H265"), JsonPrimitive(1), JsonPrimitive(true)))
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(old + ("videoCodec" to bad))) }
        assertFailsWith<IllegalArgumentException> { DisplaySettings(videoCodec = "invalid") }
    }
    @Test fun numericScreenWidthPresetsRoundTripAndRejectInvalidValues() {
        assertEquals(200, DisplaySettings().screenWidth)
        for (width in listOf(125, 200, 250, 300)) for (direction in WebJsonCodec.Direction.entries) {
            val message = DisplaySettings("auto", width).message()
            assertEquals(message, WebJsonCodec.parse(WebJsonCodec.encode(message, direction), direction))
        }
        for (width in listOf(100, 150, 400)) assertFailsWith<IllegalArgumentException> { DisplaySettings(screenWidth = width) }
        val message = DisplaySettings().message()
        for (direction in WebJsonCodec.Direction.entries) {
            assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(message - "screenWidth"), direction) }
            for (bad in listOf(JsonPrimitive("200"), JsonPrimitive(150), JsonPrimitive(200.5), JsonPrimitive(true), JsonNull))
                assertFailsWith<WireFormatException> { WebJsonCodec.validate(JsonObject(message + ("screenWidth" to bad)), direction) }
        }
    }

}
