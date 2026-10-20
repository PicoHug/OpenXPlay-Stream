// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.web

import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.serialization.json.*
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*
import kotlin.math.roundToInt

/**
 * Plain HTTP is intended for development; supply an SSL context for HTTPS/WSS. [queueDurationUs] is the per-client Smart video queue.
 *
 * [sendBufferBytes] bounds each client socket's kernel send buffer (SO_SNDBUF; zero keeps the system default), so a slow
 * browser's backlog blocks the writer and stays in the video queue, where it is dropped to the next key frame, instead of
 * waiting unseen in the kernel. Linux doubles the value for bookkeeping, which leaves room for about 20 Mbit/s at a 20 ms
 * round trip. A write that makes no progress for [writeTimeoutMillis] closes its client.
 */
public data class WebOutputConfig(
    public val bindAddress: String = "127.0.0.1",
    public val port: Int = 8443,
    public val sslContext: SSLContext? = null,
    public val staticAssetRoot: Path,
    public val sourceUrl: String,
    public val maxClients: Int = 8,
    public val plugins: PluginsConfig? = null,
    public val queueDurationUs: Long = 100_000,
    public val sendBufferBytes: Int = 32 * 1024,
    public val writeTimeoutMillis: Long = 5000,
    public val displaySettings: DisplaySettingsPort? = null,
    /** The shell serving this output, shown on the head-unit page next to the page's own version. */
    public val relay: RelayInfo? = null,
    public val viewArea: ViewAreaPort? = null,
) {
    init {
        require(port in 0..65535 && maxClients > 0 && queueDurationUs >= 0 && sendBufferBytes >= 0 && writeTimeoutMillis > 0)
        WebJsonCodec.validate(buildJsonObject {
            put("type", "welcome"); put("version", 0); put("clientId", "config")
            put("session", WebJsonCodec.session(SessionState(SessionId(0), SessionPhase.CONNECTING)))
            put("sourceUrl", sourceUrl); put("attribution", "Powered by OpenXPlay."); put("projectUrl", PROJECT_URL)
        })
    }
}

public data class RelayInfo(public val name: String, public val version: String) {
    init { require(name.isNotBlank() && version.isNotBlank() && name.length <= 64 && version.length <= 64) }
}

private data class SelectedVideo(val queue: FrameQueue, val frame: QueuedFrame)

/**
 * Server queue span for a client's `hello.playback`: Speed 50 ms, Smart 100 ms and Quality 300 ms at the
 * default configuration. Frames beyond it are dropped to the next key frame for that client only.
 */
internal fun playbackQueueDurationUs(playback: String?, smartUs: Long): Long = when (playback) {
    "SPEED" -> smartUs / 2
    "QUALITY" -> if (smartUs > Long.MAX_VALUE / 3) Long.MAX_VALUE else smartUs * 3
    else -> smartUs
}

internal fun readReceiverBuild(root: Path): String? = runCatching {
    Files.newBufferedReader(root.resolve("receiver-build.txt")).use { it.readText().trim().takeIf(String::isNotEmpty) }
}.getOrNull()

private const val PROJECT_URL = "https://github.com/OpenXPlay/OpenXPlay-Stream"

/** Host callbacks that may wait before reports are dropped; essential callbacks are never dropped. */
private const val HOST_QUEUE_CAPACITY = 256

/** Socket I/O runs on owned threads. Model callbacks only retain state and enqueue borrowed frames. */
public class WebOutput(private val config: WebOutputConfig, override val id: String = "web") : ProjectionOutput {
    @Volatile private var tlsContext = config.sslContext

    /** Existing connections retain their TLS session; new connections use this context. */
    public fun updateTls(sslContext: SSLContext) {
        check(config.sslContext != null) { "Output was configured without TLS" }
        tlsContext = sslContext
    }

    private val receiverBuild = readReceiverBuild(config.staticAssetRoot)
    private val plugins = config.plugins?.let(::Plugins)
    override fun wantsContext(): Boolean = plugins != null
    override fun onContext(update: ContextUpdate) { plugins?.update(update) }
    private val lock = Any()
    private val hostGate = Any()
    private var runtime: Runtime? = null
    private var state = SessionState(SessionId(0), SessionPhase.CONNECTING)
    private val formats = linkedMapOf<ScreenId, VideoFormat>()
    private val audioFormats = linkedMapOf<Int, AudioFormat>()
    private val microphones = linkedMapOf<Int, MicRequest>()
    @Volatile private var listening = CompletableFuture<Int>()

    /** Waits for asynchronous binding; port zero selects a loopback-test friendly ephemeral port. */
    public fun awaitListening(timeoutMillis: Long = 5000): Int = listening.get(timeoutMillis, TimeUnit.MILLISECONDS)

    internal fun queueDurationUs(clientId: String): Long? = synchronized(lock) { runtime?.clients?.get(clientId)?.queueDurationUs }

    internal fun awaitHostIdle() {
        val queue = synchronized(lock) { runtime?.hostQueue } ?: return
        queue.awaitIdle(5000)
    }

    /** Host callbacks waiting or running; bounded for reports by [HOST_QUEUE_CAPACITY]. */
    internal fun hostQueueSize(): Int = synchronized(lock) { runtime?.hostQueue?.size } ?: 0

    override fun open(host: OutputHost) {
        val run = synchronized(lock) {
            if (runtime != null) return
            listening = CompletableFuture()
            Runtime(host).also { runtime = it }
        }
        val listener = config.displaySettings?.listen { settings -> synchronized(lock) {
            if (runtime === run) run.clients.values.filter { it.hello != null && !it.closing }.forEach { it.json(settings.message()) }
        } }
        val retained = synchronized(lock) {
            if (runtime === run) { run.settingsListener = listener; true } else false
        }
        if (!retained) listener?.close()
        val areaListener = config.viewArea?.listen { area -> synchronized(lock) {
            if (runtime === run) run.clients.values.filter { it.hello != null && !it.closing }.forEach { it.json(area.message()) }
        } }
        val areaRetained = synchronized(lock) {
            if (runtime === run) { run.areaListener = areaListener; true } else false
        }
        if (!areaRetained) areaListener?.close()
        run.reportCapabilities()
        thread("accept") { run.accept() }
    }

    override fun onSession(state: SessionState, config: SessionConfig) {
        synchronized(lock) {
            val run = runtime ?: return
            if (state.id.value < this.state.id.value) return
            if (state.id != this.state.id || state.phase == SessionPhase.ENDED) {
                run.clients.values.forEach { it.audio.clear() }
                audioFormats.keys.toList().forEach { onAudioFormat(it, null) }
                microphones.keys.toList().forEach { onMicrophone(it, null) }
                formats.clear()
                run.clients.values.forEach { it.queues.values.forEach(FrameQueue::clear) }
            }
            this.state = state
            val message = buildJsonObject { put("type", "session"); put("session", WebJsonCodec.session(state)) }
            run.clients.values.filter { it.hello != null }.forEach { it.json(message) }
        }
    }

    override fun onVideoFormat(screen: ScreenId, format: VideoFormat?) {
        synchronized(lock) {
            val run = runtime ?: return
            if (format == null) formats.remove(screen) else formats[screen] = format
            run.clients.values.filter { it.hello != null }.forEach {
                it.queues.remove(screen)?.clear()
                it.videoFormat(screen, format)
            }
        }
    }

    override fun onVideoFrame(frame: VideoFrame) {
        synchronized(lock) {
            val run = runtime ?: return
            if (frame.session != state.id || state.phase == SessionPhase.ENDED || frame.screen !in formats) return
            // Each client has its own bounded queue, so a blocked writer drops its own frames and never this caller.
            run.clients.values.filter { it.hello != null && !it.closing && frame.screen !in it.paused && it.supportsVideo(formats.getValue(frame.screen)) }.forEach { client ->
                val queue = client.queues.getOrPut(frame.screen) {
                    val screen = frame.screen
                    FrameQueue(client.queueDurationUs, needKeyframe = {
                        run.host { it.control(ControlEvent.KeyFrameRequest(screen, client.origin)) }
                    })
                }
                val dropped = queue.dropped
                queue.offer(frame)
                client.droppedFrames += queue.dropped - dropped
                client.wake.release()
            }
        }
    }

    override fun onAudioFormat(stream: Int, format: AudioFormat?) {
        synchronized(lock) {
            val run = runtime ?: return
            val old = audioFormats.remove(stream)
            if (format != null) audioFormats[stream] = format
            run.clients.values.forEach { client ->
                if (format != null && client.supports(format.codec)) client.audioFormat(WebJsonCodec.audio(stream, format))
                else if (old != null && client.supports(old.codec)) client.audioFormat(WebJsonCodec.audio(stream, null))
            }
        }
    }

    override fun onAudioPacket(packet: AudioPacket) {
        synchronized(lock) {
            val run = runtime ?: return
            val format = audioFormats[packet.stream] ?: return
            if (packet.session != state.id || state.phase == SessionPhase.ENDED) return
            run.clients.values.filter { !it.closing && it.supports(format.codec) }.forEach { client ->
                client.audio.offer(packet, format)
                client.wake.release()
            }
        }
    }

    override fun onMicrophone(stream: Int, request: MicRequest?) {
        synchronized(lock) {
            val run = runtime ?: return
            if (request == null) microphones.remove(stream) else microphones[stream] = request
            run.clients.values.filter { it.hello?.get("microphone")?.jsonPrimitive?.boolean == true }
                .forEach { it.json(WebJsonCodec.microphone(stream, request)) }
        }
    }

    override fun close() {
        val run = synchronized(hostGate) {
            synchronized(lock) {
                val current = runtime ?: return
                current.active = false
                runtime = null; formats.clear(); audioFormats.clear(); microphones.clear(); state = SessionState(SessionId(0), SessionPhase.CONNECTING)
                current
            }
        }
        run.settingsListener?.close()
        run.areaListener?.close()
        run.executor.execute {
            try { run.server?.close() } catch (_: Exception) { }
            synchronized(lock) { run.clients.values.forEach { it.wake.release() }; run.sockets.toList() }.forEach { safelyClose(it) }
            run.bound.completeExceptionally(IllegalStateException("Output closed"))
            run.hostQueue.shutdown()
            run.executor.shutdown()
        }
    }

    private fun thread(role: String, action: () -> Unit): Thread = Thread(action, "oxp-$id-$role").apply { isDaemon = true; start() }
    private fun safelyClose(socket: Socket) { try { socket.close() } catch (_: Exception) { } }

    private inner class Runtime(val outputHost: OutputHost) {
        @Volatile var active = true
        @Volatile var server: ServerSocket? = null
        val bound = listening
        /** Socket timers: deferred closes, the write watchdog and shutdown. Host callbacks use [hostQueue]. */
        val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "oxp-$id-timer").apply { isDaemon = true }
        }
        val hostQueue = HostQueue("oxp-$id-host", HOST_QUEUE_CAPACITY)
        val clients = linkedMapOf<String, Client>()
        val sockets = mutableSetOf<Socket>()
        val pluginSockets = mutableSetOf<Socket>()
        val mediaConnections = java.util.concurrent.atomic.AtomicInteger()
        private var lastCapabilities: OutputCapabilities? = null

        init {
            val period = (config.writeTimeoutMillis / 4).coerceIn(10, 1000)
            executor.scheduleWithFixedDelay(::checkWriters, period, period, TimeUnit.MILLISECONDS)
        }

        /** Capabilities, control events and restarts: delivered in order and never dropped. */
        fun host(action: (OutputHost) -> Unit) {
            if (!active) return
            hostQueue.essential { synchronized(hostGate) { if (active) action(outputHost) } }
        }

        /** Stats and diagnostics: the newest per [key] while waiting, dropped while the host queue is full. */
        fun report(key: Any, action: (OutputHost) -> Unit) {
            if (!active) return
            hostQueue.report(key) { synchronized(hostGate) { if (active) action(outputHost) } }
        }

        /** Closes clients whose socket write has made no progress for the write timeout. */
        private fun checkWriters() {
            val now = System.nanoTime()
            val limit = TimeUnit.MILLISECONDS.toNanos(config.writeTimeoutMillis)
            synchronized(lock) { clients.values.toList() }.forEach { client ->
                val stalled = client.stalledNanos(now)
                // A failure must not cancel the periodic check for the other clients.
                if (stalled >= limit) try { client.writeTimedOut(stalled) } catch (_: Exception) { }
            }
        }

        var settingsListener: AutoCloseable? = null
        var areaListener: AutoCloseable? = null
        var lastRestartNanos: Long? = null

        fun reportCapabilities() {
            // Called under the model lock except for the initial empty report.
            val connected = clients.values.filter { it.hello != null && !it.closing }.map { it.hello!! }
            val decoders = connected.flatMap { h -> h["decoders"]!!.jsonArray.map { d ->
                DecoderSupport(d.jsonObject["codecString"]!!.jsonPrimitive.content, d.jsonObject["hardware"]!!.jsonPrimitive.boolean)
            } }.groupBy { it.codecString }.map { (codec, _) ->
                // Hardware support must hold for every client to keep the hub's H.265 policy conservative.
                DecoderSupport(codec, connected.filter { h -> h["displays"]!!.jsonArray.any { it.jsonObject.int("screen") == 0 } }.all { h -> h["decoders"]!!.jsonArray.any {
                    (it.jsonObject["codecString"]!!.jsonPrimitive.content == codec || VideoCodecPolicy.isHevc(codec) && VideoCodecPolicy.isHevc(it.jsonObject["codecString"]!!.jsonPrimitive.content)) && it.jsonObject["hardware"]!!.jsonPrimitive.boolean
                } })
            }
            val displays = linkedMapOf<ScreenId, DisplayInfo>()
            connected.forEach { h -> h["displays"]!!.jsonArray.forEach { element ->
                val d = element.jsonObject; val safe = d["safeArea"]!!.jsonObject
                fun px(key: String): Int = safe[key]!!.jsonPrimitive.double.coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt()
                displays.putIfAbsent(ScreenId(d.int("screen")), DisplayInfo(d.int("width"), d.int("height"),
                    (d["dpr"]!!.jsonPrimitive.double * 160).coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt(),
                    d["refreshHz"]!!.jsonPrimitive.double, SafeArea(px("left"), px("top"), px("right"), px("bottom")),
                    d["frameRate"]?.let { FrameRatePreference.valueOf(it.jsonPrimitive.content) } ?: FrameRatePreference.AUTO,
                    d["sustainedFps"]?.jsonPrimitive?.double,
                    d["viewport"]?.jsonObject?.let { Viewport(it.int("width"), it.int("height")) },
                    d["fullscreen"]?.jsonPrimitive?.boolean, d["fullscreenPreferred"]?.jsonPrimitive?.boolean))
            } }
            val caps = OutputCapabilities(LatencyClass.INTERACTIVE, decoders, displays,
                connected.maxOfOrNull { it.int("maxTouchContacts") } ?: 0, emptySet(), connected.flatMap { h -> h["audio"]!!.jsonArray.map { AudioCodec.valueOf(it.jsonPrimitive.content) } }.toSet(),
                connected.any { it["microphone"]!!.jsonPrimitive.boolean },
                prefersH264 = connected.any { h -> h["videoCodec"]?.jsonPrimitive?.content == "H264" && h["displays"]!!.jsonArray.any { it.jsonObject.int("screen") == 0 } })
            if (caps != lastCapabilities) { lastCapabilities = caps; host { it.capabilities(caps) } }
        }

        fun accept() {
            try {
                val listener = ServerSocket()
                server = listener
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(config.bindAddress, config.port))
                if (!active) { listener.close(); return }
                bound.complete(listener.localPort)
                while (active) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    if (config.sendBufferBytes > 0) try { socket.sendBufferSize = config.sendBufferBytes } catch (_: Exception) { }
                    val retained = synchronized(lock) {
                        if (!active || sockets.size - pluginSockets.size >= config.maxClients + (if (plugins != null) 4 else 0)) false else sockets.add(socket)
                    }
                    if (!retained) { safelyClose(socket); continue }
                    thread("connection") { connection(socket) }
                }
            } catch (e: Exception) { bound.completeExceptionally(e) }
            finally { try { server?.close() } catch (_: Exception) { } }
        }

        private fun connection(accepted: Socket) {
            var socket = accepted
            var upgraded = false
            var mediaReserved = false
            var client: Client? = null
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                tlsContext?.let { context ->
                    val secured = context.socketFactory.createSocket(accepted, null, accepted.port, true) as SSLSocket
                    socket = secured
                    synchronized(lock) {
                        sockets.remove(accepted)
                        if (!active) return
                        sockets.add(secured)
                    }
                    secured.useClientMode = false
                    secured.soTimeout = 5000
                    secured.startHandshake()
                }
                val input = DeadlineInput(socket, socket.getInputStream())
                input.deadline = deadline
                val request = readHttp(input)
                // With plugins off, /plugins/ paths take the ordinary route, exactly as before plugins existed.
                if (plugins != null && request.target.substringBefore('?').startsWith("/plugins/")) {
                    val authority = request.headers.getValue("host")
                    if (authority.any { it.code > 127 || !it.isLetterOrDigit() && it !in "._:-[]" }) throw HttpFailure(400)
                    val uri = try { java.net.URI("${if (config.sslContext == null) "http" else "https"}://$authority") } catch (_: Exception) { throw HttpFailure(400) }
                    val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: throw HttpFailure(400)
                    if (uri.port > 65535) throw HttpFailure(400)
                    val port = if (uri.port == -1 || uri.port == (if (config.sslContext == null) 80 else 443)) "" else ":${uri.port}"
                    val origin = "${uri.scheme}://$host$port"
                    if (request.target.substringBefore('?') == "/plugins/channel" && request.method == "GET") {
                        upgrade(request, socket.getOutputStream()); upgraded = true
                        val permit = plugins.reserve()
                        try {
                            synchronized(lock) { pluginSockets.add(socket) }
                            plugins.channel(socket, permit, executor) { active }
                        } finally { permit.close(); synchronized(lock) { pluginSockets.remove(socket) } }
                    } else PluginContract.serve(plugins, request, origin, socket.getOutputStream())
                    return
                }
                if (request.method != "GET") throw HttpFailure(405)
                if (request.target.substringBefore('?') == "/oxp/v0") {
                    if (plugins != null) {
                        if (mediaConnections.incrementAndGet() > config.maxClients) { mediaConnections.decrementAndGet(); throw HttpFailure(503) }
                        mediaReserved = true
                    }
                    upgrade(request, socket.getOutputStream()); upgraded = true
                    input.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    val current = Client(this, socket)
                    client = current
                    synchronized(lock) { if (!active) return; clients[current.clientId] = current }
                    thread("writer-${current.clientId}") { current.write() }
                    current.read(input)
                } else serveStatic(request, config.staticAssetRoot, socket.getOutputStream())
            } catch (e: HttpFailure) {
                if (!upgraded) try { httpError(socket.getOutputStream(), e.status); drainBeforeClose(socket) } catch (_: Exception) { }
            } catch (e: WebSocketFailure) { client?.finish(e.code) }
            catch (_: SocketTimeoutException) { client?.finish(1008) }
            catch (_: Exception) { client?.finish(1002) }
            finally {
                if (mediaReserved) mediaConnections.decrementAndGet()
                if (client == null) safelyClose(socket) else client.finish(1000)
                synchronized(lock) { if (client == null) sockets.remove(socket) }
            }
        }
    }

    private inner class Client(val run: Runtime, val socket: Socket) {
        val clientId = UUID.randomUUID().toString()
        val origin = Origin(id, clientId)
        @Volatile var hello: JsonObject? = null
        var closing = false
        val queues = linkedMapOf<ScreenId, FrameQueue>()
        var queueDurationUs = config.queueDurationUs
        /** Video frames dropped for back-pressure since the client connected, across queue resets. */
        var droppedFrames = 0L
        val audio = AudioQueue()
        private val audioSequences = mutableMapOf<Int, UInt>()
        fun supportsVideo(format: VideoFormat): Boolean = format.codec != VideoCodec.H265 ||
            hello?.get("decoders")?.jsonArray?.any { VideoCodecPolicy.isHevc(it.jsonObject["codecString"]!!.jsonPrimitive.content) } == true
        fun videoFormat(screen: ScreenId, format: VideoFormat?) {
            queues.remove(screen)?.clear()
            json(WebJsonCodec.video(screen, format?.takeIf(::supportsVideo)))
        }
        fun supports(codec: AudioCodec): Boolean = hello?.get("audio")?.jsonArray?.any { it.jsonPrimitive.content == codec.name } == true
        val paused = mutableSetOf<ScreenId>()
        val wake = Semaphore(0)
        private val messages = ArrayDeque<WebSocketMessage>()
        private val sequences = mutableMapOf<ScreenId, UInt>()
        @Volatile private var closeSent = false
        @Volatile private var peerClosed = false
        private val touched = mutableSetOf<ScreenId>()
        @Volatile private var output: ProgressOutputStream? = null
        private var writeTimedOut = false

        fun stalledNanos(now: Long): Long = output?.stalledNanos(now) ?: 0

        /** The peer stopped reading: report why, then close without a close frame, which could not be delivered. */
        fun writeTimedOut(stalledNanos: Long) {
            synchronized(lock) { if (writeTimedOut) return; writeTimedOut = true }
            val reason = buildJsonObject {
                put("type", "relay-close"); put("reason", "write-timeout")
                put("stalledMs", TimeUnit.NANOSECONDS.toMillis(stalledNanos))
            }.toString()
            run.report(clientId to "relay-close") { it.receiverDiagnostics(origin, "relay-close", reason) }
            finish(1011)
            abort()
        }

        /** Closes at once, even when a writer is blocked in this socket; SO_LINGER 0 keeps TLS from waiting on that writer. */
        private fun abort() {
            thread("abort") {
                try { socket.setSoLinger(true, 0) } catch (_: Exception) { }
                safelyClose(socket)
            }
        }

        fun audioFormat(message: JsonObject) {
            if (!audio.format(message)) { finish(1009); return }
            wake.release()
        }
        fun json(message: JsonObject, stampPong: Boolean = false) {
            enqueue(WebSocketMessage(1, WebJsonCodec.encode(message).toByteArray(Charsets.UTF_8), stampPong))
        }
        fun enqueue(message: WebSocketMessage) {
            synchronized(lock) {
                if (closing || !run.active) return
                if (messages.size >= 256) { finish(1009); return }
                messages.addLast(message); wake.release()
            }
        }

        fun finish(code: Int, payload: ByteArray = closePayload(code), fromPeer: Boolean = false) {
            synchronized(lock) {
                if (fromPeer) peerClosed = true
                if (closing) {
                    if (peerClosed && closeSent) run.executor.execute { safelyClose(socket) }
                    return
                }
                closing = true
                queues.clear(); audio.clear(); messages.clear()
                messages.addLast(WebSocketMessage(8, payload)); wake.release()
                if (hello != null) {
                    touched.forEach { screen -> run.host { it.control(ControlEvent.Touch(screen, emptyList(), origin)) } }
                    hello = null; run.reportCapabilities()
                }
                if (run.active) try {
                    run.executor.schedule({
                        // A writer still blocked after a second would hold a graceful TLS close as well.
                        if (stalledNanos(System.nanoTime()) > 0) abort() else safelyClose(socket)
                        synchronized(lock) { run.sockets.remove(socket) }
                    }, 1, TimeUnit.SECONDS)
                } catch (_: java.util.concurrent.RejectedExecutionException) { }
            }
        }

        fun read(input: DeadlineInput) {
            val reader = WebSocketReader(input, 1024 * 1024)
            while (run.active && !socket.isClosed) {
                val message = reader.read()
                when (message.opcode) {
                    8 -> { finish(1000, message.payload, true); return }
                    9 -> enqueue(WebSocketMessage(10, message.payload))
                    10 -> Unit
                    1 -> {
                        if (synchronized(lock) { closing }) continue
                        val json = WebJsonCodec.parse(utf8(message.payload), WebJsonCodec.Direction.CLIENT_TO_RELAY)
                        if (hello == null && json["type"]!!.jsonPrimitive.content != "hello") throw WebSocketFailure(1008, "Hello required")
                        handle(json)
                        if (hello != null) { input.deadline = Long.MAX_VALUE; socket.soTimeout = 0 }
                    }
                    2 -> {
                        if (hello == null) throw WebSocketFailure(1008, "Hello required")
                        val binary = OxpWebCodec.decode(message.payload)
                        synchronized(lock) {
                            if (!closing) {
                                val event = when (binary.header.kind) {
                                    BinaryKind.TOUCH -> ControlEvent.Touch(ScreenId(binary.header.stream), OxpWebCodec.decodeTouch(binary.payload), origin)
                                    BinaryKind.KEY -> OxpWebCodec.decodeKey(binary.payload).let { ControlEvent.Key(it.code, it.down, origin) }
                                    BinaryKind.MICROPHONE -> {
                                        val request = microphones[binary.header.stream]
                                        if (hello?.get("microphone")?.jsonPrimitive?.boolean != true || request == null ||
                                            !validMicPacket(request, binary.payload.size)) throw WebSocketFailure(1008, "Invalid microphone frame")
                                        ControlEvent.MicPacket(binary.header.stream, binary.payload, binary.header.ptsUs, origin)
                                    }
                                    else -> throw WebSocketFailure(1008, "Client media unsupported in v0")
                                }
                                if (event is ControlEvent.Touch) touched.add(event.screen)
                                run.host { it.control(event) }
                            }
                        }
                    }
                }
            }
        }

        private fun handle(message: JsonObject) {
            when (message["type"]!!.jsonPrimitive.content) {
                "hello" -> {
                    synchronized(lock) {
                        if (closing || !run.active) return
                        val first = hello == null
                        val previous = hello
                        hello = JsonObject(message - "token")
                        queueDurationUs = playbackQueueDurationUs(message["playback"]?.jsonPrimitive?.content, config.queueDurationUs)
                        queues.values.forEach { it.maxDurationUs = queueDurationUs }
                        if (first) {
                            json(receiverWelcome(clientId, state, config.sourceUrl, plugins != null, config.displaySettings != null, config.relay, config.viewArea != null, receiverBuild))
                            config.displaySettings?.let { json(it.current().message()) }
                            config.viewArea?.current()?.let { json(it.message()) }
                        }
                        formats.forEach { (screen, format) ->
                            val before = format.codec != VideoCodec.H265 || previous?.get("decoders")?.jsonArray?.any {
                                VideoCodecPolicy.isHevc(it.jsonObject["codecString"]!!.jsonPrimitive.content)
                            } == true
                            if (first || before != supportsVideo(format)) videoFormat(screen, format)
                        }
                        audioFormats.forEach { (stream, format) ->
                            val before = previous?.get("audio")?.jsonArray?.any { it.jsonPrimitive.content == format.codec.name } == true
                            val after = supports(format.codec)
                            if (before != after) {
                                val declaration = WebJsonCodec.audio(stream, if (after) format else null)
                                if (first) json(declaration) else audioFormat(declaration)
                            }
                        }
                        val hadMic = previous?.get("microphone")?.jsonPrimitive?.boolean == true
                        val hasMic = hello!!["microphone"]!!.jsonPrimitive.boolean
                        if (hadMic != hasMic) microphones.forEach { (stream, request) ->
                            json(WebJsonCodec.microphone(stream, if (hasMic) request else null))
                        }
                        run.reportCapabilities()
                    }
                }
                "pause" -> synchronized(lock) {
                    if (closing || !run.active) return
                    val screen = ScreenId(message.int("screen"))
                    if (message["paused"]!!.jsonPrimitive.boolean) {
                        paused.add(screen)
                        queues.remove(screen)?.clear()
                    } else if (paused.remove(screen)) {
                        queues.remove(screen)?.clear()
                        videoFormat(screen, formats[screen])
                    }
                }
                "display-settings" -> synchronized(lock) {
                    if (closing || !run.active) return
                    val port = config.displaySettings ?: return
                    val primary = run.clients.values.firstOrNull { !it.closing && it.hello?.get("displays")?.jsonArray?.isNotEmpty() == true }
                    val now = System.nanoTime()
                    if (primary === this && (run.lastRestartNanos == null || now - run.lastRestartNanos!! >= TimeUnit.SECONDS.toNanos(10))) {
                        run.lastRestartNanos = now
                        run.host { port.set(DisplaySettings(message.getValue("resolution").jsonPrimitive.content,
                            message.getValue("screenWidth").jsonPrimitive.int, message["videoCodec"]?.jsonPrimitive?.content ?: port.current().videoCodec), origin) }
                    }
                }
                "restart" -> synchronized(lock) {
                    val primary = run.clients.values.firstOrNull { !it.closing && it.hello?.get("displays")?.jsonArray?.isNotEmpty() == true }
                    val now = System.nanoTime()
                    if (primary === this && (run.lastRestartNanos == null || now - run.lastRestartNanos!! >= TimeUnit.SECONDS.toNanos(10))) {
                        run.lastRestartNanos = now
                        run.host { it.requestRestart(origin) }
                    }
                }
                "keyframe" -> run.host { it.control(ControlEvent.KeyFrameRequest(ScreenId(message.int("screen")), origin)) }
                "ping" -> {
                    val received = System.nanoTime() / 1000
                    json(buildJsonObject {
                        put("type", "pong"); put("t0Us", message["t0Us"]!!)
                        put("t1Us", received.toString()); put("t2Us", received.toString())
                    }, stampPong = true)
                }
                "pong" -> Unit // The browser initiates clock synchronization in v0.
                "fullscreen-exit" -> run.report(clientId to "fullscreen-exit") { it.receiverDiagnostics(origin, "fullscreen-exit", message.toString()) }
                "receiver-info" -> run.report(clientId to "receiver-info") { it.receiverDiagnostics(origin, "receiver-info", message.toString()) }
                "stats" -> {
                    // The relay's own counter rides along with the receiver's report for this client.
                    val dropped = synchronized(lock) { droppedFrames }
                    val report = JsonObject(message + ("relayDroppedFrames" to JsonPrimitive(dropped))).toString()
                    run.report(clientId to "stats-diagnostics") { it.receiverDiagnostics(origin, "stats", report) }
                    fun us(key: String): Long? = message[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.double?.let { (it * 1000).toLong() }
                    val stats = OutputStats(origin, ScreenId(message.int("screen")), us("decodeMs"), message.int("decodeQueue"),
                        message["dropped"]!!.jsonPrimitive.double.toLong(), message["rendered"]!!.jsonPrimitive.double.toLong(), us("renderedLatencyMs"),
                        us("audioBufferedMs"), message["audioUnderruns"]?.jsonPrimitive?.double?.toLong() ?: 0)
                    run.report(Triple(clientId, "stats", stats.screen)) { it.stats(stats) }
                }
            }
        }

        fun write() {
            try {
                val output = ProgressOutputStream(socket.getOutputStream()).also { this.output = it }
                while (run.active && !socket.isClosed) {
                    wake.acquire()
                    wake.drainPermits()
                    while (run.active && !socket.isClosed) {
                        val next = synchronized(lock) {
                            if (messages.isNotEmpty()) messages.removeFirst()
                            else audio.pollFormat()?.let { WebSocketMessage(1, WebJsonCodec.encode(it).toByteArray(Charsets.UTF_8)) }
                                ?: audio.poll()?.let { SelectedAudio(it, audio.generation(it.stream)) }
                                ?: queues.values.firstNotNullOfOrNull { queue -> queue.poll()?.let { SelectedVideo(queue, it) } }
                        } ?: break
                        if (next is WebSocketMessage) {
                            val payload = if (next.stampPong) {
                                val pong = WebJsonCodec.parse(utf8(next.payload))
                                WebJsonCodec.encode(JsonObject(pong + ("t2Us" to JsonPrimitive((System.nanoTime() / 1000).toString()))))
                                    .toByteArray(Charsets.UTF_8)
                            } else next.payload
                            writeWebSocket(output, next.opcode, payload)
                            if (next.opcode == 8) {
                                synchronized(lock) { closeSent = true }
                                if (peerClosed) safelyClose(socket)
                                return
                            }
                            continue
                        }
                        if (next is SelectedAudio) {
                            val packet = next.packet
                            if (synchronized(lock) { audio.generation(packet.stream) != next.generation }) continue
                            val seq = audioSequences[packet.stream] ?: 0u
                            audioSequences[packet.stream] = seq + 1u
                            val header = OxpWebCodec.encodeHeader(BinaryHeader(BinaryKind.AUDIO, packet.stream, 0,
                                seq, packet.ptsUs, System.nanoTime() / 1000))
                            writeWebSocket(output, 2, header, packet.data)
                            continue
                        }
                        val selected = next as SelectedVideo
                        val frame = selected.frame
                        // A pause or format change invalidates even a frame selected by the writer.
                        if (synchronized(lock) { queues[frame.frame.screen] !== selected.queue || frame.frame.screen in paused }) continue
                        // Metadata and media selection share a lock so a new format always precedes its frames.
                        val f = frame.frame
                        val sequence = sequences[f.screen] ?: 0u
                        sequences[f.screen] = sequence + 1u
                        val header = OxpWebCodec.encodeHeader(BinaryHeader(BinaryKind.VIDEO, f.screen.value,
                            (if (f.keyFrame) OxpWebCodec.KEYFRAME else 0) or (if (frame.gap) OxpWebCodec.GAP else 0),
                            sequence, f.ptsUs, f.receivedAtNanos / 1000))
                        writeWebSocket(output, 2, header, f.data)
                    }
                }
            } catch (_: Exception) { finish(1001) }
            finally {
                synchronized(lock) { run.clients.remove(clientId); run.reportCapabilities() }
                if (!closeSent || peerClosed || !run.active) {
                    safelyClose(socket)
                    synchronized(lock) { run.sockets.remove(socket) }
                }
            }
        }
    }
}

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.double.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

/** Enforces an absolute deadline even when an unauthenticated peer trickles bytes. */
private class DeadlineInput(private val socket: Socket, input: InputStream) : FilterInputStream(input) {
    var deadline: Long = Long.MAX_VALUE
    private fun timeout() {
        if (deadline == Long.MAX_VALUE) return
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("Handshake deadline")
        socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    override fun read(): Int { timeout(); return `in`.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { timeout(); return `in`.read(b, off, len) }
}
