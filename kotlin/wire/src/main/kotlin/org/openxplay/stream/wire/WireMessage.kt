// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.wire

import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.BinaryMessage

public enum class WireRole { PRODUCER, CONSUMER }

/** Stream v0 messages. Binary origins and sessions belong to connection state. */
public sealed interface WireMessage {
    public data class Hello(public val role: WireRole, public val name: String, public val client: String,
        public val version: Int = 0) : WireMessage
    public data class DisplaySettings(public val resolution: String, public val screenWidth: Int,
        public val videoCodec: String) : WireMessage
    public data class ViewAreaMessage(public val screen: ScreenId, public val area: WireViewArea?) : WireMessage
    public data class Context(public val update: ContextUpdate) : WireMessage
    public data class Unknown(public val json: kotlinx.serialization.json.JsonObject) : WireMessage
    public data class Session(public val state: SessionState) : WireMessage
    public data class VideoFormatMessage(public val screen: ScreenId, public val format: VideoFormat?) : WireMessage
    public data class AudioFormatMessage(public val stream: Int, public val format: AudioFormat?) : WireMessage
    public data class Microphone(public val stream: Int, public val request: MicRequest?) : WireMessage
    public data class Capabilities(public val value: OutputCapabilities) : WireMessage
    public data class InputCapabilitiesMessage(public val value: InputCapabilities) : WireMessage
    public data class Client(public val client: String) : WireMessage
    public data object Restart : WireMessage
    public data class Keyframe(public val screen: ScreenId) : WireMessage
    /** Origin is supplied by the connection on decode. */
    public data class Stats(
        public val value: OutputStats,
        public val decodeMs: Double? = value.decodeTimeUs?.div(1000.0),
        public val renderedLatencyMs: Double? = value.endToEndLatencyUs?.div(1000.0),
        public val decodedLatencyMs: Double? = null,
        public val clockOffsetUs: Long? = null,
        public val rttUs: Long? = null,
        public val audioBufferedMs: Double? = null,
        public val audioUnderruns: Long? = null,
    ) : WireMessage
    public data class Error(public val code: String, public val message: String) : WireMessage
    public class Binary(public val value: BinaryMessage) : WireMessage
}

internal val unknownInputCapabilities = InputCapabilities(emptySet(), emptySet(), false, emptySet(), false,
    false, ReconfigureCost.UNSUPPORTED)
internal val emptyOutputCapabilities = OutputCapabilities(LatencyClass.INTERACTIVE, emptyList(), emptyMap(),
    0, emptySet(), emptySet(), false)

public data class WireViewArea(public val x: Int, public val y: Int, public val width: Int,
    public val height: Int, public val canvasWidth: Int, public val canvasHeight: Int)
