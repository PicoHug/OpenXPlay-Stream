// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import org.openxplay.stream.clink.trace.*
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import org.openxplay.stream.clink.media.RtpPacketizer
import org.openxplay.stream.clink.media.RtpSender
import org.openxplay.stream.core.*
import org.openxplay.stream.core.audio.Adts
import org.openxplay.stream.core.audio.AudioSpecificConfig
import org.openxplay.stream.core.codec.AvcConfig
import org.openxplay.stream.core.codec.Nal
import org.openxplay.stream.core.codec.TsMuxer

/**
 * Interactive Carlink destination. Callbacks retain shared arrays in bounded queues; the media worker
 * never writes to a socket itself (see [RtpSender]).
 * The executor must dispatch asynchronously; socket workers use the supplied thread factory.
 * Reopen after close only once the previous lifecycle has reported ENDED.
 */
public class ClinkOutput(
    private val link: ClinkLink,
    private val pinProvider: PinProvider,
    private val trustStore: TrustStore,
    private val placeholder: PlaceholderVideo = BundledPlaceholderVideo(),
    private val ports: ClinkPorts = ClinkPorts(),
    private val threadFactory: ThreadFactory = ThreadFactory { task -> Thread(task).apply { isDaemon = true } },
    private val executor: Executor = Executor { task -> threadFactory.newThread(task).start() },
    private val listener: ClinkListener = object : ClinkListener {},
    override val id: String = "clink",
    private val deviceId: String = "openxplay-stream",
    private val deviceName: String = "OpenXPlay",
    /** Opt-in vehicle collection. False leaves the SENSOR port unbound and suppresses day/night. */
    private val vehicleData: Boolean = false,
    private val trace: ClinkTrace = ClinkTrace.NONE,
    private val mediaTap: ClinkMediaTap? = null,
) : ProjectionOutput {
    private class Run(val host: OutputHost) {
        val traceSession = ClinkSession.nextTraceSession()
        val active = AtomicBoolean(true)
        val rtpPackets = AtomicLong()
        val rtpBytes = AtomicLong()
        val queue = VideoQueue()
        val voice = VoiceDelivery()
        val audio = ArrayDeque<AudioPacket>()
        var audioBytes = 0
        var audioDrops = 0L
        val sender = RtpSender()
        var mediaStream: Int? = null
        val mediaFormats = linkedMapOf<Int, AudioFormat>()
        val voiceStreams = mutableSetOf<Int>()
        var microphone: MicRequest? = null
        val diagnostics = ArrayDeque<String>()
        val lock = Any()
        @Volatile var session: ClinkSession? = null
        var inputSession: SessionId? = null
        var format: VideoFormat? = null
        var revision = 0L
        var playing = false
        var requestKeyframe = false
    }
    @Volatile private var current: Run? = null

    /**
     * Only speech and phone calls may open the vehicle voice channel. System sounds (keyboard
     * clicks, alerts) are mixed by a real head unit; sending them here makes Carlink duck or pause
     * the media channel (2026-10-19).
     */
    private fun voiceRole(role: AudioRole): Boolean = role == AudioRole.VOICE || role == AudioRole.TELEPHONY
    private val opened = AtomicBoolean(false)
    override fun open(host: OutputHost) {
        check(opened.compareAndSet(false, true)) { "output_already_open" }
        val run = Run(host); current = run
        try {
            threadFactory.newThread {
                try { run.voice.run({ run.active.get() }, { run.session }, { packet ->
                    synchronized(run.lock) { packet.session == run.inputSession }
                }) } catch (e: Exception) {
                    if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Error(System.nanoTime(), run.traceSession, "output_failed", e.javaClass.name, "ClinkOutput"))
                    if (run.active.get()) listener.error(e)
                    run.session?.stop()
                }
            }.apply { name = "clink-voice"; start() }
            threadFactory.newThread { writer(run) }.apply { name = "clink-rtp"; start() }
            threadFactory.newThread { media(run) }.apply { name = "clink-media"; start() }
            executor.execute {
                try {
                    // Each open owns one fresh network session. The shell may close/open to reconnect.
                    if (run.active.get()) {
                        val sessionListener = object : ClinkListener {
                            override fun state(state: ClinkState) {
                                if (state != ClinkState.ENDED) listener.state(state)
                            }
                            override fun error(error: Throwable) { listener.error(error) }
                            override fun diagnostic(message: String) { listener.diagnostic(message) }
                            override fun vehicleState(sensorActive: Boolean, malformedFrames: Long) {
                                listener.vehicleState(sensorActive, malformedFrames)
                            }
                        }
                        val session = ClinkSession(link, pinProvider, trustStore, ports, threadFactory, id, sessionListener,
                            { caps -> withHost(run) { it.capabilities(caps.copy(decoders = caps.decoders.filter { decoder -> decoder.codecString.startsWith("avc1.") })) } },
                            { event -> withHost(run) { host ->
                                if (event !is ControlEvent.MicPacket || run.microphone?.stream == event.stream) host.control(event)
                            } },
                            { playing -> synchronized(run.lock) {
                                run.playing = playing; run.queue.reset(); run.revision++
                                if (playing) run.requestKeyframe = true
                            } },
                            { synchronized(run.lock) { run.requestKeyframe = true } }, deviceId, deviceName, vehicleData,
                            { data -> withHost(run) { host -> host.vehicle(data, Origin(id, run.session?.headUnitId.orEmpty())) } },
                            { stalled -> synchronized(run.lock) {
                                run.queue.setStalled(stalled); run.revision++
                                run.audio.clear(); run.audioBytes = 0
                            } }, trace, run.traceSession)
                        run.session = session
                        synchronized(run.lock) { session.microphone(run.microphone) }
                        if (!run.active.get()) session.stop()
                        session.run()
                    }
                } finally {
                    synchronized(run.lock) { run.active.set(false) }
                    if (current === run) current = null
                    opened.set(false)
                    listener.state(ClinkState.ENDED)
                }
            }
        } catch (e: RuntimeException) {
            run.active.set(false); run.session?.stop()
            if (current === run) current = null
            opened.set(false)
            throw e
        }
    }
    override fun onSession(state: SessionState, config: SessionConfig) {
        val run = current ?: return
        synchronized(run.lock) {
            if (run.inputSession != state.id || state.phase != SessionPhase.ACTIVE) {
                run.inputSession = if (state.phase == SessionPhase.ENDED) null else state.id
                if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), run.traceSession, "reset", "input_session"))
                run.queue.reset(); run.revision++; run.requestKeyframe = true
                run.audio.clear(); run.audioBytes = 0; run.mediaStream = null; run.mediaFormats.clear(); run.voiceStreams.clear(); run.voice.reset()
                run.microphone = null; run.session?.microphone(null)
            }
        }
    }
    override fun onVideoFormat(screen: ScreenId, format: VideoFormat?) {
        if (screen != ScreenId.MAIN) return
        val run = current ?: return
        synchronized(run.lock) {
            if (!sameVideoFormat(run.format, format)) {
                if (trace !== ClinkTrace.NONE) {
                    val config = if (format?.codec == VideoCodec.H264) runCatching { AvcConfig.parse(format.config) }.getOrNull() else null
                    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                    trace.emit(ClinkTraceEvent.Video(System.nanoTime(), run.traceSession, "format", codec = format?.codecString,
                        width = format?.width, height = format?.height, sps = config?.sps?.map(::hex).orEmpty(), pps = config?.pps?.map(::hex).orEmpty()))
                }
                run.format = format; run.queue.reset(); run.revision++; run.requestKeyframe = true
            }
        }
    }
    override fun onVideoFrame(frame: VideoFrame) {
        if (frame.screen != ScreenId.MAIN) return
        val run = current ?: return
        synchronized(run.lock) {
            if (!run.active.get() || !run.playing || frame.session != run.inputSession || run.format == null) return
            if (run.queue.offer(frame)) run.requestKeyframe = true
        }
    }
    override fun onAudioFormat(stream: Int, format: AudioFormat?) {
        val run = current ?: return
        synchronized(run.lock) {
            if (!run.active.get()) return
            val previous = run.mediaStream
            run.mediaFormats.remove(stream)
            if (format?.role == AudioRole.MEDIA) {
                val supported = format.codec == AudioCodec.AAC_LC && format.sampleRate == 48000 && format.channels == 2 &&
                    format.config?.contentEquals(byteArrayOf(0x11, 0x90.toByte())) == true
                if (supported) run.mediaFormats[stream] = format
                else if (run.diagnostics.size < 31) {
                    run.diagnostics.addLast("unsupported_media_audio:$stream")
                    run.diagnostics.addLast("audio unsupported_media stream=$stream codec=${format.codec} " +
                        "rate=${format.sampleRate} channels=${format.channels} config=${format.config?.joinToString("") { "%02x".format(it) }}")
                }
            }
            run.mediaStream = run.mediaFormats.keys.lastOrNull()
            if (previous != run.mediaStream || previous == stream) { run.audio.clear(); run.audioBytes = 0 }
            run.voiceStreams.remove(stream)
            if (format != null && voiceRole(format.role) && format.codec == AudioCodec.PCM_S16LE &&
                format.sampleRate in 8000..48000 && format.channels in 1..2) run.voiceStreams.add(stream)
            run.voice.format(stream, format?.takeIf { voiceRole(it.role) })
        }
    }
    override fun onAudioPacket(packet: AudioPacket) {
        val run = current ?: return
        synchronized(run.lock) {
            if (!run.active.get() || packet.session != run.inputSession) return
            if (packet.stream == run.mediaStream) {
                if (packet.data.size !in 1..8184) return
                while (run.audio.isNotEmpty() && mediaQueueFull(run.audio.size, run.audioBytes + packet.data.size,
                        mediaQueueSpanUs(packet.ptsUs, run.audio.first.ptsUs))) {
                    run.audioBytes -= run.audio.removeFirst().data.size; run.audioDrops++
                }
                run.audio.addLast(packet); run.audioBytes += packet.data.size
            } else if (packet.stream in run.voiceStreams) run.voice.packet(packet)
        }
    }
    override fun onMicrophone(stream: Int, request: MicRequest?) {
        val run = current ?: return
        synchronized(run.lock) {
            if (!run.active.get()) return
            require(request == null || request.stream == stream && request.codec == AudioCodec.PCM_S16LE &&
                request.sampleRate in 8000..48000 && request.channels in 1..2 && request.frameMs == 20 && request.sampleRate % 50 == 0)
            run.microphone = request; run.session?.microphone(request)
        }
    }
    override fun close() {
        val run = current ?: return
        synchronized(run.lock) { run.active.set(false) }
        run.session?.stop()
    }
    /**
     * The iPhone repeats an identical configuration with every keyframe, and a passthrough source
     * hands out a fresh [VideoFormat] instance each time. Comparing identities reset the queue,
     * bumped the revision and forced another keyframe every second (Carlink flicker, 2026-10-19),
     * so compare the fields that actually change the encoded stream instead.
     */
    private fun sameVideoFormat(a: VideoFormat?, b: VideoFormat?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.codec == b.codec && a.codecString == b.codecString &&
            a.width == b.width && a.height == b.height && a.config.contentEquals(b.config)
    }

    /**
     * Never calls the host under [Run.lock]: the hub delivers frames into this output from threads that
     * hold an input's lock, so a host call made under our lock deadlocked with an input waiting for that
     * frame (2026-10-15). The hub fences the host of a removed output, so a call racing stop() is harmless.
     */
    private fun withHost(run: Run, call: (OutputHost) -> Unit) {
        if (synchronized(run.lock) { run.active.get() }) call(run.host)
    }
    private fun writer(run: Run) {
        try { run.sender.run { run.active.get() } }
        catch (e: Exception) {
            if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Error(System.nanoTime(), run.traceSession, "output_failed", e.javaClass.name, "ClinkOutput"))
            if (run.active.get()) listener.error(e)
            run.session?.stop(); run.active.set(false)
        }
    }
    /** Snapshot of what the media path retains, for diagnostics and tests; null when not open. */
    internal fun backlog(): MediaBacklog? {
        val run = current ?: return null
        val (rtpBytes, rtpInFlight) = run.sender.retained()
        val (voiceCommands, voiceBytes) = run.voice.queued()
        return synchronized(run.lock) {
            MediaBacklog(run.queue.size(), run.queue.bytes(), run.audio.size, run.audioBytes, voiceCommands, voiceBytes,
                rtpBytes, rtpInFlight)
        }
    }
    private enum class Emit { SENT, LATE, DROPPED }
    private fun media(run: Run) {
        try {
            val gate = VideoSwitch()
            var revision = -1L
            var generation: ClinkSession? = null
            var format: VideoFormat? = null
            var avc: AvcConfig? = null
            val placeholderConfig = AvcConfig.parse(placeholder.format.config)
            require(placeholder.frames.isNotEmpty() && placeholder.fps > 0 &&
                Nal.isKeyFrame(placeholder.frames.first(), VideoCodec.H264))
            var placeholderIndex = 0
            var nextPlaceholder = 0L
            var outputPts = -1L
            var keyframeRequestedAt = Long.MIN_VALUE / 2
            var keyframePending = false
            var keyframeSource = "internal"
            var audioReported: ClinkSession? = null
            var videoWrittenAt = 0L
            // Once live video has played, a reset freezes the last frame instead of flashing the
            // placeholder (2026-10-15); the placeholder returns only after a long gap.
            var everLive = false
            var liveFrameAt = 0L
            var reportedOverflows = 0L
            var reportedWaitingDrops = 0L
            var reportedAudioDrops = 0L
            var transportPts = -1L
            var liveOffset = 0L
            var audioOffset: Long? = null
            var liveFrames = 0L; var placeholderFrames = 0L; var keyframes = 0L; var videoBytes = 0L
            var sendStalls = 0L; var audioFrames = 0L; var failedVideo = 0L
            var lastAudioPts = -1L
            var clockAt = 0L
            var sent = 0L
            var reportedAt = 0L
            var mismatchReported: String? = null
            val rtp = RtpPacketizer()
            val sender = run.sender
            val unit = sender.unit
            // What the write still in flight after RtpSender.waitNs carries; its clocks restart when it lands.
            var lateLive = false
            var lateVideo = false
            val diagnostics = mutableListOf<String>()
            var muxer: TsMuxer? = null
            val aac = AudioSpecificConfig(2, 48000, 2)
            // The muxer streams TS packets straight into the reusable RTP unit; nothing is retained per packet.
            fun emit(session: ClinkSession, pts: Long, mux: () -> Unit): Emit {
                rtp.start(unit, pts); mux()
                if (!rtp.finish(unit)) {
                    listener.diagnostic("rtp unit_dropped limit=${unit.maxBytes}")
                    return Emit.DROPPED
                }
                if (sender.submit { bytes, offset, length ->
                    if (trace !== ClinkTrace.NONE) { run.rtpPackets.incrementAndGet(); run.rtpBytes.addAndGet((length - 2).toLong()) }
                    if (mediaTap != null) try { mediaTap.rtp(bytes, offset + 2, length - 2) } catch (_: Exception) { }
                    session.sendRtp(bytes, offset, length)
                }) return Emit.SENT
                if (trace !== ClinkTrace.NONE) sendStalls++
                listener.diagnostic("rtp send stalled ms=${sender.waitNs / 1_000_000}")
                return Emit.LATE
            }
            while (run.active.get()) {
                val session = run.session
                var frame: VideoFrame? = null
                var request = false
                // While a late write is in flight, media stays in the bounded queues (video drops to the next
                // keyframe, audio drops oldest) exactly as when this thread blocked in the socket write.
                var writable = sender.idle()
                sender.takeRecovered()?.let { ns ->
                    listener.diagnostic("rtp send recovered after ms=${ns / 1_000_000}")
                    val now = System.nanoTime()
                    if (lateVideo) videoWrittenAt = now
                    if (lateLive) liveFrameAt = now
                    lateVideo = false; lateLive = false
                }
                synchronized(run.lock) {
                    if (revision != run.revision || generation !== session) {
                        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), run.traceSession,
                            if (everLive) "freeze" else "reset", "revision"))
                        // Keep the audio anchor across a revision: the input clock did not restart, and
                        // zeroing it made every audio packet fall back to raw PTS and get dropped (the
                        // 47 -> 27 packets/s decay in the 2026-10-19 trace). Only a new session resets it.
                        gate.play(run.playing); placeholderIndex = 0; nextPlaceholder = 0
                        revision = run.revision
                        if (generation !== session) {
                            muxer = null; keyframeRequestedAt = Long.MIN_VALUE / 2; everLive = false; audioOffset = null
                        }
                        generation = session
                        if (format !== run.format) { format = run.format; avc = null; mismatchReported = null }
                    }
                    if (run.queue.takeGap()) {
                        if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), run.traceSession, "reset", "gap"))
                        if (trace !== ClinkTrace.NONE && gate.live) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), run.traceSession, "freeze", "gap"))
                        keyframeSource = "gap"
                        gate.reset(); placeholderIndex = 0; nextPlaceholder = 0
                    }
                    if (writable) frame = run.queue.poll()
                    while (run.diagnostics.isNotEmpty()) diagnostics += run.diagnostics.removeFirst()
                    request = run.requestKeyframe
                    if (session?.isPlaying == true && !session.isStalled) run.requestKeyframe = false
                }
                diagnostics.forEach { listener.diagnostic(it) }
                diagnostics.clear()
                if (session == null || !session.isPlaying || session.isStalled) { Thread.sleep(5); continue }
                if (audioReported !== session) {
                    audioReported = session
                    listener.diagnostic("audio negotiated media=${session.mediaAudio}")
                }
                require(session.videoSize == (placeholder.format.width to placeholder.format.height)) { "placeholder_timing_mismatch" }
                // No access unit delimiter: the head unit only recognises a keyframe when the access unit
                // begins with SPS (NAL 7) or an IDR slice (NAL 5), and an AUD in front of those made it
                // discard every frame — black picture with working audio (2026-10-19).
                val transport = muxer ?: TsMuxer({ rtp.append(unit, it) }, audioEnabled = session.mediaAudio,
                    insertAud = false).also { muxer = it }
                val origin = Origin(id, session.headUnitId)
                // At most one keyframe request per second per session: each one makes the phone send an IDR
                // frame. A request inside the interval, or during a stalled send, stays pending instead of being lost.
                if (request) keyframePending = true
                if (keyframePending && writable && System.nanoTime() - keyframeRequestedAt >= 1_000_000_000) {
                    keyframePending = false
                    keyframeRequestedAt = System.nanoTime()
                    if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), session.traceSession, "keyframe_request", keyframeSource))
                    keyframeSource = "internal"
                    withHost(run) { it.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)) }
                }
                val f = frame
                if (f != null && format?.codec == VideoCodec.H264) {
                    val config = avc ?: AvcConfig.parse(checkNotNull(format).config).also { avc = it }
                    val supportsProfile = session.decoders.any {
                        val profile = it.codecString.substringAfter("avc1.").take(2).toIntOrNull(16)
                        val level = it.codecString.takeLast(2).toIntOrNull(16) ?: 0
                        (profile == config.profile || profile == 100 && config.profile == 66) && level >= config.level
                    }
                    if (!supportsProfile && mismatchReported != format?.codecString) {
                        mismatchReported = format?.codecString
                        listener.profileMismatch(checkNotNull(format).codecString, session.decoders.map { it.codecString })
                    }
                    val wasLive = gate.live
                    if (gate.accept(f.keyFrame)) {
                        if (!wasLive) {
                            liveOffset = maxOf(0L, outputPts + 1_000_000 / placeholder.fps,
                                f.ptsUs + (audioOffset ?: (0L - f.ptsUs))) - f.ptsUs
                            // The absolute PTS decides whether the sink ever renders: a video timeline
                            // parked far ahead of the audio clock keeps the frames in the receiver's
                            // future, so bytes and counters climb while the screen stays black. Detect it
                            // once, at the transition, and re-anchor onto the shared clock.
                            val lead = maxOf(outputPts + 1, transportPts + 1, f.ptsUs + liveOffset) - transportPts
                            if (lead > MAX_LEAD_US) liveOffset -= lead - 1
                            if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Video(System.nanoTime(), session.traceSession,
                                if (lead > MAX_LEAD_US) "reanchor" else "live",
                                "in=${f.ptsUs} out=$outputPts tr=$transportPts off=$liveOffset aoff=${audioOffset ?: -1} lead=$lead"))
                        }
                        val pts = maxOf(outputPts + 1, transportPts + 1, f.ptsUs + liveOffset)
                        val result = emit(session, pts) { transport.writeVideo(f.data, config, pts, f.keyFrame) }
                        outputPts = pts; transportPts = pts
                        if (result == Emit.DROPPED) {
                            if (trace !== ClinkTrace.NONE) { failedVideo++; trace.emit(ClinkTraceEvent.Video(System.nanoTime(), session.traceSession, "freeze", "unit_dropped")) }
                            gate.reset(); keyframePending = true; keyframeSource = "gap"
                        } else {
                            if (trace !== ClinkTrace.NONE) { liveFrames++; videoBytes += f.data.size; if (f.keyFrame) keyframes++ }
                            sent++
                            everLive = true; liveFrameAt = System.nanoTime(); videoWrittenAt = liveFrameAt
                        }
                        if (result == Emit.LATE) { writable = false; lateVideo = true; lateLive = true }
                    }
                } else if (f != null && mismatchReported != "unsupported_h265") {
                    mismatchReported = "unsupported_h265"
                    listener.error(IllegalArgumentException("Carlink v0 requires H.264 input"))
                }
                val now = System.nanoTime()
                if (writable && gate.placeholder && now >= nextPlaceholder && (!everLive || now - liveFrameAt > 3_000_000_000)) {
                    val data = placeholder.frames[placeholderIndex]
                    val pts = maxOf(0L, transportPts + 1, outputPts + 1_000_000 / placeholder.fps)
                    val result = emit(session, pts) { transport.writeVideo(data, placeholderConfig, pts, placeholderIndex == 0) }
                    outputPts = pts; transportPts = pts
                    if (result == Emit.DROPPED) { if (trace !== ClinkTrace.NONE) failedVideo++; placeholderIndex = 0 } else {
                        if (trace !== ClinkTrace.NONE) { placeholderFrames++; videoBytes += data.size; if (placeholderIndex == 0) keyframes++ }
                        sent++; videoWrittenAt = System.nanoTime()
                        placeholderIndex = (placeholderIndex + 1) % placeholder.frames.size
                    }
                    if (result == Emit.LATE) { writable = false; lateVideo = true }
                    nextPlaceholder = now + 1_000_000_000 / placeholder.fps
                }
                // Anchor audio during the placeholder, then share the live video's exact offset.
                if (session.mediaAudio) repeat(4) {
                    if (!writable) return@repeat
                    val audio = synchronized(run.lock) {
                        val candidate = run.audio.peekFirst()
                        if (!gate.live && candidate != null && audioOffset == null) audioOffset = maxOf(0L, outputPts) - candidate.ptsUs
                        val offset = if (gate.live) liveOffset else audioOffset ?: 0L
                        // Phones deliver video in batches with gaps of up to ~1 s; between video writes the
                        // transport clock keeps running so audio is not starved and dropped (2026-10-15).
                        val clockPts = if (videoWrittenAt == 0L) outputPts else outputPts + (System.nanoTime() - videoWrittenAt) / 1000
                        if (candidate != null && candidate.ptsUs + offset <= clockPts + 50_000) {
                            run.audio.removeFirst().also { run.audioBytes -= it.data.size }
                        } else null
                    }
                    if (audio != null) {
                        val mapped = audio.ptsUs + (if (gate.live) liveOffset else audioOffset ?: 0L)
                        if (mapped >= 0 && mapped >= transportPts - 50_000) {
                            // Keep mux calls ordered; bounded late arrival can shift PTS by at most 50 ms.
                            val pts = maxOf(mapped, transportPts)
                            val result = emit(session, pts) { transport.writeAudio(Adts.buildHeader(aac, audio.data.size) + audio.data, pts) }
                            if (trace !== ClinkTrace.NONE && result != Emit.DROPPED) audioFrames++
                            lastAudioPts = pts
                            transportPts = pts
                            if (result == Emit.LATE) writable = false
                        }
                    }
                }
                if (!run.active.get()) break
                if (now - reportedAt >= 1_000_000_000) {
                    val overflows = run.queue.overflows; val waitingDrops = run.queue.waitingDrops
                    if (overflows != reportedOverflows || waitingDrops != reportedWaitingDrops) {
                        listener.diagnostic("video queue overflows=$overflows waitingDrops=$waitingDrops sent=$sent")
                        reportedOverflows = overflows; reportedWaitingDrops = waitingDrops
                    }
                    val audioDrops = synchronized(run.lock) { run.audioDrops }
                    if (session.mediaAudio && audioDrops != reportedAudioDrops) {
                        listener.diagnostic("audio queue drops=$audioDrops")
                        reportedAudioDrops = audioDrops
                    }
                    val dropped = run.queue.droppedCount()
                    // What the sink actually sees: video and audio PTS on one shared transport clock.
                    if (trace !== ClinkTrace.NONE && now - clockAt >= 2_000_000_000L) {
                        clockAt = now
                        trace.emit(ClinkTraceEvent.Video(now, session.traceSession, "clock",
                            "v=$outputPts a=$lastAudioPts t=$transportPts off=$liveOffset"))
                    }
                    if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Media(now, session.traceSession,
                        liveFrames, placeholderFrames, dropped + failedVideo, keyframes, videoBytes, run.rtpPackets.get(), run.rtpBytes.get(),
                        sendStalls, audioFrames, overflows))
                    listener.statistics(sent, dropped)
                    withHost(run) { it.stats(OutputStats(origin, ScreenId.MAIN, null, 0, dropped, 0, null)) }
                    reportedAt = now
                }
                if (f == null) Thread.sleep(2)
            }
        } catch (e: Exception) {
            if (trace !== ClinkTrace.NONE) trace.emit(ClinkTraceEvent.Error(System.nanoTime(), run.traceSession, "output_failed", e.javaClass.name, "ClinkOutput"))
            if (run.active.get()) listener.error(e)
            run.session?.stop(); run.active.set(false)
        }
    }
}

/** Longest a video timestamp may lead the shared transport clock before the stream is re-anchored. */
private const val MAX_LEAD_US: Long = 500_000

/** Everything the Carlink media path retains at one instant; every field has a fixed bound. */internal data class MediaBacklog(
    val videoFrames: Int, val videoBytes: Long,
    val audioPackets: Int, val audioBytes: Int,
    val voiceCommands: Int, val voiceBytes: Int,
    val rtpBufferBytes: Int, val rtpInFlight: Boolean,
)

internal fun mediaQueueFull(packetCount: Int, totalBytes: Int, spanUs: Long): Boolean =
    packetCount >= 96 || totalBytes > 524_288 || spanUs > 1_500_000

internal fun mediaQueueSpanUs(newestUs: Long, oldestUs: Long): Long =
    if (newestUs <= oldestUs) 0 else
        (newestUs.toULong() - oldestUs.toULong()).coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
