// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import org.openxplay.stream.clink.trace.*
import java.io.Closeable
import java.io.EOFException
import java.net.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import org.openxplay.stream.core.audio.AudioClock
import org.openxplay.stream.clink.protocol.*
import org.openxplay.stream.clink.uibc.UibcDecoder
import org.openxplay.stream.clink.wfd.*
import org.openxplay.stream.core.*

/** Failure reasons meaning the saved head-unit key no longer belongs to this head unit. */
private val stalePeerReasons = setOf("peer_key_changed", "auth_signature_failed")

/** One authenticated socket generation. Run on a lifecycle worker; stop is non-blocking. */
public class ClinkSession(
    private val link: ClinkLink,
    private val pinProvider: PinProvider,
    private val trustStore: TrustStore,
    private val ports: ClinkPorts,
    private val threads: ThreadFactory,
    private val outputId: String,
    private val listener: ClinkListener,
    private val onCapabilities: (OutputCapabilities) -> Unit,
    private val onControl: (ControlEvent) -> Unit,
    private val onPlay: (Boolean) -> Unit,
    private val onKeyframe: () -> Unit,
    private val deviceId: String = "openxplay-stream",
    private val deviceName: String = "OpenXPlay",
    private val vehicleData: Boolean = false,
    private val onVehicle: (VehicleData) -> Unit = {},
    private val onLinkStalled: (Boolean) -> Unit = {},
    private val trace: ClinkTrace = ClinkTrace.NONE,
    sessionNumber: Long? = null,
    private val hevc: ClinkHevcMode = ClinkHevcMode.STREAM_ONLY,
) {
    internal val traceSession: Long = sessionNumber ?: nextTraceSession()
    internal companion object {
        private val sessions = AtomicLong()
        internal fun nextTraceSession(): Long = sessions.incrementAndGet()
    }
    private fun service(port: Int): String = when (port) {
        ports.auth -> "auth"; ports.control -> "control"; ports.media -> "media"; ports.cert -> "cert"
        ports.rtsp -> "rtsp"; ports.uibc -> "uibc"; ports.sensor -> "sensor"; else -> "rtp"
    }
    private fun socketEvent(port: Int, event: String, address: String? = null, error: Throwable? = null, name: String = service(port)) {
        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Socket(System.nanoTime(), traceSession,
            name, event, address, port, error?.javaClass?.name))
    }
    private fun frameEvent(direction: String, port: Int, frame: Frame, body: ByteArray, decrypted: Boolean = true) {
        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Frame(System.nanoTime(), traceSession, direction,
            port, service(port), frame.category, frame.method, frame.kind, frame.sequence, body.size,
            ClinkTraceFields.decode(service(port), body, decrypted)))
    }
    private fun rtspEvent(direction: String, message: RtspMessage) {
        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Rtsp(System.nanoTime(), traceSession,
            direction, message.line, message.headers.toMap(), message.body))
    }
    private fun rtspWrite(socket: Socket, message: RtspMessage) {
        rtspEvent("TX", message); write(socket, message.encode())
    }
    private sealed interface Event {
        data class Accepted(val service: Int, val socket: Socket) : Event
        data class Bytes(val service: Int, val bytes: ByteArray) : Event
        data class Closed(val service: Int, val cause: Throwable?) : Event
        data class Lost(val cause: Throwable?) : Event
    }
    private val events = ArrayBlockingQueue<Event>(128)
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val writes = ConcurrentHashMap<Socket, Long>()
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val tracedSockets = ConcurrentHashMap<Socket, Pair<Int, String>>()
    private val readers = mutableMapOf<Int, FrameReader>()
    private val ciphers = mutableMapOf<Int, SecureRecord>()
    private val accepted = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var stopped = false
    @Volatile private var linkLost = false
    @Volatile private var linkLossCause: Throwable? = null
    @Volatile private var ready = false
    @Volatile private var playing = false
    @Volatile private var destinationPort = ports.rtp
    @Volatile private var addresses: ClinkAddresses? = null
    @Volatile private var video: Socket? = null
    private var connectedVideoPort = 0
    @Volatile private var mode = WfdMode(1280, 720, 30, 5, 1, 1)
    private var key = byteArrayOf()
    private val sequence = AtomicLong(10)
    private data class Ack(val service: Int, val done: CountDownLatch = CountDownLatch(1))
    private val acknowledgements = ConcurrentHashMap<Long, Ack>()
    @Volatile private var stopRequested = false
    @Volatile private var selectedAudio = false
    private val micLock = Any()
    private var desiredMic: MicRequest? = null
    private var appliedMic: MicRequest? = null
    private var micBytes = byteArrayOf()
    private var micClock: AudioClock? = null
    public val sensorActive: Boolean get() = !stopped && !stopRequested && sockets.containsKey(ports.sensor)
    @Volatile public var malformedSensorFrames: Long = 0
        private set
    private fun reportSensorState() { listener.vehicleState(sensorActive, malformedSensorFrames) }
    private fun malformedSensor(failure: Exception) {
        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Error(System.nanoTime(), traceSession, "malformed_sensor", failure.javaClass.name, "ClinkSession.sensor"))
        malformedSensorFrames++
        reportSensorState()
    }
    public val mediaAudio: Boolean get() = selectedAudio
    public val audioReady: Boolean get() = isPlaying && sockets.containsKey(ports.media)
    public fun microphone(request: MicRequest?) {
        require(request == null || request.codec == AudioCodec.PCM_S16LE && request.sampleRate in 8000..48000 &&
            request.channels in 1..2 && request.frameMs == 20 && request.sampleRate % 50 == 0) { "invalid_microphone_request" }
        synchronized(micLock) {
            if (request == desiredMic) return
            desiredMic = request; micBytes = byteArrayOf(); micClock = request?.let { AudioClock(it.sampleRate) }
        }
    }
    private fun fields(vararg values: Pair<Int, Int>): ByteArray =
        Fields(values.associate { it.first to FieldValue.Varint(it.second.toULong()) }).encode()
    public fun voiceState(start: Boolean) {
        synchronous(ports.control, 1, 8, fields(1 to 3, 2 to if (start) 0 else 1, 3 to 16000, 4 to 1, 5 to 2), 1)
    }
    public fun sendVoice(data: ByteArray, waitForAck: Boolean = false) {
        if (waitForAck) synchronous(ports.media, 4, 3, data, 0)
        else send(ports.media, 4, 3, data, format = 0)
    }
    private fun synchronous(service: Int, category: Int, method: Int, body: ByteArray, format: Int) {
        val number = sequence.incrementAndGet() and 0xffffffffL
        val ack = Ack(service); acknowledgements[number] = ack
        try {
            send(service, category, method, body, 3, number, format)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!stopped && !stopRequested && !ack.done.await(10, TimeUnit.MILLISECONDS)) {
                check(System.nanoTime() < deadline) { "audio_ack_timeout" }
            }
        } finally { acknowledgements.remove(number) }
    }
    private fun updateMicrophone() {
        val change = synchronized(micLock) {
            if (!ready || !sockets.containsKey(ports.media) || desiredMic == appliedMic) return
            (desiredMic to appliedMic).also { appliedMic = desiredMic }
        }
        val (request, previous) = change
        send(ports.control, 1, 6, fields(1 to if (request == null) 0 else 1,
            2 to (request?.sampleRate ?: previous?.sampleRate ?: 16000),
            3 to (request?.channels ?: previous?.channels ?: 1), 4 to 2), kind = 3)
    }
    private fun microphoneFrame(body: ByteArray) {
        val packets = synchronized(micLock) {
            val request = desiredMic ?: return
            if (request != appliedMic) return
            val sampleBytes = request.channels * 2
            require(body.isNotEmpty() && body.size % sampleBytes == 0 && body.size / sampleBytes <= request.sampleRate / 10) {
                "invalid_microphone_frame"
            }
            micBytes += body
            val samples = request.sampleRate / 50
            val size = samples * sampleBytes
            buildList {
                while (micBytes.size >= size) {
                    val data = micBytes.copyOfRange(0, size); micBytes = micBytes.copyOfRange(size, micBytes.size)
                    add(ControlEvent.MicPacket(request.stream, data, checkNotNull(micClock).timestamp(samples), Origin(outputId, headUnitId)))
                }
            }
        }
        packets.forEach { packet ->
            if (synchronized(micLock) { desiredMic == appliedMic && desiredMic != null }) onControl(packet)
        }
    }
    private var configSequence = 0L
    private var certified = false
    private var controlConfigured = false
    private var peerKey: ByteArray? = null
    @Volatile public var isStalled: Boolean = false
        private set
    internal fun updateLink(health: LinkHealth, now: Long, received: Boolean) {
        health.poll(now)?.let { linkTransition(true, it) }
        if (received) health.received(now)?.let { linkTransition(false, it) }
    }
    private fun linkTransition(stalled: Boolean, message: String) {
        isStalled = stalled
        onLinkStalled(stalled)
        listener.diagnostic(message)
        if (!stalled) onKeyframe()
    }
    private var display = DisplayInfo(1280, 720, 160, 30.0)
    private lateinit var wfd: WfdSource
    private lateinit var touch: UibcDecoder
    public val headUnitId: String get() = addresses?.headUnitId.orEmpty()
    public val hevcEnabled: Boolean get() = decoderSnapshot.any { it.codecString.startsWith("hvc1.") }
    public val decoders: List<DecoderSupport> get() = decoderSnapshot
    @Volatile private var decoderSnapshot: List<DecoderSupport> = emptyList()
    public val isPlaying: Boolean get() = playing && ready && !stopped && !stopRequested
    public val videoSize: Pair<Int, Int> get() = mode.width to mode.height
    public fun stop() { microphone(null); stopRequested = true }
    private fun worker(name: String, action: () -> Unit) {
        threads.newThread { action() }.apply { this.name = "clink-$name"; start() }
    }
    private fun enqueue(event: Event) {
        while (!stopped && !events.offer(event, 100, TimeUnit.MILLISECONDS)) { /* Backpressure on socket workers. */ }
    }
    private fun closeResources() { resources.forEach { resource ->
        val wasClosed = when (resource) { is Socket -> resource.isClosed; is ServerSocket -> resource.isClosed; else -> false }
        runCatching { resource.close() }
        if (!wasClosed && resource is Socket) {
            val acceptedPort = sockets.entries.firstOrNull { it.value === resource }?.key
            val endpoint = tracedSockets[resource]
            socketEvent(endpoint?.first ?: acceptedPort ?: resource.port, "CLOSED", resource.inetAddress?.hostAddress,
                name = endpoint?.second ?: acceptedPort?.let(::service) ?: "rtp")
        }
        if (!wasClosed && resource is ServerSocket) socketEvent(resource.localPort, "CLOSED")
    } }
    private fun write(socket: Socket, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(!stopped)
        synchronized(socket) {
            writes[socket] = System.nanoTime()
            try { socket.getOutputStream().write(bytes, offset, length) } finally { writes.remove(socket) }
        }
    }
    private fun listen(service: Int, local: InetAddress, peer: InetAddress) {
        val server = ServerSocket().also { resources += it; it.reuseAddress = true; try { it.bind(InetSocketAddress(local, service)) } catch (e: Exception) { socketEvent(service, "ERROR", error = e); throw e } }
        socketEvent(service, "LISTEN")
        worker("accept-$service") {
            try {
                while (!stopped) {
                    val socket = server.accept()
                    if (stopped || socket.inetAddress != peer || !accepted.add(service)) { socket.close(); continue }
                    if (trace !== ClinkTrace.NONE) tracedSockets[socket] = service to service(service)
                    socketEvent(service, "ACCEPTED", socket.inetAddress.hostAddress)
                    socket.tcpNoDelay = true; resources += socket
                    enqueue(Event.Accepted(service, socket))
                    worker("read-$service") {
                        var cause: Throwable? = null
                        try {
                            val input = socket.getInputStream(); val buffer = ByteArray(65536)
                            while (!stopped) {
                                val n = input.read(buffer); if (n < 0) break
                                if (n > 0) enqueue(Event.Bytes(service, buffer.copyOf(n)))
                            }
                        } catch (e: Exception) { cause = e }
                        finally { socketEvent(service, if (cause == null) "CLOSED" else "ERROR", socket.inetAddress.hostAddress, cause); enqueue(Event.Closed(service, cause)) }
                    }
                }
            } catch (e: Exception) { socketEvent(service, "ERROR", error = e); enqueue(Event.Lost(e)) }
        }
    }
    private fun dial(port: Int, name: String = "rtp"): Socket {
        val a = checkNotNull(addresses)
        check(!stopped)
        return Socket().also { socket ->
            resources += socket
            if (trace !== ClinkTrace.NONE) tracedSockets[socket] = port to name
            if (stopped) { socket.close(); error("session_stopped") }
            socket.bind(InetSocketAddress(a.localIpv4, 0))
            socketEvent(port, "DIAL", a.headUnitIpv4, name = name)
            try { socket.connect(InetSocketAddress(a.headUnitIpv4, port), 12000) }
            catch (e: Exception) { socketEvent(port, "ERROR", a.headUnitIpv4, e, name); throw e }
            socketEvent(port, "CONNECTED", a.headUnitIpv4, name = name)
            socket.tcpNoDelay = true
        }
    }
    private fun authenticate(socket: Socket, identity: AuthIdentity): ByteArray {
        socket.soTimeout = 250
        val a = checkNotNull(addresses)
        val pinned = trustStore.loadPeerKey(a.headUnitId)
        var auth = Auth(identity, deviceId, deviceName, a.localIpv4, pinned)
        var full = pinned == null
        var pin: String? = if (full) pinProvider.requestPin(a.headUnitId) ?: error("pin_cancelled") else null
        var sequence = 1L
        var attempt = auth.start(now(), sequence, pin)
        val decoder = FrameReader(); val buffer = ByteArray(65536)
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var retries = 0
        // A head unit that rebuilds its pairing store at boot presents a new P-256 identity while the stored
        // pin is still the old one; every attempt then fails in quick mode (auth_signature_failed) or in the
        // full handshake (peer_key_changed), and only clearing app data recovered (2026-10-19). Drop the
        // stale pin and pair again once on this socket.
        var repinned = false
        while (!stopped && !stopRequested) {
            if (linkLost) throw linkLossCause ?: IllegalStateException("link_lost")
            attempt.frames.forEach { frameEvent("TX", ports.auth, it, it.body, false); write(socket, it.encode()) }
            attempt.sessionKey?.let { sessionKey ->
                peerKey = attempt.events.filterIsInstance<AuthEvent.PeerVerified>().single().publicKey
                return sessionKey
            }
            check(System.nanoTime() < deadline) { "auth_timeout" }
            val n = try { socket.getInputStream().read(buffer) } catch (_: SocketTimeoutException) { 0 }
            if (n < 0) throw EOFException("auth_closed")
            if (n == 0) { attempt = AuthOutput(); continue }
            val frames = decoder.feed(buffer.copyOf(n))
            attempt = AuthOutput()
            for (frame in frames) {
                frameEvent("RX", ports.auth, frame, frame.body, false)
                attempt = auth.receive(frame, now())
                val failed = attempt.events.filterIsInstance<AuthEvent.Failed>().firstOrNull()?.reason
                if (failed != null) {
                    if (failed !in stalePeerReasons || repinned) error(failed)
                    // Forget before retrying: the full handshake must not compare against the old key.
                    trustStore.forgetPeer(a.headUnitId)
                    listener.diagnostic("auth repin reason=$failed")
                    repinned = true
                    full = true
                    pin = pinProvider.requestPin(a.headUnitId) ?: error("pin_cancelled")
                    sequence = (sequence + 2) and 0xffffffffL
                    auth = Auth(identity, deviceId, deviceName, a.localIpv4, null)
                    attempt = auth.start(now(), sequence, pin, forceFull = true)
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    break
                }
                val result = attempt.events.filterIsInstance<AuthEvent.Result>().firstOrNull()?.result
                if (result == AuthResult.PIN_REQUIRED && !full && retries++ == 0) {
                    full = true; pin = pinProvider.requestPin(a.headUnitId) ?: error("pin_cancelled")
                    sequence = (sequence + 2) and 0xffffffffL
                    attempt = auth.start(now(), sequence, pin, forceFull = true)
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                } else if (result == AuthResult.CONFIRMATION_REQUIRED && retries++ < 3) {
                    check(pinProvider.confirmPairing(a.headUnitId)) { "pairing_confirmation_cancelled" }
                    sequence = (sequence + 2) and 0xffffffffL
                    attempt = auth.start(now(), sequence, pin, forceFull = full, confirmed = true)
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                } else if (result != null && result != AuthResult.SUCCESS) error("auth_${result.name.lowercase()}")
            }
        }
        error("session_stopped")
    }
    private fun send(service: Int, category: Int, method: Int, body: ByteArray = byteArrayOf(),
        kind: Int = 0, seq: Long? = null, format: Int = 1): Long {
        val number = seq ?: (sequence.incrementAndGet() and 0xffffffffL)
        val socket = checkNotNull(sockets[service])
        if (trace !== ClinkTrace.NONE) frameEvent("TX", service, Frame(number, now(), 1, format, kind, category, method, body), body)
        write(socket, Frame(number, now(), 1, format, kind, category, method, SecureRecord(key).seal(body)).encode())
        return number
    }
    private fun reportCapabilities() {
        decoderSnapshot = wfd.decoders
        onCapabilities(OutputCapabilities(LatencyClass.INTERACTIVE, decoderSnapshot,
            mapOf(ScreenId.MAIN to canvas(display, mode.width, mode.height)), 2, UibcDecoder.keys,
            setOf(AudioCodec.AAC_LC, AudioCodec.PCM_S16LE), true))
    }
    private fun updatePlay() {
        ready = certified && controlConfigured
        val next = ready && wfd.playing
        if (next != playing) { playing = next; onPlay(next); if (next) listener.state(ClinkState.PLAYING) }
    }
    private val malformedRecords = ArrayDeque<Long>()
    private fun handleFrame(service: Int, frame: Frame) {
        require(frame.source == 0 && frame.format <= 1) { "invalid_message_origin" }
        if (frame.category == 7 && frame.kind == 2 && frame.body.isEmpty()) {
            frameEvent("RX", service, frame, frame.body)
            // A real car acknowledges with an empty plaintext record instead of a sealed empty body
            // (2026-10-16); dropping it timed out voice start and ended the session.
            acknowledgements[frame.sequence]?.let { if (it.service == service) it.done.countDown() }
            return
        }
        val body = try { ciphers.getValue(service).open(frame.body) } catch (e: IllegalArgumentException) {
            frameEvent("RX", service, frame, frame.body, false)
            // The sensor channel already counts and tolerates malformed frames (see malformedSensor).
            if (service == ports.sensor) throw e
            // Head units occasionally send a record we cannot parse; one bad frame ended the whole
            // session every few minutes (2026-10-15). Skip it, keep authentication and replay checks,
            // and give up only when they keep coming. Headers only: never log record contents.
            val record = frame.body
            val n = if (record.size >= 4) java.nio.ByteBuffer.wrap(record).int else -1
            val size = if (n in 0..record.size - 8) java.nio.ByteBuffer.wrap(record, n + 4, 4).int else -1
            listener.diagnostic("frame record_skipped service=$service category=${frame.category} method=${frame.method} " +
                "kind=${frame.kind} bytes=${record.size} iv=$n cipher=$size reason=${e.message ?: "malformed"}")
            val now = System.nanoTime()
            malformedRecords.addLast(now)
            while (malformedRecords.isNotEmpty() && now - malformedRecords.first() > 60_000_000_000) malformedRecords.removeFirst()
            if (malformedRecords.size > 10) throw e
            return
        }
        frameEvent("RX", service, frame, body)
        if (frame.category == 7) {
            require(frame.format == 0 && frame.kind == 2 && body.isEmpty()) { "invalid_acknowledgement" }
            acknowledgements[frame.sequence]?.let { if (it.service == service) it.done.countDown() }
            return
        }
        if (service == ports.sensor) {
            require(vehicleData && frame.format == 1 && frame.category == 2 && frame.kind in listOf(0, 3)) { "invalid_sensor_frame" }
            VehicleCodec.sensor(frame.method, body)?.let(onVehicle)
            if (frame.kind == 3) send(service, 7, 0, kind = 2, seq = frame.sequence, format = 0)
            return
        }
        if (service == ports.control && frame.category == 1 && frame.method == 15) {
            if (vehicleData) {
                try {
                    require(frame.format == 1 && frame.kind in listOf(0, 3)) { "invalid_day_night_frame" }
                    onVehicle(VehicleCodec.dayNight(body))
                } catch (_: IllegalArgumentException) { listener.diagnostic("malformed_day_night") }
            }
            if (frame.kind == 3) send(service, 7, 0, kind = 2, seq = frame.sequence, format = 0)
            return
        }
        if (service == ports.media) {
            require(certified && frame.format == 0 && frame.category == 4 && frame.kind == 0) { "invalid_media_frame" }
            if (frame.method == 9) microphoneFrame(body)
            return
        }
        // The assistant's optional PCM prelude is deliberately discarded in v0.
        if (service == ports.control && frame.category == 1 && frame.method == 16) {
            require(certified)
            val origin = Origin(outputId, headUnitId)
            onControl(ControlEvent.Key(KeyCode.VOICE, true, origin))
            onControl(ControlEvent.Key(KeyCode.VOICE, false, origin))
            if (frame.kind == 3) send(service, 7, 0, kind = 2, seq = frame.sequence, format = 0)
            return
        }
        require(frame.format == 1)
        when (service) {
            ports.control -> {
                require(frame.category == 1)
                when (frame.method) {
                    1 -> { Fields.decode(body) }
                    2 -> {
                        val request = Fields.decode(body).number(1)
                        send(service, 1, 3, Control.serverResponse(when (request) { 3uL -> ports.uibc; 2uL -> ports.media; else -> 0 }), 2, frame.sequence)
                    }
                    24 -> {
                        if (body.isNotEmpty()) { display = Control.display(body, display); reportCapabilities() }
                        send(service, 1, 25, Control.displayBody(DisplayInfo(mode.width, mode.height, display.densityDpi, mode.fps.toDouble())), 2, frame.sequence)
                    }
                    25 -> {
                        require(frame.kind == 2 && frame.sequence == configSequence) { "unexpected_configuration" }
                        display = Control.display(body, display); controlConfigured = true; reportCapabilities()
                    }
                    // Methods 4 and 5 arrive when the car takes the screen and gives it back (2026-10-20,
                    // shifting through reverse; field 1 epoch ms, field 2 its RTSP port). Its decoder restarts
                    // on return, and H.265 passthrough has no encoder that sends periodic key frames.
                    4, 5 -> {
                        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), traceSession,
                            "keyframe_request", "head_unit_m${frame.method}"))
                        onKeyframe()
                    }
                    23 -> { stopRequested = true; return }
                    12 -> {
                        check(certified)
                        val f = Fields.decode(body)
                        val code = f.number(2); val action = f.number(1)
                        require(code <= 65535uL && action <= 2uL && f.number(3) <= 0xffffffffuL)
                        val mapped = UibcDecoder.key(code.toInt(), action.toInt(), Origin(outputId, headUnitId))
                        // Log the raw code so a head unit with non-standard key values can be mapped from
                        // the trace instead of guessed.
                        listener.diagnostic("control key code=${code.toInt()} action=${action.toInt()} " +
                            "mapped=${mapped?.code ?: "none"} down=${mapped?.down}")
                        mapped?.let(onControl)
                    }
                }
            }
            ports.cert -> {
                require(frame.category == 6 && frame.method == 1 && !certified)
                val identity = Certificate.check(body)
                if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Link(System.nanoTime(), traceSession, "head_unit_certificate", identity))
                trustStore.savePeerKey(headUnitId, checkNotNull(peerKey))
                certified = true
            }
        }
        if (frame.kind == 3) send(service, 7, 0, kind = 2, seq = frame.sequence, format = 0)
        updatePlay()
    }
    /** Blocks until disconnect. A new instance is required for each network generation. */
    public fun run(): Unit {
        if (stopped || stopRequested) return
        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), traceSession, "reset", "session"))
        worker("watchdog") {
            while (!stopped) {
                val now = System.nanoTime()
                writes.forEach { (socket, started) -> if (now - started > LinkHealth.RECEIVE_TIMEOUT_NS) runCatching { socket.close() } }
                Thread.sleep(100)
            }
            closeResources()
        }
        try {
            listener.state(ClinkState.CONNECTING)
            addresses = link.connect { cause -> linkLossCause = cause; linkLost = true }
            check(!stopped)
            val a = checkNotNull(addresses)
            val local = ipv4(a.localIpv4); val peer = ipv4(a.headUnitIpv4)
            touch = UibcDecoder(Origin(outputId, a.headUnitId), { videoSize }, trace, traceSession)
            wfd = WfdSource(a.localIpv4, ports, hevc) { display }
            // Bind before AuthConfirm; reverse connections can arrive immediately.
            listOf(ports.control, ports.cert, ports.media, ports.rtsp, ports.uibc).forEach { listen(it, local, peer) }
            if (vehicleData) { listen(ports.sensor, local, peer); reportSensorState() }
            val identity = trustStore.loadIdentity() ?: AuthIdentity.generate().also(trustStore::saveIdentity)
            listener.state(ClinkState.AUTHENTICATING)
            val authPort = if (ports.auth != 57209) ports.auth else a.authPort
            val auth = dial(authPort, "auth")
            key = authenticate(auth, identity); auth.close(); socketEvent(authPort, "CLOSED", a.headUnitIpv4, name = "auth")
            listener.state(ClinkState.NEGOTIATING)
            var health = LinkHealth(System.nanoTime())
            var nextHeartbeat = System.nanoTime()
            val rtspReader = RtspReader()
            val inputCipher = SecureRecord(key)
            var startedPlaying = false
            val negotiationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!stopped && !stopRequested) {
                if (linkLost) throw linkLossCause ?: IllegalStateException("link_lost")
                val event = events.poll(100, TimeUnit.MILLISECONDS)
                if (sockets.containsKey(ports.control)) {
                    updateLink(health, System.nanoTime(), event is Event.Bytes && event.bytes.isNotEmpty())
                }
                when (event) {
                    is Event.Accepted -> {
                        sockets[event.service] = event.socket
                        when (event.service) {
                            ports.control -> {
                                ciphers[event.service] = SecureRecord(key); readers[event.service] = FrameReader()
                                health = LinkHealth(System.nanoTime())
                                configSequence = send(ports.control, 1, 24, kind = 1)
                                val zeros = Fields((1..4).associateWith { FieldValue.Varint(0uL) }).encode()
                                send(ports.control, 1, 9, zeros)
                            }
                            ports.sensor -> {
                                ciphers[event.service] = SecureRecord(key); readers[event.service] = FrameReader()
                                reportSensorState()
                            }
                            ports.cert, ports.media -> { ciphers[event.service] = SecureRecord(key); readers[event.service] = FrameReader() }
                            ports.rtsp -> rtspWrite(event.socket, wfd.start())
                        }
                    }
                    is Event.Bytes -> when (event.service) {
                        ports.rtsp -> {
                            for (message in rtspReader.feed(event.bytes)) {
                                rtspEvent("RX", message)
                                val responses = wfd.handle(message)
                                responses.forEach {
                                    if (trace !== ClinkTrace.NONE && it.line.startsWith("SET_PARAMETER") && it.body.contains("wfd_video_formats:"))
                                        trace.emit(ClinkTraceEvent.Link(System.nanoTime(), traceSession, "hevc_m4", mapOf(
                                            "mode" to hevc.name, "peer" to (wfd.iccoaSupportHevc ?: "absent"),
                                            "variant" to if (wfd.hevcEnabled && hevc == ClinkHevcMode.SIGNAL) "signal" else "unchanged")))
                                    rtspWrite(sockets.getValue(ports.rtsp), it)
                                }
                                mode = wfd.mode; destinationPort = wfd.rtpPort; selectedAudio = wfd.audio
                                reportCapabilities(); updatePlay()
                                wfd.takeReports().forEach(listener::diagnostic)
                                if (trace !== ClinkTrace.NONE && (message.body.contains("wfd_video_formats:") || message.line.startsWith("SETUP ")))
                                    trace.emit(ClinkTraceEvent.Negotiation(System.nanoTime(), traceSession, mode.toString(),
                                        wfd.decoders.map { it.codecString }, selectedAudio, destinationPort, display.toString()))
                                if (wfd.takeKeyframe()) {
                                    if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), traceSession,
                                        "keyframe_request", if (message.body.contains("wfd_idr_request")) "head_unit_rtsp" else "internal"))
                                    onKeyframe()
                                }
                                if (message.line.startsWith("TEARDOWN ") && responses.firstOrNull()?.line == "RTSP/1.0 200 OK") error("rtsp_teardown")
                            }
                        }
                        ports.uibc -> { check(certified); inputCipher.feed(event.bytes).forEach { touch.decode(it)?.let(onControl) } }
                        ports.sensor -> {
                            if (sockets.containsKey(ports.sensor)) {
                                try {
                                    readers.getValue(ports.sensor).feed(event.bytes, ::malformedSensor).forEach {
                                        try { handleFrame(ports.sensor, it) } catch (failure: Exception) { malformedSensor(failure) }
                                    }
                                } catch (failure: Exception) {
                                    // A corrupt length loses stream boundaries. Close only SENSOR, preserving projection.
                                    malformedSensor(failure)
                                    sockets.remove(ports.sensor)?.let { it.close(); socketEvent(ports.sensor, "CLOSED", it.inetAddress.hostAddress) }
                                    readers.remove(ports.sensor); ciphers.remove(ports.sensor)
                                    reportSensorState()
                                }
                            }
                        }
                        else -> readers.getValue(event.service).feed(event.bytes).forEach { handleFrame(event.service, it) }
                    }
                    is Event.Closed -> {
                        if (event.service == ports.sensor) {
                            if ((readers.remove(ports.sensor)?.pendingBytes ?: 0) > 0) malformedSensor(EOFException("truncated_sensor_frame"))
                            sockets.remove(ports.sensor); ciphers.remove(ports.sensor); accepted.remove(ports.sensor)
                            reportSensorState()
                        } else if (event.service != ports.cert || !certified) throw event.cause ?: EOFException("vehicle_channel_closed")
                    }
                    is Event.Lost -> throw event.cause ?: IllegalStateException("link_lost")
                    null -> Unit
                }
                val now = System.nanoTime()
                if (sockets.containsKey(ports.control)) updateLink(health, now, false)
                if (sockets.containsKey(ports.control) && now >= nextHeartbeat) {
                    send(ports.control, 1, 1, Fields(mapOf(1 to FieldValue.Varint(System.currentTimeMillis().toULong()))).encode())
                    nextHeartbeat = now + LinkHealth.HEARTBEAT_INTERVAL_NS
                }
                updateMicrophone()
                if (playing) startedPlaying = true
                check(startedPlaying || now < negotiationDeadline) { "projection_timeout" }
            }
        } catch (e: Exception) {
            if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Error(System.nanoTime(), traceSession, "session_failed", e.javaClass.name, "ClinkSession.run"))
            if (!stopped) listener.error(e)
        }
        finally {
            runCatching { microphone(null); updateMicrophone() }.onFailure { if (!stopped) listener.error(it) }
            stopped = true; playing = false; ready = false
            acknowledgements.values.forEach { it.done.countDown() }
            closeResources(); if (vehicleData) reportSensorState(); onPlay(false)
            if (::touch.isInitialized) onControl(touch.release())
            runCatching { link.disconnect() }.onFailure(listener::error)
            listener.state(ClinkState.ENDED)
        }
    }
    /** Called by one RTP writer thread only. TCP writes are bounded by the session watchdog. */
    public fun sendRtp(framedPacket: ByteArray): Boolean = sendRtp(framedPacket, 0, framedPacket.size)
    /** Writes one framed packet held at [offset] in a reused buffer; same contract as the array overload. */
    internal fun sendRtp(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (!isPlaying || isStalled) return false
        try {
            if (video != null && connectedVideoPort != destinationPort) {
                video?.let { it.close(); socketEvent(connectedVideoPort, "CLOSED", it.inetAddress.hostAddress, name = "rtp") }; video = null
            }
            val socket = video ?: dial(destinationPort).also { video = it; connectedVideoPort = destinationPort }
            write(socket, buffer, offset, length)
            return true
        } catch (e: Exception) {
            if (!isPlaying) return false // Teardown may close a socket during the media worker's write.
            throw e
        }
    }
    private fun now(): Long = (System.currentTimeMillis() / 1000) and 0xffffffffL
    private fun ipv4(value: String): InetAddress {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 })
        return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
    }
}

/**
 * The head unit's decoder is set up for the negotiated WFD mode and starts on the mode-sized
 * placeholder, while its control channel reports the screen area. A real car (screen 1156x764, mode
 * 1280x720) stayed black with any other stream size, so CarPlay renders exactly at the mode and the
 * head unit scales it onto its screen.
 */
internal fun canvas(screen: DisplayInfo, modeWidth: Int, modeHeight: Int): DisplayInfo {
    if (modeWidth <= 0 || modeHeight <= 0 || (screen.widthPx == modeWidth && screen.heightPx == modeHeight)) return screen
    val a = screen.safeArea
    fun x(value: Int) = (value.toLong() * modeWidth / screen.widthPx).toInt()
    fun y(value: Int) = (value.toLong() * modeHeight / screen.heightPx).toInt()
    return screen.copy(widthPx = modeWidth, heightPx = modeHeight, safeArea = SafeArea(x(a.left), y(a.top), x(a.right), y(a.bottom)))
}
