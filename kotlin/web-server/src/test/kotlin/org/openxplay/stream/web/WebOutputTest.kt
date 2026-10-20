// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.web

import java.io.Closeable
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*
import kotlin.test.*

internal class TestClient(val socket: Socket, path: String = "/oxp/v0") : Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    init {
        socket.soTimeout = 5000
        output.write(("GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
        output.flush()
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val b = input.read(); if (b < 0) throw EOFException(); headers.append(b.toChar())
        }
        assertTrue(headers.startsWith("HTTP/1.1 101"))
        assertTrue(headers.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
    }
    fun send(opcode: Int, bytes: ByteArray) { output.write(masked(opcode, bytes)); output.flush() }
    fun hello(token: String = "example-pairing-token") {
        val hello = WebJsonCodec.parse(vector("hello"))
        send(1, JsonObject(hello + ("token" to JsonPrimitive(token))).toString().toByteArray())
    }
    fun read(beforePayload: () -> Unit = {}): WebSocketMessage {
        fun byte(): Int = input.read().also { if (it < 0) throw EOFException() }
        val first = byte(); val second = byte()
        assertEquals(128, first and 128); assertEquals(0, second and 128)
        var size = (second and 127).toLong()
        when (size) {
            126L -> size = (byte().toLong() shl 8) or byte().toLong()
            127L -> { size = 0; repeat(8) { size = (size shl 8) or byte().toLong() } }
        }
        require(size in 0..(10 * 1024 * 1024))
        beforePayload()
        val data = input.readNBytes(size.toInt()); assertEquals(size.toInt(), data.size)
        return WebSocketMessage(first and 15, data)
    }
    fun json(): JsonObject = read().let { assertEquals(1, it.opcode); WebJsonCodec.parse(utf8(it.payload)) }
    override fun close() { socket.close() }
}

internal fun fixtureFormat(): VideoFormat {
    val f = WebJsonCodec.parse(vector("video-format"))["format"]!!.jsonObject
    return VideoFormat.create(ScreenId.MAIN, VideoCodec.H264, Base64.getDecoder().decode(f["config"]!!.jsonPrimitive.content), 30.0)
}

internal fun eventually(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) { if (System.nanoTime() >= deadline) fail("Condition timed out"); Thread.sleep(5) }
}

/**
 * Sleeps 1 ms at a time and records how late it wakes: time in which a busy scheduler or a garbage collection pause
 * held runnable threads back, so a timing assertion can tell that from time a thread spent blocked.
 */
private class SchedulerLag : Thread("scheduler-lag") {
    /** Each late wake as (System.nanoTime when it woke, nanoseconds late). */
    private val wakes = java.util.concurrent.ConcurrentLinkedQueue<LongArray>()
    @Volatile private var running = true

    init { isDaemon = true }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            Thread.sleep(1)
            val now = System.nanoTime()
            val late = now - last - TimeUnit.MILLISECONDS.toNanos(1)
            if (late > 0) wakes.add(longArrayOf(now, late))
            last = now
        }
    }

    /** How long the probe was held back within [from, to] (System.nanoTime). */
    fun lateNanos(from: Long, to: Long): Long =
        wakes.sumOf { (woke, late) -> (minOf(woke, to) - maxOf(woke - late, from)).coerceAtLeast(0) }

    fun finish() { running = false; join() }
}

class WebOutputTest {
    @TempDir lateinit var root: Path
    private fun output(ssl: SSLContext? = null, maxClients: Int = 8, sendBufferBytes: Int = 32 * 1024): WebOutput = WebOutput(WebOutputConfig(
        port = 0, staticAssetRoot = root, sslContext = ssl,
        sourceUrl = "https://example.org/source", maxClients = maxClients,
        sendBufferBytes = sendBufferBytes))

    @Test fun hevcFormatAndFramesOnlyReachCapableClients() {
        val web = output()
        val capabilities = CopyOnWriteArrayList<OutputCapabilities>()
        web.open(object : OutputHost {
            override fun capabilities(value: OutputCapabilities) { capabilities.add(value) }
            override fun stats(stats: OutputStats) = Unit
            override fun control(event: ControlEvent) = Unit
        })
        val bytes = checkNotNull(javaClass.getResource("/vectors/hevc-main-1920x1080.hex")).readText()
            .trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
        val format = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, bytes)
        web.onSession(SessionState(SessionId(1), SessionPhase.ACTIVE), SessionConfig(emptyMap()))
        web.onVideoFormat(ScreenId.MAIN, format)
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { capable ->
                val hello = WebJsonCodec.parse(vector("hello"))
                val hevc = JsonObject(hello + ("decoders" to buildJsonArray {
                    add(buildJsonObject { put("codecString", "hvc1.1.6.L120.90"); put("hardware", true) })
                }))
                capable.send(1, hevc.toString().toByteArray())
                capable.json()
                assertEquals(WebJsonCodec.video(ScreenId.MAIN, format), capable.json())
                eventually { capabilities.lastOrNull()?.decoders?.any { it.hardware && VideoCodecPolicy.isHevc(it.codecString) } == true }
                TestClient(Socket("127.0.0.1", port)).use { avc ->
                    avc.hello(); avc.json()
                    assertEquals(WebJsonCodec.video(ScreenId.MAIN, null), avc.json())
                    eventually { capabilities.lastOrNull()?.decoders?.none { it.hardware && VideoCodecPolicy.isHevc(it.codecString) } == true }
                    val payload = byteArrayOf(0, 0, 0, 1, 0x26)
                    web.onVideoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), payload, 0, true, 0))
                    assertEquals(2, capable.read().opcode)
                    avc.send(1, "{\"type\":\"ping\",\"t0Us\":\"1\"}".toByteArray())
                    assertEquals("pong", avc.json()["type"]!!.jsonPrimitive.content)
                }
            }
        } finally { web.close() }
    }

    @Test fun everyRateReachesTheHostAndRestartsArePrimaryOnlyAndRateLimited() {
        val web = output()
        val capabilities = CopyOnWriteArrayList<OutputCapabilities>()
        val restarts = CopyOnWriteArrayList<Origin>()
        web.open(object : OutputHost {
            override fun capabilities(value: OutputCapabilities) { capabilities.add(value) }
            override fun requestRestart(origin: Origin) { restarts.add(origin) }
            override fun stats(stats: OutputStats) = Unit
            override fun control(event: ControlEvent) = Unit
        })
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { primary ->
                primary.hello(); assertEquals("welcome", primary.json()["type"]!!.jsonPrimitive.content)
                for (rate in FrameRatePreference.entries) {
                    val hello = WebJsonCodec.parse(vector("hello"))
                    val display = hello["displays"]!!.jsonArray[0].jsonObject
                    val changedDisplay = JsonObject(display + ("frameRate" to JsonPrimitive(rate.name)))
                    val changed = JsonObject(hello + ("displays" to JsonArray(listOf(changedDisplay))))
                    primary.send(1, changed.toString().toByteArray())
                    eventually { capabilities.lastOrNull()?.displays?.get(ScreenId.MAIN)?.frameRate == rate }
                }
                TestClient(Socket("127.0.0.1", port)).use { secondary ->
                    secondary.hello(); secondary.json()
                    fun barrier(client: TestClient) {
                        client.send(1, "{\"type\":\"ping\",\"t0Us\":\"1\"}".toByteArray())
                        assertEquals("pong", client.json()["type"]!!.jsonPrimitive.content)
                    }
                    secondary.send(1, "{\"type\":\"restart\"}".toByteArray()); barrier(secondary)
                    assertTrue(restarts.isEmpty())
                    repeat(2) { primary.send(1, "{\"type\":\"restart\"}".toByteArray()) }
                    barrier(primary)
                    assertEquals(1, restarts.size)
                }
            }
        } finally { web.close() }
    }

    @Test fun helloAcceptsAndIgnoresAnyToken() {
        val web = output()
        web.open(object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities): Unit = Unit
            override fun stats(stats: OutputStats): Unit = Unit
            override fun control(event: ControlEvent): Unit = Unit
        })
        try {
            val port = web.awaitListening()
            for (token in listOf("", "wrong", "00123456", "legacy-token", "anything")) {
                TestClient(Socket("127.0.0.1", port)).use { client ->
                    client.hello(token)
                    assertEquals("welcome", client.json()["type"]!!.jsonPrimitive.content)
                    client.send(8, closePayload(1000))
                }
            }
            TestClient(Socket("127.0.0.1", port)).use { client ->
                client.hello()
                assertEquals("welcome", client.json()["type"]!!.jsonPrimitive.content)
            }
        } finally { web.close() }
    }

    @Test fun hubEndToEndAndClientCapabilities() {
        val input = FakeInput()
        val hub = ProjectionHub(input)
        val web = output()
        hub.start(); hub.addOutput(web)
        try {
            input.emit { it.session(SessionState(SessionId(1), SessionPhase.ACTIVE)); it.videoFormat(ScreenId.MAIN, fixtureFormat()) }
            TestClient(Socket("127.0.0.1", web.awaitListening())).use { client ->
                client.hello()
                val welcome = client.json()
                assertEquals("welcome", welcome["type"]!!.jsonPrimitive.content)
                assertEquals("https://example.org/source", welcome["sourceUrl"]!!.jsonPrimitive.content)
                assertEquals("1", welcome["session"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals("video-format", client.json()["type"]!!.jsonPrimitive.content)
                val payload = byteArrayOf(0, 0, 0, 2, 0x65, 0)
                input.emit { it.videoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), payload, 1234, true, 9_000_000)) }
                val media = client.read(); assertEquals(2, media.opcode)
                val decoded = OxpWebCodec.decode(media.payload)
                assertContentEquals(payload, decoded.payload)
                assertEquals(1234L, decoded.header.ptsUs); assertEquals(9000L, decoded.header.senderClockUs)
                assertEquals(3, decoded.header.flags)
                val touch = OxpWebCodec.encode(BinaryMessage(BinaryHeader(BinaryKind.TOUCH, 0, 0, 0u, 0, 0),
                    OxpWebCodec.encodeTouch(listOf(TouchContact(0, 0.5, 0.25, true)))))
                client.send(2, touch)
                eventually { input.controls.any { it is ControlEvent.Touch && it.contacts.isNotEmpty() } }
                val event = input.controls.filterIsInstance<ControlEvent.Touch>().first()
                assertEquals(Origin("web", welcome["clientId"]!!.jsonPrimitive.content), event.origin)
                assertEquals(0.5, event.contacts[0].x, 1.0 / 65535)
                client.send(1, vector("ping").toByteArray())
                assertEquals("pong", client.json()["type"]!!.jsonPrimitive.content)
                client.send(8, closePayload(1000))
                assertEquals(8, client.read().opcode)
            }
        } finally { hub.stop() }
    }

    @Test fun authenticationStatsUpdatesAndEmptyCapabilities() {
        val caps = CopyOnWriteArrayList<OutputCapabilities>()
        val observedStats = CopyOnWriteArrayList<OutputStats>()
        val diagnostics = CopyOnWriteArrayList<Pair<String, String>>()
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val web = output()
        web.open(object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities) { caps.add(capabilities) }
            override fun stats(stats: OutputStats) { observedStats.add(stats) }
            override fun receiverDiagnostics(origin: Origin, type: String, json: String) { diagnostics.add(type to json) }
            override fun control(event: ControlEvent) { controls.add(event) }
        })
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { client ->
                client.hello(); client.json()
                eventually { caps.lastOrNull()?.touch == 10 }
                assertEquals(160, caps.last().displays[ScreenId.MAIN]!!.densityDpi)
                assertTrue(caps.last().audio.isEmpty()); assertFalse(caps.last().microphone); assertTrue(caps.last().keys.isEmpty())
                for (preferred in listOf(true, false)) {
                    val report = WebJsonCodec.parse(vector("hello-fullscreen-preferred"))
                    val display = report["displays"]!!.jsonArray[0].jsonObject
                    client.send(1, JsonObject(report + ("displays" to JsonArray(listOf(JsonObject(display +
                        ("fullscreenPreferred" to JsonPrimitive(preferred))))))).toString().toByteArray())
                    eventually { caps.last().displays[ScreenId.MAIN]?.fullscreenPreferred == preferred }
                }
                val changed = WebJsonCodec.parse(vector("hello"))
                client.send(1, JsonObject(changed + ("maxTouchContacts" to JsonPrimitive(1))).toString().toByteArray())
                eventually { caps.last().touch == 1 }
                assertNull(caps.last().displays[ScreenId.MAIN]?.fullscreenPreferred)
                client.send(1, vector("stats").toByteArray())
                eventually { observedStats.isNotEmpty() }
                assertEquals(3200L, observedStats.last().decodeTimeUs)
                assertEquals(16700L, observedStats.last().endToEndLatencyUs)
                assertFalse(diagnostics.any { it.first == "receiver-info" })
                client.send(1, vector("receiver-info").toByteArray())
                client.send(1, vector("stats-diagnostics").toByteArray())
                eventually { diagnostics.size == 3 }
                assertEquals(WebJsonCodec.parse(vector("receiver-info")), WebJsonCodec.parse(diagnostics[1].second))
                val forwarded = WebJsonCodec.parse(diagnostics[2].second)
                assertEquals(WebJsonCodec.parse(vector("stats-diagnostics")), JsonObject(forwarded - "relayDroppedFrames"))
                assertEquals(0L, forwarded["relayDroppedFrames"]!!.jsonPrimitive.long)
                client.send(1, vector("keyframe").toByteArray())
                eventually { controls.any { it is ControlEvent.KeyFrameRequest } }
                client.send(8, closePayload(1000)); assertEquals(8, client.read().opcode)
            }
            eventually { caps.last().displays.isEmpty() && caps.last().touch == 0 }
        } finally { web.close() }
    }

    @Test fun keyframeGatingOverflowRecoveryAndSessionFencing() {
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val web = output()
        web.open(object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities): Unit = Unit
            override fun stats(stats: OutputStats): Unit = Unit
            override fun control(event: ControlEvent) { controls.add(event) }
        })
        val session = SessionState(SessionId(2), SessionPhase.ACTIVE)
        web.onSession(session, SessionConfig(emptyMap()))
        web.onVideoFormat(ScreenId.MAIN, fixtureFormat())
        try {
            TestClient(Socket("127.0.0.1", web.awaitListening())).use { client ->
                client.hello(); client.json(); client.json()
                fun frame(pts: Long, key: Boolean, size: Int = 6, sessionId: Long = 2): VideoFrame =
                    VideoFrame(ScreenId.MAIN, SessionId(sessionId), ByteArray(size), pts, key, 0)
                web.onVideoFrame(frame(0, true, sessionId = 1))
                web.onVideoFrame(frame(1, false))
                eventually { controls.any { it is ControlEvent.KeyFrameRequest } }
                web.onVideoFrame(frame(2, true))
                assertEquals(2L, OxpWebCodec.decode(client.read().payload).header.ptsUs)
                controls.clear()
                // No socket reads during the burst: the writer cannot hold the producer lock while blocked.
                web.onVideoFrame(frame(3, true, 8 * 1024 * 1024))
                repeat(20) { web.onVideoFrame(frame(10_000L + it * 33_333L, false, 1024 * 1024)) }
                eventually { controls.any { it is ControlEvent.KeyFrameRequest } }
                web.onVideoFrame(frame(1_000_000, true))
                var recovered = false
                repeat(32) {
                    if (!recovered) {
                        val binary = OxpWebCodec.decode(client.read().payload)
                        if (binary.header.ptsUs == 1_000_000L) {
                            assertEquals(3, binary.header.flags); recovered = true
                        }
                    }
                }
                assertTrue(recovered)
                client.send(2, OxpWebCodec.encode(BinaryMessage(BinaryHeader(BinaryKind.KEY, 0, 0, 0u, 0, 0),
                    OxpWebCodec.encodeKey(KeyPayload(KeyCode.HOME, true)))))
                eventually { controls.any { it is ControlEvent.Key } }
            }
        } finally { web.close() }
    }

    @Test fun playbackSetsEachClientsQueueSpanAndARepeatedHelloUpdatesIt() {
        assertEquals(50_000L, playbackQueueDurationUs("SPEED", 100_000))
        assertEquals(100_000L, playbackQueueDurationUs("SMART", 100_000))
        assertEquals(100_000L, playbackQueueDurationUs(null, 100_000))
        assertEquals(300_000L, playbackQueueDurationUs("QUALITY", 100_000))
        val web = output(); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            fun hello(playback: String?): ByteArray {
                val hello = WebJsonCodec.parse(vector("hello"))
                return (if (playback == null) hello else JsonObject(hello + ("playback" to JsonPrimitive(playback)))).toString().toByteArray()
            }
            TestClient(Socket("127.0.0.1", port)).use { speed ->
                TestClient(Socket("127.0.0.1", port)).use { smart ->
                    TestClient(Socket("127.0.0.1", port)).use { quality ->
                        val ids = listOf(speed to "SPEED", smart to null, quality to "QUALITY").map { (client, mode) ->
                            client.send(1, hello(mode)); client.json()["clientId"]!!.jsonPrimitive.content
                        }
                        assertEquals(listOf(50_000L, 100_000L, 300_000L), ids.map { web.queueDurationUs(it) })
                        smart.send(1, hello("QUALITY")); smart.send(1, vector("ping").toByteArray())
                        assertEquals("pong", smart.json()["type"]!!.jsonPrimitive.content)
                        assertEquals(300_000L, web.queueDurationUs(ids[1]))
                    }
                }
            }
        } finally { web.close() }
    }

    @Test fun slowClientDropsItsOwnFramesWithoutBlockingTheProducerOrOtherClients() {
        val requests = CopyOnWriteArrayList<String>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val caps = CopyOnWriteArrayList<OutputCapabilities>()
        // Eight times real time with a 6 MB key frame: the reading client must take 6 MB within its 300 ms Quality span
        // of timestamps, about 36 ms. The system's send buffers keep loopback throughput, not scheduling on a busy
        // machine, from deciding that; the bounded send buffer is covered by WriteDeadlineTest.
        val web = output(sendBufferBytes = 0)
        web.open(object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities) { caps.add(capabilities) }
            override fun stats(stats: OutputStats) = Unit
            override fun receiverDiagnostics(origin: Origin, type: String, json: String) { if (type == "stats") diagnostics.add(json) }
            override fun control(event: ControlEvent) { if (event is ControlEvent.KeyFrameRequest) requests.add(event.origin.clientId) }
        })
        web.onSession(SessionState(SessionId(1), SessionPhase.ACTIVE), SessionConfig(emptyMap()))
        web.onVideoFormat(ScreenId.MAIN, fixtureFormat())
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { fast ->
                // A tiny receive window that is never read stands in for a stalled head-unit browser.
                val socket = Socket().apply { receiveBufferSize = 4096; connect(java.net.InetSocketAddress("127.0.0.1", port)) }
                TestClient(socket).use { slow ->
                    val hello = WebJsonCodec.parse(vector("hello"))
                    fast.send(1, JsonObject(hello + ("playback" to JsonPrimitive("QUALITY")) + ("maxTouchContacts" to JsonPrimitive(1))).toString().toByteArray())
                    val fastId = fast.json()["clientId"]!!.jsonPrimitive.content
                    assertEquals("video-format", fast.json()["type"]!!.jsonPrimitive.content)
                    eventually { caps.lastOrNull()?.touch == 1 }
                    slow.send(1, JsonObject(hello + ("playback" to JsonPrimitive("SPEED"))).toString().toByteArray())
                    eventually { caps.lastOrNull()?.touch == 10 }
                    val received = CopyOnWriteArrayList<Pair<Long, Int>>()
                    val frames = 120
                    val reader = Thread {
                        while (received.size < frames) {
                            val message = fast.read()
                            if (message.opcode == 2) OxpWebCodec.decode(message.payload).header.let { received.add(it.ptsUs to it.flags) }
                        }
                    }.apply { isDaemon = true; start() }
                    // The first key frame is far larger than the socket buffers, so the slow writer blocks inside it. A
                    // blocked producer would wait for that writer, which never finishes; a call that takes longer only
                    // because the scheduler or the garbage collector held every thread back is not blocked.
                    val lag = SchedulerLag().apply { start() }
                    var slowest = 0L
                    var slowestLag = 0L
                    for (index in 0 until frames) {
                        val size = if (index == 0) 6 * 1024 * 1024 else 1200
                        val frame = VideoFrame(ScreenId.MAIN, SessionId(1), ByteArray(size), index * 16_667L, index % 30 == 0, 0)
                        val started = System.nanoTime()
                        web.onVideoFrame(frame)
                        val ended = System.nanoTime()
                        val held = lag.lateNanos(started, ended)
                        if (ended - started - held > slowest - slowestLag) { slowest = ended - started; slowestLag = held }
                        Thread.sleep(2)
                    }
                    lag.finish()
                    assertTrue(slowest - slowestLag < TimeUnit.MILLISECONDS.toNanos(50),
                        "Producer blocked for ${slowest / 1000} µs, of which the scheduler held threads back ${slowestLag / 1000} µs")
                    reader.join(5000)
                    // The other client receives every frame in order, without a gap after the first.
                    assertEquals((0 until frames).map { it * 16_667L }, received.map { it.first })
                    assertTrue(received.drop(1).none { it.second and OxpWebCodec.GAP != 0 })
                    // One request per gap: the stalled client recovers at each key frame and overflows again.
                    eventually { requests.size == frames / 30 }
                    assertTrue(requests.none { it == fastId })
                    slow.send(1, vector("stats").toByteArray())
                    eventually { diagnostics.isNotEmpty() }
                    val dropped = WebJsonCodec.parse(diagnostics.last())["relayDroppedFrames"]!!.jsonPrimitive.long
                    assertTrue(dropped >= frames / 2, "$dropped frames dropped")
                    fast.send(1, vector("stats").toByteArray())
                    eventually { diagnostics.size == 2 }
                    assertEquals(0L, WebJsonCodec.parse(diagnostics.last())["relayDroppedFrames"]!!.jsonPrimitive.long)
                }
            }
        } finally { web.close() }
    }

    @Test fun pauseResumeIsPerClientAndKeyframeFirst() {
        val web = output(); web.open(NoopHost)
        web.onSession(SessionState(SessionId(1), SessionPhase.ACTIVE), SessionConfig(emptyMap()))
        web.onVideoFormat(ScreenId.MAIN, fixtureFormat())
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { hidden ->
                TestClient(Socket("127.0.0.1", port)).use { visible ->
                    for (client in listOf(hidden, visible)) { client.hello(); client.json(); client.json() }
                    fun frame(pts: Long, key: Boolean) = web.onVideoFrame(
                        VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(1), pts, key, 0))
                    fun pause(paused: Boolean) {
                        hidden.send(1, """{"type":"pause","screen":0,"paused":$paused}""".toByteArray())
                        hidden.send(1, vector("ping").toByteArray())
                    }
                    fun noVideo() {
                        hidden.socket.soTimeout = 150
                        try { assertFailsWith<SocketTimeoutException> { hidden.read() } }
                        finally { hidden.socket.soTimeout = 5000 }
                    }
                    frame(1, true)
                    assertEquals(1L, OxpWebCodec.decode(hidden.read().payload).header.ptsUs)
                    assertEquals(1L, OxpWebCodec.decode(visible.read().payload).header.ptsUs)
                    pause(true)
                    assertEquals("pong", hidden.json()["type"]!!.jsonPrimitive.content)
                    frame(2, true); frame(3, false)
                    assertEquals(2L, OxpWebCodec.decode(visible.read().payload).header.ptsUs)
                    assertEquals(3L, OxpWebCodec.decode(visible.read().payload).header.ptsUs)
                    noVideo()
                    val old = fixtureFormat()
                    val changed = VideoFormat.create(ScreenId.MAIN, old.codec, old.config, 24.0)
                    web.onVideoFormat(ScreenId.MAIN, changed)
                    assertEquals(WebJsonCodec.video(ScreenId.MAIN, changed), hidden.json())
                    assertEquals(WebJsonCodec.video(ScreenId.MAIN, changed), visible.json())
                    pause(false)
                    assertEquals(WebJsonCodec.video(ScreenId.MAIN, changed), hidden.json())
                    assertEquals("pong", hidden.json()["type"]!!.jsonPrimitive.content)
                    frame(4, false)
                    noVideo()
                    frame(5, true)
                    val resumed = OxpWebCodec.decode(hidden.read().payload)
                    assertEquals(5L, resumed.header.ptsUs)
                    assertEquals(OxpWebCodec.KEYFRAME or OxpWebCodec.GAP, resumed.header.flags)
                    assertEquals(5L, OxpWebCodec.decode(visible.read().payload).header.ptsUs)
                    frame(6, false)
                    assertEquals(6L, OxpWebCodec.decode(hidden.read().payload).header.ptsUs)
                    assertEquals(6L, OxpWebCodec.decode(visible.read().payload).header.ptsUs)
                    // A repeated resumed state must not reset a working client's decoding history.
                    pause(false); assertEquals("pong", hidden.json()["type"]!!.jsonPrimitive.content)
                    frame(7, false)
                    assertEquals(7L, OxpWebCodec.decode(hidden.read().payload).header.ptsUs)
                }
            }
        } finally { web.close() }
    }

    @Test fun pauseDropsBacklogAndResumeAfterStreamEnd() {
        val observedStats = CopyOnWriteArrayList<OutputStats>()
        val web = output()
        web.open(object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities): Unit = Unit
            override fun control(event: ControlEvent): Unit = Unit
            override fun stats(stats: OutputStats) { observedStats.add(stats) }
        })
        web.onSession(SessionState(SessionId(1), SessionPhase.ACTIVE), SessionConfig(emptyMap()))
        web.onVideoFormat(ScreenId.MAIN, fixtureFormat())
        try {
            TestClient(Socket("127.0.0.1", web.awaitListening())).use { client ->
                client.hello(); client.json(); client.json()
                web.onVideoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), ByteArray(8 * 1024 * 1024), 1, true, 0))
                // Hold the payload read while the writer sends this frame; later video queues behind it.
                val inFlight = client.read {
                    for (pts in 2L..3L) web.onVideoFrame(
                        VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(1), pts, false, 0))
                    client.send(1, vector("pause").toByteArray())
                    client.send(1, vector("stats").toByteArray())
                    eventually { observedStats.isNotEmpty() }
                    client.send(1, vector("ping").toByteArray())
                }
                assertEquals(1L, OxpWebCodec.decode(inFlight.payload).header.ptsUs)
                // Anything already in socket buffers may finish, but the pong precedes queued video.
                var pong = false
                repeat(4) {
                    if (!pong) {
                        val message = client.read()
                        if (message.opcode == 1) {
                            assertEquals("pong", WebJsonCodec.parse(utf8(message.payload))["type"]!!.jsonPrimitive.content)
                            pong = true
                        }
                    }
                }
                assertTrue(pong)
                client.socket.soTimeout = 150
                try { assertFailsWith<SocketTimeoutException> { client.read() } }
                finally { client.socket.soTimeout = 5000 }
                web.onVideoFormat(ScreenId.MAIN, null)
                assertEquals(WebJsonCodec.video(ScreenId.MAIN, null), client.json())
                client.send(1, """{"type":"pause","screen":0,"paused":false}""".toByteArray())
                assertEquals(WebJsonCodec.video(ScreenId.MAIN, null), client.json())
                // A restarted stream still needs a fresh keyframe.
                web.onVideoFormat(ScreenId.MAIN, fixtureFormat()); client.json()
                web.onVideoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(1), 4, false, 0))
                web.onVideoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(1), 5, true, 0))
                assertEquals(5L, OxpWebCodec.decode(client.read().payload).header.ptsUs)
            }
        } finally { web.close() }
    }

    @Test fun maximumConnectionsIsEnforced() {
        val web = output(maxClients = 1); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { first ->
                first.hello(); first.json()
                assertFailsWith<java.io.IOException> {
                    Socket("127.0.0.1", port).use { second -> TestClient(second) }
                }
            }
        } finally { web.close() }
    }

    @Test fun helloDeadlineAndPreHelloInputAreRejected() {
        val web = output(); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            TestClient(Socket("127.0.0.1", port)).use { client ->
                client.socket.soTimeout = 8000
                assertContentEquals(closePayload(1008), client.read().payload)
            }
            TestClient(Socket("127.0.0.1", port)).use { client ->
                client.send(1, vector("ping").toByteArray())
                assertContentEquals(closePayload(1008), client.read().payload)
            }
        } finally { web.close() }
    }

    private fun http(port: Int, target: String, method: String = "GET", extra: String = ""): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: localhost\r\n$extra\r\n".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun staticFilesTraversalAndHttpLimits() {
        Files.createDirectories(root.resolve("receiver")); Files.createDirectories(root.resolve("probe"))
        Files.writeString(root.resolve("receiver/index.html"), "receiver")
        Files.writeString(root.resolve("probe/index.html"), "probe")
        Files.writeString(root.resolve("app.js"), "script")
        val web = output(); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            val page = http(port, "/")
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.endsWith("receiver"))
            assertTrue(http(port, "/probe/").endsWith("probe"))
            assertTrue(http(port, "/app.js").contains("text/javascript"))
            for (path in listOf("/../secret", "/%2e%2e/secret", "/%2e%2e%2fsecret", "/%5c..%5csecret", "/missing")) assertTrue(http(port, path).startsWith("HTTP/1.1 404"))
            assertTrue(http(port, "/", "POST").startsWith("HTTP/1.1 405"))
            assertTrue(http(port, "/", extra = "X-Large: ${"a".repeat(9000)}\r\n").startsWith("HTTP/1.1 431"))
            val outside = Files.createTempFile(root.parent, "outside", ".html")
            try {
                Files.createSymbolicLink(root.resolve("escape.html"), outside)
                assertTrue(http(port, "/escape.html").startsWith("HTTP/1.1 404"))
            } finally { Files.deleteIfExists(outside) }
        } finally { web.close() }
    }

    /** Server and client contexts for a generated localhost certificate. */
    private fun tlsContexts(name: String = "server"): Pair<SSLContext, SSLContext> {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        assumeTrue(Files.isExecutable(keytool), "JDK keytool unavailable")
        val storePath = root.resolve("$name.p12")
        val log = root.resolve("$name-keytool.log")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", "test-password", "-keypass", "test-password",
            "-dname", "CN=localhost", "-validity", "1", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("keytool timed out") }
        assertEquals(0, process.exitValue(), Files.readString(log))
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(storePath).use { store.load(it, "test-password".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "test-password".toCharArray())
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        return serverContext to clientContext
    }

    @Test fun tlsUpdateKeepsListenerAndExistingWebSocket() {
        val (serverA, clientA) = tlsContexts("a")
        val (serverB, clientB) = tlsContexts("b")
        val web = output(serverA); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            val socketA = clientA.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            TestClient(socketA).use { first ->
                first.hello(); assertEquals("welcome", first.json()["type"]!!.jsonPrimitive.content)
                val certificateA = socketA.session.peerCertificates.first().encoded
                web.updateTls(serverB)
                assertEquals(port, web.awaitListening())
                val socketB = clientB.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
                TestClient(socketB).use { second ->
                    second.hello(); assertEquals("welcome", second.json()["type"]!!.jsonPrimitive.content)
                    assertFalse(certificateA.contentEquals(socketB.session.peerCertificates.first().encoded))
                }
                first.send(9, byteArrayOf(1, 2, 3))
                var reply = first.read()
                repeat(20) { if (reply.opcode != 10) reply = first.read() }
                assertEquals(10, reply.opcode)
                assertContentEquals(byteArrayOf(1, 2, 3), reply.payload)
            }
        } finally { web.close() }
    }

    @Test fun stalledTlsHandshakeDoesNotBlockOtherConnections() {
        val (server, client) = tlsContexts()
        val web = output(server); web.open(NoopHost)
        try {
            val port = web.awaitListening()
            Socket("127.0.0.1", port).use {
                val socket = client.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
                TestClient(socket).use { connected ->
                    connected.hello(); assertEquals("welcome", connected.json()["type"]!!.jsonPrimitive.content)
                }
            }
        } finally { web.close() }
    }

    @Test fun plainOutputRejectsTlsUpdates() {
        val web = output()
        assertFailsWith<IllegalStateException> { web.updateTls(SSLContext.getDefault()) }
    }

    @Test fun tlsHandshakeUsingGeneratedPkcs12() {
        val (serverContext, clientContext) = tlsContexts()
        val web = output(serverContext); web.open(NoopHost)
        try {
            val socket = clientContext.socketFactory.createSocket("127.0.0.1", web.awaitListening()) as SSLSocket
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            TestClient(socket).use { client -> client.hello(); assertEquals("welcome", client.json()["type"]!!.jsonPrimitive.content) }
        } finally { web.close() }
    }
}

internal object NoopHost : OutputHost {
    override fun capabilities(capabilities: OutputCapabilities): Unit = Unit
    override fun control(event: ControlEvent): Unit = Unit
    override fun stats(stats: OutputStats): Unit = Unit
}

class WebOutputContractTest : ProjectionOutputContract() {
    @TempDir lateinit var root: Path
    private lateinit var web: WebOutput
    override fun createOutput(): ProjectionOutput = WebOutput(WebOutputConfig(port = 0, staticAssetRoot = root,
        sourceUrl = "https://example.org/source")).also { web = it }
    override fun videoFormat(): VideoFormat = fixtureFormat()
    override fun stimulate(output: ProjectionOutput) {
        output.onVideoFrame(VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(0, 0, 0, 2, 0x65, 0), 0, true, 0))
    }
    override fun awaitIdle() { web.awaitListening(); web.awaitHostIdle() }
}
