// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-20

package org.openxplay.stream.clink

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.openxplay.stream.clink.trace.*
import org.openxplay.stream.core.*
import kotlin.test.*

@Timeout(30)
class ClinkCodecLockTest {
    @Test fun placeholderAndLiveKeepTheNegotiatedCodecDespiteInputChanges() {
        for ((mode, flag) in listOf(ClinkHevcMode.STREAM_ONLY to true, ClinkHevcMode.STREAM_ONLY to false,
            ClinkHevcMode.OFF to true, ClinkHevcMode.SIGNAL to true)) {
            val expected = if (flag && mode != ClinkHevcMode.OFF) VideoCodec.H265 else VideoCodec.H264
            val avc = BundledPlaceholderVideo()
            val hevc = BundledHevcPlaceholderVideo()
            val good = if (expected == VideoCodec.H265) hevc else avc
            val bad = if (expected == VideoCodec.H265) avc else hevc
            val events = CopyOnWriteArrayList<ClinkTraceEvent.Video>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val ports = freePorts()
            val capabilities = javaClass.getResourceAsStream("/head-unit-wfd.txt")!!.bufferedReader().use { it.readText() } +
                if (flag) "\r\niccoa_support_hevc: supported\r\n" else ""
            SimulatedHeadUnit(ports, capabilities = capabilities).use { peer ->
                val output = ClinkOutput(StaticClinkLink(ClinkAddresses("127.0.0.1", "127.0.0.1", ports.auth)),
                    PinProvider { "012345" }, MemoryTrustStore(), ports = ports, hevc = mode,
                    trace = ClinkTrace { if (it is ClinkTraceEvent.Video) events.add(it) },
                    listener = object : ClinkListener { override fun error(error: Throwable) { errors.add(error) } })
                val decoders = CopyOnWriteArrayList<DecoderSupport>()
                val requests = CopyOnWriteArrayList<Long>()
                output.open(object : OutputHost {
                    override fun capabilities(capabilities: OutputCapabilities) { decoders.addAll(capabilities.decoders) }
                    override fun control(event: ControlEvent) {
                        if (event is ControlEvent.KeyFrameRequest) requests.add(System.nanoTime())
                    }
                    override fun stats(stats: OutputStats) {}
                })
                try {
                    var session = SessionId(1)
                    output.onSession(SessionState(session, SessionPhase.ACTIVE), SessionConfig(emptyMap()))
                    peer.await("first placeholder") { peer.accessUnits.isNotEmpty() }
                    assertEquals(expected, peer.accessUnits.first().codec)
                    assertTrue(decoders.isNotEmpty())
                    assertTrue(decoders.all { it.codecString.startsWith(if (expected == VideoCodec.H265) "hvc1." else "avc1.") })
                    var pts = 0L
                    fun send(clip: PlaceholderVideo) {
                        output.onVideoFormat(ScreenId.MAIN, clip.format)
                        output.onVideoFrame(VideoFrame(ScreenId.MAIN, session, clip.frames.first(), pts, true, System.nanoTime()))
                        pts += 33_333
                    }
                    send(bad)
                    peer.await("mismatched input skipped") { events.any { it.event == "codec_mismatch" } }
                    peer.await("mismatch requests a keyframe") { events.any { it.event == "keyframe_request" && it.source == "codec_mismatch" } }
                    val count = peer.accessUnits.size
                    peer.await("placeholder continues") { peer.accessUnits.size > count + 1 }
                    assertFalse(events.any { it.event == "live" })
                    send(good)
                    peer.await("matching live input") { events.any { it.event == "live" || it.event == "reanchor" } }
                    send(bad)
                    peer.await("second mismatch skipped") { events.count { it.event == "codec_mismatch" } == 2 }
                    // Restarting the iPhone stream does not start a new Carlink session or PMT.
                    output.onSession(SessionState(session, SessionPhase.ENDED), SessionConfig(emptyMap()))
                    session = SessionId(2)
                    output.onSession(SessionState(session, SessionPhase.ACTIVE), SessionConfig(emptyMap()))
                    val liveCount = events.count { it.event == "live" || it.event == "reanchor" }
                    send(good)
                    peer.await("matching input after iPhone restart") {
                        events.count { it.event == "live" || it.event == "reanchor" } > liveCount
                    }
                    assertEquals(1, events.count { it.event == "muxer_codec" })
                    assertTrue(peer.accessUnits.all { it.codec == expected })
                    val tracedRequests = events.filter { it.event == "keyframe_request" }
                    assertTrue(requests.isNotEmpty())
                    assertTrue(tracedRequests.any { it.source == "codec_mismatch" })
                    // Incoming requests are traced as they arrive; the requests sent to the host are rate limited.
                    assertTrue(requests.zipWithNext().all { (a, b) -> b - a >= 1_000_000_000 })
                    assertTrue(errors.isEmpty(), errors.joinToString())
                    peer.assertHealthy()
                } finally {
                    peer.expectRelayClose()
                    output.close()
                }
            }
        }
    }
}
