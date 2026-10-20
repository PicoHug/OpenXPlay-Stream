// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink.wfd

import java.util.UUID
import org.openxplay.stream.clink.ClinkPorts
import org.openxplay.stream.clink.ClinkHevcMode
import org.openxplay.stream.core.DecoderSupport
import org.openxplay.stream.core.DisplayInfo

/** Negotiated CEA timing and H.264 profile/level masks. */
public data class WfdMode(public val width: Int, public val height: Int, public val fps: Int, public val cea: Int,
    public val profile: Int, public val level: Int)

/** Pure source negotiation, confined to its RTSP worker. Transport remains separate. */
public class WfdSource(private val address: String, private val ports: ClinkPorts,
    private val hevc: ClinkHevcMode = ClinkHevcMode.STREAM_ONLY,
    private val display: () -> DisplayInfo = { DisplayInfo(1280, 720, 160, 30.0) }) {
    public var mode: WfdMode = WfdMode(1280, 720, 30, 5, 1, 1)
        private set
    public var decoders: List<DecoderSupport> = emptyList()
        private set
    public var iccoaSupportHevc: String? = null
        private set
    public val hevcEnabled: Boolean get() = hevc != ClinkHevcMode.OFF && iccoaSupportHevc == "supported"
    public var playing: Boolean = false
        private set
    public var rtpPort: Int = ports.rtp
        private set
    public var audio: Boolean = false
        private set
    private var setup = false
    private var keyframe = false
    private var sequence = 0
    private val pending = mutableMapOf<String, String>()
    private val session = UUID.randomUUID().toString().replace("-", "")
    private var advertisedRtp = "RTP/AVP/TCP;unicast ${ports.rtp} 0 mode=play"
    private var probed = false
    private val reports = mutableListOf<String>()

    private fun request(method: String, stage: String, body: String = "", headers: Map<String, String> = emptyMap()): RtspMessage {
        val seq = (++sequence).toString(); pending[seq] = stage
        return RtspMessage("$method * RTSP/1.0", headers + ("cseq" to seq), body)
    }
    public fun start(): RtspMessage = request("OPTIONS", "options", headers = mapOf("require" to "org.wfa.wfd1.0"))
    public fun takeKeyframe(): Boolean = keyframe.also { keyframe = false }
    /** Diagnostic lines about the head unit's video capabilities, drained by the session. */
    public fun takeReports(): List<String> = reports.toList().also { reports.clear() }
    public fun capabilities(): Map<String, String> {
        val d = display()
        return mapOf(
            "wfd_video_formats" to "%02x 00 %02x %02x %08x 00000000 00000000 00 0000 0000 00 none none".format(mode.cea shl 3, mode.profile, mode.level, 1L shl mode.cea),
            "wfd_audio_codecs" to if (audio) "AAC 00000001 00" else "none", "wfd_client_rtp_ports" to advertisedRtp,
            "wfd_uibc_capability" to "input_category_list=GENERIC;generic_cap_list=Keyboard, MultiTouch;hidc_cap_list=none;port=${ports.uibc};uibc_encrypted=true",
            "wfd_uibc_setting" to "enable", "wfd_content_protection" to "none",
            "wfd_standby_resume_capability" to "none",
            "wfd_presentation_URL" to "rtsp://$address:${ports.rtsp}/wfd1.0/streamid=0 none",
            "wfd_car_display_mode" to "mode=0;display=${mode.width}:${mode.height};dpi=${d.densityDpi};fps=${mode.fps}",
        ) + if (hevcEnabled && hevc == ClinkHevcMode.SIGNAL) mapOf("iccoa_support_hevc" to "supported") else emptyMap()
    }
    private fun negotiate(body: String) {
        val peer = parameters(body)
        iccoaSupportHevc = peer["iccoa_support_hevc"]
        reports += "video caps iccoa_support_hevc=${iccoaSupportHevc ?: "absent"}"
        reports += "video caps wfd_video_formats=${peer["wfd_video_formats"]}"
        audio = peer["wfd_audio_codecs"].orEmpty().split(',').any { entry ->
            val fields = entry.trim().split(Regex("\\s+"))
            fields.size == 3 && fields[0] == "AAC" && (fields[1].toLongOrNull(16)?.and(1L) == 1L)
        }
        val formats = checkNotNull(peer["wfd_video_formats"]).split(',')
        val timings = listOf(Triple(1280,720,30) to 5, Triple(640,480,60) to 0, Triple(720,480,60) to 1,
            Triple(1280,720,60) to 6, Triple(1920,1080,30) to 7, Triple(1920,1080,60) to 8,
            Triple(1280,720,25) to 10, Triple(1280,720,50) to 11, Triple(1920,1080,25) to 12, Triple(1920,1080,50) to 13)
        val supported = mutableListOf<DecoderSupport>()
        var selected: WfdMode? = null
        for ((index, entry) in formats.withIndex()) {
            val fields = entry.trim().split(Regex("\\s+"))
            val offset = if (index == 0) 2 else 0
            require(fields.size >= offset + 3) { "projection_formats_required" }
            val profiles = fields[offset].toInt(16); val levels = fields[offset + 1].toInt(16)
            val cea = fields[offset + 2].toLong(16)
            for (profile in listOf(1, 2)) for ((bit, level) in listOf(1 to 31, 2 to 32, 4 to 40, 8 to 41, 16 to 42)) {
                if (profiles and profile != 0 && levels and bit != 0) {
                    supported += DecoderSupport("avc1.${if (profile == 1) "42E0" else "6400"}${"%02X".format(level)}", true)
                    if (selected == null) {
                        // H.265 claims Main Level 4.1, which covers 1920x1080p30 (2,073,600 samples).
                        // 1080p60 would need Level 5.1, and at 60 fps the encoder and the muxer take
                        // enough CPU that the audio queue starts dropping packets (2026-10-20), so the
                        // highest mode stays at 1080p30. The head unit scales the stream onto its screen.
                        // H.264 keeps 720p30, the size the head units are known to accept.
                        val wanted = if (hevcEnabled) listOf(Triple(1920, 1080, 30), Triple(1280, 720, 30))
                            else listOf(Triple(1280, 720, 30))
                        val timing = wanted.firstNotNullOfOrNull { size ->
                            timings.firstOrNull { it.first == size && cea and (1L shl it.second) != 0L }
                        }
                        if (timing != null) selected = WfdMode(timing.first.first, timing.first.second, timing.first.third, timing.second, profile, bit)
                    }
                }
            }
        }
        // The flag names no level; claim Main 5.1 so the iPhone's 1080p60 H.265 passes through untouched.
        // 1080p30 fits Level 4.1, so the lower mode keeps the smaller claim.
        if (hevcEnabled) {
            supported.clear()
            val level = if ((selected?.fps ?: 0) >= 60) 153 else 123
            supported += DecoderSupport("hvc1.1.6.L$level.B0", true)
        }
        mode = checkNotNull(selected) { "projection_timing_mismatch" }; decoders = supported.distinct()
        peer["wfd_client_rtp_ports"]?.let {
            require(it.startsWith("RTP/AVP/TCP;unicast")) { "tcp_transport_required" }
            advertisedRtp = it
        }
    }
    public fun handle(message: RtspMessage): List<RtspMessage> {
        val seq = checkNotNull(message.headers["cseq"]) { "rtsp_sequence_required" }
        val parts = message.line.split(' '); require(parts.size >= 3)
        if (parts[0].startsWith("RTSP/")) {
            val stage = pending.remove(seq) ?: return emptyList()
            if (stage == "probe") {
                // Optional R2 query: a refusal or an omitted answer never affects the session.
                val answer = if (parts[1] == "200") parameters(message.body)["wfd2_video_formats"] ?: "absent" else "rejected ${parts[1]}"
                reports += "video caps wfd2_video_formats=$answer"
                return emptyList()
            }
            require(parts[1] == "200") { "projection_negotiation_rejected" }
            return when (stage) {
                "options" -> listOf(request("GET_PARAMETER", "capabilities", "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\n"))
                "capabilities" -> { negotiate(message.body); listOf(request("SET_PARAMETER", "selected", parameterBody(capabilities()))) }
                "selected" -> listOf(request("SET_PARAMETER", "trigger", "wfd_trigger_method: SETUP\r\n"))
                else -> emptyList()
            }
        }
        val headers = mutableMapOf("cseq" to seq)
        var status = "200 OK"
        var body = ""
        when (parts[0]) {
            "OPTIONS" -> headers["public"] = "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"
            "GET_PARAMETER" -> body = parameterBody(capabilities().filterKeys { it in parameters(message.body) })
            "SET_PARAMETER" -> if ("wfd_idr_request" in parameters(message.body)) keyframe = true
            "SETUP" -> {
                val transport = message.headers["transport"].orEmpty()
                val match = Regex("(?:^|;)client_port=(\\d+)(?:-(\\d+))?(?:;|$)").find(transport)
                if (!transport.startsWith("RTP/AVP/TCP;") || match == null) status = "461 Unsupported Transport"
                else {
                    rtpPort = match.groupValues[1].toInt(); require(rtpPort in 1..65535)
                    headers["transport"] = "$transport;server_port=${ports.rtp}-${minOf(65535, ports.rtp + 1)}"
                    headers["session"] = session; setup = true
                }
            }
            "PLAY", "PAUSE", "TEARDOWN" -> {
                if (!setup || message.headers["session"]?.substringBefore(';') != session) status = "454 Session Not Found"
                else {
                    playing = parts[0] == "PLAY"; headers["session"] = session
                    if (parts[0] == "TEARDOWN") setup = false
                    if (playing) keyframe = true
                }
            }
            else -> status = "405 Method Not Allowed"
        }
        val response = RtspMessage("RTSP/1.0 $status", headers, body)
        if (playing && !probed) {
            // Ask once, after playback started, whether the head unit also decodes H.265 (WFD R2).
            probed = true
            return listOf(response, request("GET_PARAMETER", "probe", "wfd2_video_formats\r\n"))
        }
        return listOf(response)
    }
}
