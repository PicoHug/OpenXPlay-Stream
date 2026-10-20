// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.openxplay.stream.clink.protocol.*
import org.openxplay.stream.clink.wfd.*
import org.openxplay.stream.core.DisplayInfo

/** Loopback head unit that simulates vehicle and projection behavior for tests.
 * AUTH proof/signing is independent of the phone implementation. Close joins every worker.
 */
public class SimulatedHeadUnit(
    public val ports: ClinkPorts,
    private val pin: String = "012345",
    private val capabilities: String = checkNotNull(SimulatedHeadUnit::class.java.getResourceAsStream("/head-unit-wfd.txt"))
        .use { it.bufferedReader().readText() }.replace(Regex("wfd_audio_codecs:[^\r\n]*"), "wfd_audio_codecs: AAC 00000001 00"),
    private val identity: AuthIdentity = AuthIdentity.generate(),
    private val pinnedPhone: ByteArray? = null,
    /** Called on workers after progress, including failures. Must return promptly and never throw. */
    private val onProgress: (SimulatedHeadUnit) -> Unit = {},
    private val sensorAttempts: Int = 100,
    private val sensorRetryMillis: Long = 110,
    private val sendVehicleSamples: Boolean = true,
) : Closeable {
    private val monitor = Object()
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val workers = CopyOnWriteArrayList<Thread>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val sequence = AtomicLong(1000)
    private val capture = TsCapture()
    @Volatile private var closed = false
    private val authServer = listen(ports.auth)
    private val rtpServer = listen(ports.rtp)
    @Volatile private var disconnecting = false
    @Volatile private var key = byteArrayOf()
    @Volatile private var sensor: Socket? = null
    @Volatile public var sensorConnected: Boolean = false
        private set
    @Volatile public var sensorFinishedDialing: Boolean = false
        private set
    public val sensorAcknowledgements: MutableList<Long> = CopyOnWriteArrayList()
    @Volatile private var control: Socket? = null
    @Volatile private var media: Socket? = null
    @Volatile private var rtsp: Socket? = null
    @Volatile private var uibc: Socket? = null
    @Volatile private var rtspSession = ""
    @Volatile public var authenticated: Boolean = false
        private set
    @Volatile public var phoneKey: ByteArray? = null
        private set
    @Volatile public var playing: Boolean = false
        private set
    @Volatile public var requestedUibcPort: Int = 0
        private set
    @Volatile public var requestedMediaPort: Int = 0
        private set
    @Volatile public var configurationAnswered: Boolean = false
        private set
    @Volatile public var heartbeats: Int = 0
        private set
    public data class AudioCommand(public val method: Int, public val fields: Fields, public val receivedNanos: Long)
    public data class VoiceFrame(public val data: ByteArray, public val kind: Int, public val receivedNanos: Long)
    public val audioCommands: MutableList<AudioCommand> = CopyOnWriteArrayList()
    public val voiceFrames: MutableList<VoiceFrame> = CopyOnWriteArrayList()
    @Volatile public var tailAckDelayMillis: Long = 0
    /** While true the RTP reader stops reading, so the relay's TCP send stalls once socket buffers fill. */
    @Volatile public var rtpPaused: Boolean = false
    @Volatile public var tailAckNanos: Long = 0
    @Volatile public var selectedAudio: String = ""
    public val audioUnits: List<CapturedAudioUnit> get() = capture.audio.toList()
    public fun awakenVoiceAssistant(raw: Boolean = false): Unit =
        send(checkNotNull(control), 1, 16, byteArrayOf(1, 2, 3, 4), format = if (raw) 0 else 1)
    public fun microphone(data: ByteArray): Unit = send(checkNotNull(media), 4, 9, data, format = 0)
    public val peerKey: ByteArray get() = identity.publicKey
    public val accessUnits: List<CapturedAccessUnit> get() = capture.units.toList()
    public val patCount: Int get() = capture.patCount
    public val pmtCount: Int get() = capture.pmtCount
    public val pcrCount: Int get() = capture.pcrCount

    init {
        require(sensorAttempts in 1..100 && sensorRetryMillis in 0..110)
        worker("auth") { authenticate(authServer.accept().also { track(it) }) }
        worker("rtp") {
            val socket = rtpServer.accept().also { track(it) }
            val input = DataInputStream(socket.getInputStream())
            while (!closed) {
                if (rtpPaused) { Thread.sleep(5); continue }
                val size = input.readUnsignedShort()
                require(size in 12..(12 + 7 * 188))
                val packet = ByteArray(size); input.readFully(packet)
                capture.packet(packet); changed()
            }
        }
    }

    /** Waits on socket progress and propagates worker failures to the test thread. */
    public fun await(description: String, timeoutMillis: Long = 3000, condition: () -> Boolean): Unit {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(monitor) {
            while (true) {
                assertHealthy()
                if (condition()) return
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Timed out: $description" }
                monitor.wait(maxOf(1, remaining / 1_000_000))
            }
        }
    }
    public fun assertHealthy(): Unit {
        failures.firstOrNull()?.let { throw AssertionError("Simulated head unit worker failed", it) }
    }
    /** Pixel coordinates are already in the negotiated source-video space. */
    public fun touch(action: Int, contacts: List<PixelContact>, width: Int = 1280, height: Int = 720): Unit {
        val points = contacts.mapIndexed { n, p -> "\"trackID$n\":${p.trackId},\"x$n\":${p.x},\"y$n\":${p.y}" }
        input("{\"type\":1,\"action\":$action,\"count\":${contacts.size},\"width\":$width,\"height\":$height" +
            (if (points.isEmpty()) "" else "," + points.joinToString(",")) + "}")
    }
    public fun key(androidCode: Int, action: Int): Unit =
        input("{\"type\":2,\"keycode\":$androidCode,\"action\":$action,\"metaState\":0}")
    public fun requestKeyframe(): Unit {
        check(playing)
        write(checkNotNull(rtsp), RtspMessage("SET_PARAMETER * RTSP/1.0",
            mapOf("cseq" to sequence.incrementAndGet().toString(), "session" to rtspSession), "wfd_idr_request\r\n").encode())
    }
    /** Arms intentional relay teardown without hiding failures earlier in negotiation. */
    public fun expectRelayClose(): Unit { disconnecting = true }
    public fun disconnect(): Unit {
        disconnecting = true
        send(checkNotNull(control), 1, 23)
    }
    private fun input(json: String) {
        write(checkNotNull(uibc), SecureRecord(key).seal(json.toByteArray()))
    }
    private fun changed() {
        synchronized(monitor) { monitor.notifyAll() }
        onProgress(this)
    }
    private fun <T : Closeable> track(resource: T): T {
        synchronized(resources) {
            if (closed) { resource.close(); error("Head unit closed") }
            resources += resource
        }
        return resource
    }
    private fun listen(port: Int): ServerSocket = ServerSocket().also {
        track(it); it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port))
    }
    private fun dial(port: Int): Socket = Socket().also {
        track(it); it.connect(InetSocketAddress("127.0.0.1", port), 2000); it.tcpNoDelay = true
    }
    private fun worker(name: String, block: () -> Unit) {
        val thread = Thread({
            try { block() } catch (error: Throwable) {
                if (!closed && !disconnecting) failures += error
            } finally { changed() }
        }, "head-unit-$name").apply { isDaemon = true }
        synchronized(workers) {
            if (closed) return
            workers += thread; thread.start()
        }
    }
    private fun write(socket: Socket, bytes: ByteArray) {
        synchronized(socket) { socket.getOutputStream().write(bytes) }
    }
    private fun readFrame(input: DataInputStream): Frame {
        val size = input.readInt(); require(size in Frame.HEADER_BYTES..Frame.MAX_BYTES)
        val rest = ByteArray(size - 4); input.readFully(rest)
        return Frame.decode(java.nio.ByteBuffer.allocate(size).putInt(size).put(rest).array())
    }
    private fun send(socket: Socket, category: Int, method: Int, body: ByteArray = byteArrayOf(),
        kind: Int = 0, seq: Long = sequence.incrementAndGet(), secure: Boolean = true, format: Int = 1) {
        write(socket, Frame(seq, System.currentTimeMillis() / 1000, 0, format, kind, category, method,
            if (secure) SecureRecord(key).seal(body) else body).encode())
    }
    private fun authenticate(socket: Socket) {
        socket.soTimeout = 3000
        val input = DataInputStream(socket.getInputStream())
        val request = readFrame(input)
        require(request.source == 1 && request.format == 1 && request.category == 3 && request.method == 1 && request.kind == 1)
        val f = Fields.decode(request.body); require(f.number(1) == 1uL && f.number(9) <= 1uL)
        require(f.bytes(7).isNotEmpty() && f.bytes(8).isNotEmpty())
        val agreement = f.bytes(4, 64); val nonce = f.bytes(6, 32)
        val phone = if (2 in f.numbers) f.bytes(2, 64).also {
            require(MessageDigest.isEqual(f.bytes(3, 32), proof(pin.toByteArray(), nonce, it))) { "m1 PIN HMAC" }
            require(pinnedPhone == null || MessageDigest.isEqual(it, pinnedPhone))
        } else checkNotNull(pinnedPhone) { "Quick auth without a pinned phone" }
        require(verify(phone, agreement + nonce, f.bytes(5))) { "m1 signature" }
        val ephemeral = pair(); val carAgreement = raw(ephemeral)
        val carNonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val carIdentity = identity.publicKey
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(identity.pkcs8)))
            update(carAgreement + agreement + carNonce + nonce); sign()
        }
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private); doPhase(publicKey(agreement), true); generateSecret()
        }
        key = hmac(hmac(hash(nonce + carNonce), shared), "session_key".toByteArray() + byteArrayOf(1)).copyOf(16)
        val response = Fields(mapOf(1 to FieldValue.Varint(0x0901uL), 2 to FieldValue.Bytes(carIdentity),
            3 to FieldValue.Bytes(proof(pin.toByteArray(), carNonce + nonce, carIdentity + phone)),
            4 to FieldValue.Bytes(carAgreement), 5 to FieldValue.Bytes(signature),
            6 to FieldValue.Bytes(carNonce), 7 to FieldValue.Varint(0uL))).encode()
        send(socket, 3, 2, response, 2, request.sequence, secure = false)
        val confirm = readFrame(input)
        require(confirm.source == 1 && confirm.format == 1 && confirm.kind == 0 && confirm.category == 3 &&
            confirm.method == 3 && confirm.sequence == request.sequence + 1)
        require(SecureRecord(key).open(Fields.decode(confirm.body).bytes(1)).toString(Charsets.UTF_8) == "127.0.0.1")
        phoneKey = phone.copyOf(); authenticated = true; socket.close(); changed()
        val command = dial(ports.control); control = command
        worker("sensor") { sensorChannel() }
        media = dial(ports.media)
        worker("media") { audio(checkNotNull(media)) }
        val cert = dial(ports.cert)
        send(cert, 6, 1, certificate())
        // Retain CERT until cleanup: control and UIBC must only follow its acceptance.
        uibc = dial(ports.uibc)
        rtsp = dial(ports.rtsp)
        send(command, 1, 24, Control.displayBody(DisplayInfo(1280, 720, 160, 30.0)), 1, 700)
        send(command, 1, 2, Fields(mapOf(1 to FieldValue.Varint(3uL))).encode(), 1, 701)
        send(command, 1, 2, Fields(mapOf(1 to FieldValue.Varint(2uL))).encode(), 1, 702)
        worker("control") { control(command) }
        worker("rtsp") { projection(checkNotNull(rtsp)) }
    }
    /** Sends one raw SENSOR body, permitting malformed payloads and either push kind in tests. */
    public fun sensorFrame(method: Int, body: ByteArray, kind: Int = 0): Unit =
        send(checkNotNull(sensor), 2, method, body, kind)
    /** Sends complete wire bytes for framing and encryption fault tests. */
    public fun sensorBytes(bytes: ByteArray): Unit = write(checkNotNull(sensor), bytes)
    public fun closeSensor(): Unit { sensor?.close() }
    /** The car's screen notices: method 4 or 5 with its clock and RTSP port, as a real car sends them. */
    public fun screenNotice(method: Int): Unit = send(checkNotNull(control), 1, method, Fields(mapOf(
        1 to FieldValue.Varint(System.currentTimeMillis().toULong()), 2 to FieldValue.Varint(7236uL))).encode())
    public fun dayNight(mode: Int, kind: Int = 0): Unit =
        send(checkNotNull(control), 1, 15, Fields(mapOf(1 to FieldValue.Varint(mode.toULong()))).encode(), kind)
    /** Deterministic samples preserve raw integers and doubles without assuming any units. */
    public fun vehicleSamples(): Unit {
        fun numbers(vararg values: Pair<Int, ULong>): ByteArray =
            Fields(values.associate { it.first to FieldValue.Varint(it.second) }).encode()
        fun double(value: Double): FieldValue = FieldValue.Bytes(java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(value).array(), 1)
        sensorFrame(1, numbers(1 to (-12L).toULong(), 2 to 45uL, 3 to (-123L).toULong(),
            4 to 1uL, 5 to 9uL, 6 to 72uL, 7 to 180uL, 8 to 8uL, 9 to 12uL, 10 to 1_700_000_000_123uL))
        sensorFrame(2, numbers(1 to 1uL, 2 to 0uL, 3 to 1uL, 4 to 0uL, 5 to 1uL))
        sensorFrame(3, Fields(mapOf(1 to FieldValue.Varint(2uL), 2 to double(-12.5), 3 to double(3.25),
            4 to double(270.5), 5 to FieldValue.Varint(1_700_000_000_124uL))).encode())
        sensorFrame(4, Fields(mapOf(1 to double(-1.5), 2 to double(2.25), 3 to double(9.75),
            4 to FieldValue.Varint(1_700_000_000_125uL))).encode())
        sensorFrame(5, numbers(1 to 100uL, 2 to 42uL))
        sensorFrame(6, numbers(1 to 2uL, 2 to 72uL), kind = 3)
        sensorFrame(7, Fields(mapOf(1 to double(1000.5), 2 to double(0.25), 3 to double(50.75))).encode())
        dayNight(2, kind = 3)
    }
    private fun sensorChannel() {
        var connection: Socket? = null
        try {
            repeat(sensorAttempts) { attempt ->
                if (closed || disconnecting || connection != null) return@repeat
                val socket = Socket()
                try {
                    track(socket)
                    socket.connect(InetSocketAddress("127.0.0.1", ports.sensor), 100)
                    socket.tcpNoDelay = true; connection = socket
                } catch (_: java.io.IOException) { socket.close() }
                if (connection == null && attempt + 1 < sensorAttempts) Thread.sleep(sensorRetryMillis)
            }
            sensor = connection; sensorConnected = connection != null
        } finally { sensorFinishedDialing = true; changed() }
        val socket = connection ?: return
        if (sendVehicleSamples) vehicleSamples()
        val input = DataInputStream(socket.getInputStream()); val cipher = SecureRecord(key)
        try {
            while (!closed && !disconnecting) {
                val frame = readFrame(input)
                require(frame.source == 1 && frame.category == 7 && frame.format == 0 && frame.kind == 2 &&
                    cipher.open(frame.body).isEmpty())
                sensorAcknowledgements += frame.sequence; changed()
            }
        } catch (_: java.io.IOException) {
            // SENSOR can be closed independently while projection continues.
        } finally { sensorConnected = false; socket.close(); changed() }
    }
    private fun control(socket: Socket) {
        val input = DataInputStream(socket.getInputStream()); val cipher = SecureRecord(key)
        while (!closed) {
            val frame = readFrame(input)
            if (frame.category == 7) { require(frame.format == 0 && frame.kind == 2 && cipher.open(frame.body).isEmpty()); continue }
            require(frame.source == 1 && frame.category == 1 && frame.format == 1)
            val body = cipher.open(frame.body)
            when (frame.method) {
                6, 8 -> {
                    require(frame.kind == 3)
                    val fields = Fields.decode(body)
                    audioCommands += AudioCommand(frame.method, fields, System.nanoTime())
                    send(socket, 7, 0, kind = 2, seq = frame.sequence, format = 0)
                    if (frame.method == 6 && fields.number(1) == 1uL) {
                        val size = fields.number(2).toInt() / 50 * fields.number(3).toInt() * 2
                        microphone(ByteArray(size) { (it % 127).toByte() })
                    }
                }
                1 -> { Fields.decode(body); send(socket, 1, 1, body); heartbeats++ }
                24 -> send(socket, 1, 25, Control.displayBody(DisplayInfo(1280, 720, 160, 30.0)), 2, frame.sequence)
                25 -> { require(frame.kind == 2 && frame.sequence == 700L); configurationAnswered = true }
                3 -> {
                    val f = Fields.decode(body)
                    require(frame.kind == 2 && f.number(1) == 1uL)
                    when (frame.sequence) {
                        701L -> { requestedUibcPort = f.number(2).toInt(); require(requestedUibcPort == ports.uibc) }
                        702L -> { requestedMediaPort = f.number(2).toInt(); require(requestedMediaPort == ports.media) }
                        else -> error("Unexpected server response")
                    }
                }
            }
            changed()
        }
    }
    private fun audio(socket: Socket) {
        val input = DataInputStream(socket.getInputStream()); val cipher = SecureRecord(key)
        while (!closed) {
            val frame = readFrame(input)
            require(frame.source == 1 && frame.category == 4 && frame.method == 3 && frame.format == 0 && frame.kind in listOf(0, 3))
            val data = cipher.open(frame.body)
            require(data.isNotEmpty() && data.size % 2 == 0 && data.size <= 3200)
            voiceFrames += VoiceFrame(data, frame.kind, System.nanoTime()); changed()
            if (frame.kind == 3) {
                if (tailAckDelayMillis > 0) Thread.sleep(tailAckDelayMillis)
                tailAckNanos = System.nanoTime()
                send(socket, 7, 0, kind = 2, seq = frame.sequence, format = 0); changed()
            }
        }
    }
    private fun projection(socket: Socket) {
        val reader = RtspReader(); val buffer = ByteArray(4096)
        val url = "rtsp://127.0.0.1:${ports.rtsp}/wfd1.0/streamid=0"
        while (!closed) {
            val n = socket.getInputStream().read(buffer); check(n >= 0) { "RTSP closed" }
            for (message in reader.feed(buffer.copyOf(n))) {
                val seq = checkNotNull(message.headers["cseq"])
                if (message.line.startsWith("RTSP/")) {
                    require(message.line == "RTSP/1.0 200 OK")
                    when (seq) {
                        "900" -> {
                            require(message.headers["transport"].orEmpty().contains("client_port=${ports.rtp}"))
                            rtspSession = checkNotNull(message.headers["session"]).substringBefore(';')
                            write(socket, RtspMessage("PLAY $url RTSP/1.0", mapOf("cseq" to "901", "session" to rtspSession)).encode())
                        }
                        "901" -> { playing = true; changed() }
                    }
                    continue
                }
                if (message.body.contains("wfd_audio_codecs:")) selectedAudio = parameters(message.body)["wfd_audio_codecs"].orEmpty()
                val method = message.line.substringBefore(' ')
                require(method in setOf("OPTIONS", "GET_PARAMETER", "SET_PARAMETER"))
                val headers = mapOf("cseq" to seq, "public" to "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY")
                val body = if (method == "GET_PARAMETER") capabilities.lineSequence().filter { it.isNotBlank() }
                    .joinToString("\r\n", postfix = "\r\n")
                    .replace("unicast 19000 0", "unicast ${ports.rtp} 0") else ""
                write(socket, RtspMessage("RTSP/1.0 200 OK", headers, body).encode())
                if (message.body.contains("wfd_trigger_method: SETUP")) {
                    write(socket, RtspMessage("SETUP $url RTSP/1.0", mapOf("cseq" to "900",
                        "transport" to "RTP/AVP/TCP;unicast;client_port=${ports.rtp}")).encode())
                }
            }
        }
    }
    override fun close() {
        synchronized(resources) {
            closed = true
            resources.forEach { runCatching { it.close() } }
        }
        val deadline = System.nanoTime() + 2_000_000_000
        workers.forEach { it.join(maxOf(1, (deadline - System.nanoTime()) / 1_000_000)) }
        check(workers.none { it.isAlive }) { "Head unit worker leaked" }
        assertHealthy()
    }
    private fun certificate(): ByteArray {
        val content = "{\"short_name\":\"loopback\",\"product_name\":\"Simulator\",\"vendor_name\":\"OpenXPlay\",\"vendor_id\":\"1\",\"product_id\":\"1\"}"
            .toByteArray().joinToString(",") { it.toString() }
        val json = "{\"content\":[$content],\"signature\":[${List(256) { "0" }.joinToString(",")}]," +
            "\"signAlgorithm\":\"SHA256withRSA\",\"version\":1,\"cryptoConfigIndex\":\"loopback\"}"
        return Fields(mapOf(1 to FieldValue.Bytes(Base64.getEncoder().encode(json.toByteArray())))).encode()
    }
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private fun raw(pair: KeyPair): ByteArray {
        val point = (pair.public as ECPublicKey).w
        fun coordinate(value: BigInteger): ByteArray = value.toByteArray().let { bytes ->
            ByteArray(32).also { bytes.copyInto(it, maxOf(0, 32 - bytes.size), maxOf(0, bytes.size - 32)) }
        }
        return coordinate(point.affineX) + coordinate(point.affineY)
    }
    private fun publicKey(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 64)
        val params = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(0, 32)), BigInteger(1, raw.copyOfRange(32, 64))), params))
    }
    private fun verify(raw: ByteArray, transcript: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(raw)); update(transcript); verify(signature) }
    private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
    private fun proof(pin: ByteArray, nonce: ByteArray, bytes: ByteArray): ByteArray = hmac(hash(nonce + pin), bytes)
}

public data class PixelContact(public val trackId: Long, public val x: Int, public val y: Int)
