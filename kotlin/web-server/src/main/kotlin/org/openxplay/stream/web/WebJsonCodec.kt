// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.web

import java.net.URI
import kotlinx.serialization.json.*
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.WireFormatException

/** Strict tree codec for every OXP-Web v0 JSON message, including reserved phase-two messages. */
public object WebJsonCodec {
    public enum class Direction { CLIENT_TO_RELAY, RELAY_TO_CLIENT }

    public fun parse(text: String, direction: Direction? = null): JsonObject {
        val element = try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) {
            throw WireFormatException("Invalid JSON")
        }
        return validate(element, direction)
    }

    public fun encode(message: JsonObject, direction: Direction? = null): String = validate(message, direction).toString()

    public fun validate(element: JsonElement, direction: Direction? = null): JsonObject {
        val obj = element as? JsonObject ?: throw WireFormatException("Expected JSON object")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type == null || schemas[type]?.invoke(obj) != true) throw WireFormatException("Invalid JSON message")
        val allowed = when (direction) {
            Direction.CLIENT_TO_RELAY -> setOf("fullscreen-exit", "display-settings", "hello", "restart", "keyframe", "pause", "ping", "pong", "stats", "receiver-info")
            Direction.RELAY_TO_CLIENT -> setOf("view-area", "display-settings", "welcome", "session", "video-format", "audio-format", "mic", "ping", "pong", "error")
            null -> schemas.keys
        }
        if (type == "display-settings" && direction == Direction.RELAY_TO_CLIENT && "videoCodec" !in obj) throw WireFormatException("Missing video codec")
        if (type !in allowed) throw WireFormatException("Wrong message direction")
        return obj
    }

    internal fun session(state: SessionState): JsonObject = buildJsonObject {
        put("id", state.id.value.toString()); put("phase", state.phase.name)
        state.reason?.let { put("reason", it) }
    }

    internal fun video(screen: ScreenId, format: VideoFormat?): JsonObject = buildJsonObject {
        put("type", "video-format"); put("screen", screen.value)
        put("format", format?.let { buildJsonObject {
            put("codec", it.codec.name); put("codecString", it.codecString)
            put("config", java.util.Base64.getEncoder().encodeToString(it.config))
            put("width", it.width); put("height", it.height)
            it.fpsHint?.let { fps -> put("fpsHint", fps) }
        } } ?: JsonNull)
    }

    internal fun audio(stream: Int, format: AudioFormat?): JsonObject = buildJsonObject {
        put("type", "audio-format"); put("stream", stream)
        put("format", format?.let { buildJsonObject {
            put("role", it.role.name); put("codec", it.codec.name)
            put("sampleRate", it.sampleRate); put("channels", it.channels)
            it.config?.let { bytes -> put("config", java.util.Base64.getEncoder().encodeToString(bytes)) }
        } } ?: JsonNull)
    }

    internal fun microphone(stream: Int, request: MicRequest?): JsonObject = buildJsonObject {
        put("type", "mic"); put("stream", stream)
        put("request", request?.let { buildJsonObject {
            put("codec", it.codec.name); put("sampleRate", it.sampleRate)
            put("channels", it.channels); put("frameMs", it.frameMs)
        } } ?: JsonNull)
    }

    private val string: (JsonElement?) -> Boolean = { it is JsonPrimitive && it.isString }
    private val nonempty: (JsonElement?) -> Boolean = { string(it) && (it as JsonPrimitive).content.isNotEmpty() }
    private val bool: (JsonElement?) -> Boolean = { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }
    private val number: (JsonElement?) -> Boolean = {
        it is JsonPrimitive && !it.isString && it.doubleOrNull?.let { n -> n.isFinite() && n >= 0 } == true
    }
    private val positive: (JsonElement?) -> Boolean = { number(it) && (it as JsonPrimitive).double > 0 }
    private val integer: (JsonElement?) -> Boolean = {
        number(it) && (it as JsonPrimitive).double.let { n -> n <= 9007199254740991.0 && n % 1.0 == 0.0 }
    }
    private val positiveInt: (JsonElement?) -> Boolean = { integer(it) && positive(it) }
    private fun range(max: Int): (JsonElement?) -> Boolean = { integer(it) && (it as JsonPrimitive).double <= max }
    private fun literal(vararg values: String): (JsonElement?) -> Boolean = { string(it) && (it as JsonPrimitive).content in values }
    private fun optional(v: (JsonElement?) -> Boolean): (JsonElement?) -> Boolean = { it == null || v(it) }
    private fun nullable(v: (JsonElement?) -> Boolean): (JsonElement?) -> Boolean = { it == JsonNull || v(it) }
    private fun array(v: (JsonElement?) -> Boolean): (JsonElement?) -> Boolean = { it is JsonArray && it.all(v) }
    private fun obj(vararg fields: Pair<String, (JsonElement?) -> Boolean>): (JsonElement?) -> Boolean {
        val schema = fields.toMap()
        return { it is JsonObject && (it["type"]?.jsonPrimitive?.content == "stats" || it.keys.all(schema::containsKey)) && schema.all { (key, v) -> v(it[key]) } }
    }
    private val i64: (JsonElement?) -> Boolean = {
        string(it) && (it as JsonPrimitive).content.let { s ->
            s.length <= 20 && Regex("^(0|-?[1-9][0-9]*)$").matches(s) && s.toLongOrNull() != null
        }
    }
    private val base64: (JsonElement?) -> Boolean = {
        string(it) && Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$").matches((it as JsonPrimitive).content)
    }
    private val url: (JsonElement?) -> Boolean = {
        string(it) && try {
            val uri = URI((it as JsonPrimitive).content)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        } catch (_: Exception) { false }
    }
    private val audioCodec = literal("AAC_LC", "OPUS", "PCM_S16LE")
    private val sessionSchema = obj("id" to i64, "phase" to literal("CONNECTING", "ACTIVE", "RECONNECTING", "ENDED"), "reason" to optional(string))
    private val display = obj("screen" to range(255), "width" to positiveInt, "height" to positiveInt,
        "dpr" to positive, "refreshHz" to positive,
        "viewport" to optional(obj("width" to positiveInt, "height" to positiveInt)), "fullscreen" to optional(bool), "fullscreenPreferred" to optional(bool),
        "frameRate" to optional(literal("AUTO", "FPS_30", "FPS_35", "FPS_40", "FPS_45", "FPS_50", "FPS_55", "FPS_60")), "sustainedFps" to optional(positive), "safeArea" to obj("top" to number, "right" to number, "bottom" to number, "left" to number))
    private val videoSchema = obj("codec" to literal("H264", "H265"), "codecString" to nonempty,
        "config" to { nonempty(it) && base64(it) }, "width" to positiveInt, "height" to positiveInt, "fpsHint" to optional(positive))
    private val audio = obj("role" to literal("MEDIA", "ALERT", "VOICE", "TELEPHONY", "OTHER"), "codec" to audioCodec,
        "sampleRate" to positiveInt, "channels" to positiveInt, "config" to optional(base64))
    private val mic = obj("codec" to audioCodec, "sampleRate" to positiveInt, "channels" to positiveInt, "frameMs" to positive)
    private fun message(type: String, vararg fields: Pair<String, (JsonElement?) -> Boolean>): Pair<String, (JsonElement?) -> Boolean> =
        type to obj("type" to literal(type), *fields)
    private val schemas = mapOf(
        "view-area" to { value: JsonElement? ->
            obj("type" to literal("view-area"), "x" to integer, "y" to integer, "width" to positiveInt, "height" to positiveInt,
                "canvasWidth" to positiveInt, "canvasHeight" to positiveInt)(value) && (value as JsonObject).let {
                it.getValue("x").jsonPrimitive.double + it.getValue("width").jsonPrimitive.double <= it.getValue("canvasWidth").jsonPrimitive.double &&
                it.getValue("y").jsonPrimitive.double + it.getValue("height").jsonPrimitive.double <= it.getValue("canvasHeight").jsonPrimitive.double } },
        message("fullscreen-exit", "hiddenMs" to nullable(number), "display" to display, "autoRestore" to optional(literal("restored", "denied", "off"))),
        "receiver-info" to { value: JsonElement? -> value is JsonObject && value.toString().toByteArray(Charsets.UTF_8).size <= 65536 },
        message("display-settings", "videoCodec" to optional(literal("auto", "h265", "h264")), "resolution" to literal("auto", "native", "1080p", "720p", "screen"), "screenWidth" to { integer(it) && (it as JsonPrimitive).intOrNull in setOf(125, 200, 250, 300) }),
        message("restart"),
        message("hello", "videoCodec" to optional(literal("AUTO", "H264")), "playback" to optional(literal("SMART", "QUALITY", "SPEED")), "version" to { integer(it) && (it as JsonPrimitive).double == 0.0 }, "token" to string,
            "decoders" to array(obj("codecString" to nonempty, "hardware" to bool)), "displays" to array(display),
            "maxTouchContacts" to range(10), "audio" to array(audioCodec), "microphone" to bool),
        message("welcome", "receiverBuild" to optional(nonempty), "features" to optional(array(string)), "relay" to optional(obj("name" to nonempty, "version" to nonempty)), "plugins" to optional(obj("host" to literal("/plugins/host/index.html"))), "version" to { integer(it) && (it as JsonPrimitive).double == 0.0 }, "clientId" to nonempty,
            "session" to sessionSchema, "sourceUrl" to url, "attribution" to nonempty, "projectUrl" to url),
        message("session", "session" to sessionSchema),
        message("video-format", "screen" to range(255), "format" to nullable(videoSchema)),
        message("audio-format", "stream" to range(255), "format" to nullable(audio)),
        message("mic", "stream" to range(255), "request" to nullable(mic)),
        message("pause", "screen" to range(255), "paused" to bool),
        message("keyframe", "screen" to range(255)),
        message("ping", "t0Us" to i64),
        message("pong", "t0Us" to i64, "t1Us" to i64, "t2Us" to i64),
        message("stats",
            "wsBytesPerSecond" to optional(number), "videoFramesPerSecond" to optional(number), "decoderSubmittedPerSecond" to optional(number),
            "decoderOutputPerSecond" to optional(number), "decodeLatencyP50Ms" to optional(number), "decodeLatencyP95Ms" to optional(number),
            "decodeLatencyMaxMs" to optional(number), "maxDecodeQueue" to optional(number), "decoderErrors" to optional(number),
            "decoderResets" to optional(number), "renderedFps" to optional(number), "droppedFrames" to optional(number),
            "renderLatencyP50Ms" to optional(number), "renderLatencyP95Ms" to optional(number), "eventLoopLagAvgMs" to optional(number),
            "eventLoopLagMaxMs" to optional(number), "longTaskCount" to optional(number), "longTaskTotalMs" to optional(number),
            "usedJSHeapSize" to optional(number), "renderDepth" to optional(number),
            "audioBufferedMs" to optional(nullable(number)), "audioUnderruns" to optional(integer), "screen" to range(255), "decodeMs" to nullable(number), "decodeQueue" to integer,
            "dropped" to integer, "rendered" to integer, "decodedLatencyMs" to nullable(number),
            "renderedLatencyMs" to nullable(number), "clockOffsetUs" to nullable(i64), "rttUs" to nullable(i64)),
        message("error", "code" to nonempty, "message" to string),
    )
}
