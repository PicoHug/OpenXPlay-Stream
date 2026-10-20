// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.wire

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.*
import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*

/** Length-prefixed Stream v0 codec; clean EOF is distinct from a truncated frame. */
public object StreamWireCodec {
    public const val MAX_PAYLOAD: Int = 16 * 1024 * 1024

    public fun read(input: InputStream): WireMessage? {
        while (true) {
        val first = input.read()
        if (first < 0) return null
        val prefix = ByteArray(4); prefix[0] = first.toByte()
        exact(input, prefix, 1, 3)
        var length = 0L
        repeat(4) { length = length or ((prefix[it].toLong() and 255) shl (it * 8)) }
        check(length in 1..MAX_PAYLOAD.toLong(), "Invalid frame length")
        val payload = ByteArray(length.toInt())
        exact(input, payload, 0, payload.size)
        val message = decodePayload(payload, allowUnknown = true)
        if (message !is WireMessage.Unknown) return message
        }
    }

    /** Writes borrowed binary media separately from its header, without a media-sized copy. */
    public fun write(output: OutputStream, message: WireMessage) {
        if (message is WireMessage.Binary) {
            val binary = message.value
            val header = OxpWebCodec.encodeHeader(binary.header)
            when (binary.header.kind) {
                BinaryKind.TOUCH -> OxpWebCodec.decodeTouch(binary.payload)
                BinaryKind.KEY -> OxpWebCodec.decodeKey(binary.payload)
                else -> Unit
            }
            val size = header.size.toLong() + binary.payload.size
            check(size <= MAX_PAYLOAD, "Frame exceeds 16 MiB")
            prefix(output, size.toInt()); output.write(header); output.write(binary.payload)
        } else {
            val payload = encode(message)
            prefix(output, payload.size); output.write(payload)
        }
    }

    public fun encode(message: WireMessage): ByteArray {
        val bytes = if (message is WireMessage.Binary) {
            check(message.value.payload.size <= MAX_PAYLOAD - OxpWebCodec.HEADER_SIZE, "Frame exceeds 16 MiB")
            OxpWebCodec.encode(message.value)
        } else json(message).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_PAYLOAD, "Invalid frame length")
        // Validate locally constructed messages as strictly as received messages.
        if (message !is WireMessage.Binary && message !is WireMessage.Unknown) decode(bytes)
        return bytes
    }

    public fun decode(payload: ByteArray): WireMessage = decodePayload(payload, allowUnknown = false)

    private fun decodePayload(payload: ByteArray, allowUnknown: Boolean): WireMessage {
        check(payload.size in 1..MAX_PAYLOAD, "Invalid frame length")
        if (payload[0] != '{'.code.toByte()) return WireMessage.Binary(OxpWebCodec.decode(payload))
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            return parse(Json.parseToJsonElement(text).jsonObject, allowUnknown)
        } catch (e: WireFormatException) { throw e }
        catch (_: Exception) { throw WireFormatException("Invalid JSON message") }
    }

    private fun exact(input: InputStream, bytes: ByteArray, offset: Int, count: Int) {
        var position = offset
        while (position < offset + count) {
            val n = input.read(bytes, position, offset + count - position)
            if (n < 0) throw WireFormatException("Truncated frame")
            if (n == 0) {
                val byte = input.read()
                if (byte < 0) throw WireFormatException("Truncated frame")
                bytes[position++] = byte.toByte()
            } else position += n
        }
    }
    private fun prefix(output: OutputStream, length: Int) {
        check(length in 1..MAX_PAYLOAD, "Invalid frame length")
        output.write(ByteArray(4) { (length ushr (8 * it)).toByte() })
    }
    private fun check(ok: Boolean, reason: String) { if (!ok) throw WireFormatException(reason) }

    private fun json(message: WireMessage): JsonObject = when (message) {
        is WireMessage.Context -> JsonObject(ContextJson.encode(message.update) + ("type" to JsonPrimitive("context")))
        is WireMessage.Unknown -> message.json
        else -> knownJson(message)
    }
    private fun knownJson(message: WireMessage): JsonObject = buildJsonObject {
        when (message) {
            is WireMessage.Context, is WireMessage.Unknown -> error("Extension handled separately")
            is WireMessage.DisplaySettings -> {
                put("type", "display-settings"); put("resolution", message.resolution)
                put("screenWidth", message.screenWidth); put("videoCodec", message.videoCodec)
            }
            is WireMessage.ViewAreaMessage -> {
                put("type", "view-area"); put("screen", message.screen.value)
                put("area", message.area?.let { a -> buildJsonObject {
                    put("x", a.x); put("y", a.y); put("width", a.width); put("height", a.height)
                    put("canvasWidth", a.canvasWidth); put("canvasHeight", a.canvasHeight)
                } } ?: JsonNull)
            }
            is WireMessage.Hello -> {
                put("type", "hello"); put("version", message.version)
                put("role", message.role.name.lowercase()); put("name", message.name); put("client", message.client)
            }
            is WireMessage.Session -> {
                put("type", "session"); put("session", buildJsonObject {
                    put("id", message.state.id.value.toString()); put("phase", message.state.phase.name)
                    message.state.reason?.let { put("reason", it) }
                })
            }
            is WireMessage.VideoFormatMessage -> {
                put("type", "video-format"); put("screen", message.screen.value)
                put("format", message.format?.let { f -> buildJsonObject {
                    put("codec", f.codec.name); put("codecString", f.codecString)
                    put("config", Base64.getEncoder().encodeToString(f.config))
                    put("width", f.width); put("height", f.height); f.fpsHint?.let { put("fpsHint", it) }
                } } ?: JsonNull)
            }
            is WireMessage.AudioFormatMessage -> {
                check(message.format == null || message.format.stream == message.stream, "Inconsistent audio stream")
                put("type", "audio-format"); put("stream", message.stream)
                put("format", message.format?.let { f -> buildJsonObject {
                    put("role", f.role.name); put("codec", f.codec.name)
                    put("sampleRate", f.sampleRate); put("channels", f.channels)
                    f.config?.let { put("config", Base64.getEncoder().encodeToString(it)) }
                } } ?: JsonNull)
            }
            is WireMessage.Microphone -> {
                check(message.request == null || message.request.stream == message.stream, "Inconsistent microphone stream")
                put("type", "mic"); put("stream", message.stream)
                put("request", message.request?.let { r -> buildJsonObject {
                    put("codec", r.codec.name); put("sampleRate", r.sampleRate)
                    put("channels", r.channels); put("frameMs", r.frameMs)
                } } ?: JsonNull)
            }
            is WireMessage.Capabilities -> {
                val c = message.value
                put("type", "capabilities"); put("latency", c.latency.name)
                put("decoders", buildJsonArray { c.decoders.forEach { d -> add(buildJsonObject {
                    put("codecString", d.codecString); put("hardware", d.hardware)
                }) } })
                put("displays", buildJsonObject { c.displays.toSortedMap(compareBy { it.value }).forEach { (screen, d) ->
                    put(screen.value.toString(), buildJsonObject {
                        put("widthPx", d.widthPx); put("heightPx", d.heightPx); put("densityDpi", d.densityDpi)
                        put("refreshHz", d.refreshHz); put("safeArea", buildJsonObject {
                            put("left", d.safeArea.left); put("top", d.safeArea.top)
                            put("right", d.safeArea.right); put("bottom", d.safeArea.bottom)
                        })
                        if (d.frameRate != FrameRatePreference.AUTO) put("frameRate", d.frameRate.name)
                        d.sustainedFps?.let { put("sustainedFps", it) }
                        d.viewport?.let { put("viewport", buildJsonObject { put("widthPx", it.width); put("heightPx", it.height) }) }
                        d.fullscreen?.let { put("fullscreen", it) }
                        d.fullscreenPreferred?.let { put("fullscreenPreferred", it) }
                    })
                } })
                put("touch", c.touch); put("keys", keys(c.keys))
                put("audio", names(c.audio.map { it.name })); put("microphone", c.microphone)
                if (c.prefersH264) put("prefersH264", true)
            }
            is WireMessage.InputCapabilitiesMessage -> {
                val c = message.value
                put("type", "input-capabilities"); put("codecs", names(c.codecs.map { it.name }))
                put("screens", buildJsonArray { c.screens.sortedBy { it.value }.forEach { add(it.value) } })
                put("touch", c.touch); put("keys", keys(c.keys)); put("microphone", c.microphone)
                put("keyFrameRequests", c.keyFrameRequests); put("reconfigure", c.reconfigure.name)
            }
            is WireMessage.Client -> { put("type", "client"); put("client", message.client) }
            WireMessage.Restart -> put("type", "restart")
            is WireMessage.Keyframe -> { put("type", "keyframe"); put("screen", message.screen.value) }
            is WireMessage.Stats -> {
                val s = message.value
                put("type", "stats"); put("screen", s.screen.value)
                put("decodeMs", message.decodeMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("decodeQueue", s.decodeQueue); put("dropped", s.dropped); put("rendered", s.rendered)
                put("decodedLatencyMs", message.decodedLatencyMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("renderedLatencyMs", message.renderedLatencyMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("clockOffsetUs", message.clockOffsetUs?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("rttUs", message.rttUs?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                if (message.audioUnderruns != null) {
                    put("audioBufferedMs", message.audioBufferedMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("audioUnderruns", message.audioUnderruns)
                } else check(message.audioBufferedMs == null, "Audio stats require underrun count")
            }
            is WireMessage.Error -> { put("type", "error"); put("code", message.code); put("message", message.message) }
            is WireMessage.Binary -> error("Binary has no JSON representation")
        }
    }
    private fun names(values: List<String>): JsonArray = buildJsonArray { values.sorted().forEach { add(it) } }
    private fun keys(values: Set<KeyCode>): JsonArray = buildJsonArray { values.sortedBy { it.wireCode }.forEach { add(it.wireCode) } }

    private fun parse(o: JsonObject, allowUnknown: Boolean): WireMessage = when (o.string("type")) {
        "display-settings" -> {
            o.fields("resolution", "screenWidth", "videoCodec")
            val resolution = o.string("resolution"); val width = o.int("screenWidth"); val codec = o.string("videoCodec")
            check(resolution in setOf("auto", "native", "1080p", "720p", "screen") && width in setOf(125, 200, 250, 300)
                && codec in setOf("auto", "h265", "h264"), "Invalid display settings")
            WireMessage.DisplaySettings(resolution, width, codec)
        }
        "view-area" -> {
            o.fields("screen", "area")
            val a = o.getValue("area")
            WireMessage.ViewAreaMessage(ScreenId(o.int("screen", 0, 255)), if (a == JsonNull) null else {
                val v = a.jsonObject
                v.fields("x", "y", "width", "height", "canvasWidth", "canvasHeight", type = false)
                WireViewArea(v.int("x"), v.int("y"), v.int("width", 1), v.int("height", 1),
                    v.int("canvasWidth", 1), v.int("canvasHeight", 1)).also {
                    check(it.x.toLong() + it.width <= it.canvasWidth && it.y.toLong() + it.height <= it.canvasHeight, "Invalid view area")
                }
            })
        }
        "context" -> { o.fields("topic", "value"); WireMessage.Context(ContextJson.decode(o)) }
        "hello" -> {
            o.fields("version", "role", "name", "client")
            check(o.int("version") == 0, "Unsupported wire version")
            val role = when (o.string("role")) {
                "producer" -> WireRole.PRODUCER; "consumer" -> WireRole.CONSUMER
                else -> throw WireFormatException("Invalid role")
            }
            WireMessage.Hello(role, o.string("name", true), o.string("client", true))
        }
        "session" -> {
            o.fields("session"); val s = o.getValue("session").jsonObject
            s.fields("id", "phase", optional = setOf("reason"), type = false)
            val id = s.string("id")
            check(Regex("^(0|-?[1-9][0-9]*)$").matches(id), "Invalid session id")
            WireMessage.Session(SessionState(SessionId(id.toLong()), SessionPhase.valueOf(s.string("phase")),
                s["reason"]?.let { s.string("reason") }))
        }
        "video-format" -> {
            o.fields("screen", "format"); val screen = ScreenId(o.int("screen", 0, 255))
            val f = o.getValue("format")
            WireMessage.VideoFormatMessage(screen, if (f == JsonNull) null else {
                val v = f.jsonObject
                v.fields("codec", "codecString", "config", "width", "height", optional = setOf("fpsHint"), type = false)
                val config = v.string("config", true)
                val bytes = Base64.getDecoder().decode(config)
                check(Base64.getEncoder().encodeToString(bytes) == config, "Noncanonical base64")
                VideoFormat.create(screen, VideoCodec.valueOf(v.string("codec")), bytes,
                    v["fpsHint"]?.let { v.number("fpsHint", positive = true) }).also {
                    check(it.width == v.int("width", 1) && it.height == v.int("height", 1) &&
                        it.codecString == v.string("codecString", true), "Inconsistent video format")
                }
            })
        }
        "audio-format" -> {
            o.fields("stream", "format"); val stream = o.int("stream", 0, 255)
            val f = o.getValue("format")
            WireMessage.AudioFormatMessage(stream, if (f == JsonNull) null else {
                val v = f.jsonObject
                v.fields("role", "codec", "sampleRate", "channels", optional = setOf("config"), type = false)
                val config = v["config"]?.let {
                    val text = v.string("config")
                    Base64.getDecoder().decode(text).also { bytes ->
                        check(Base64.getEncoder().encodeToString(bytes) == text, "Noncanonical base64")
                    }
                }
                AudioFormat(stream, AudioRole.valueOf(v.string("role")), AudioCodec.valueOf(v.string("codec")),
                    v.int("sampleRate", 1), v.int("channels", 1), config)
            })
        }
        "mic" -> {
            o.fields("stream", "request"); val stream = o.int("stream", 0, 255)
            val r = o.getValue("request")
            WireMessage.Microphone(stream, if (r == JsonNull) null else {
                val v = r.jsonObject
                v.fields("codec", "sampleRate", "channels", "frameMs", type = false)
                MicRequest(stream, AudioCodec.valueOf(v.string("codec")), v.int("sampleRate", 1),
                    v.int("channels", 1), v.int("frameMs", 1))
            })
        }
        "capabilities" -> {
            o.fields("latency", "decoders", "displays", "touch", "keys", "audio", "microphone", optional = setOf("prefersH264"))
            val displays = o.getValue("displays").jsonObject.map { (key, value) ->
                val screen = key.toInt(); check(screen in 0..255 && key == screen.toString(), "Invalid screen")
                val d = value.jsonObject
                d.fields("widthPx", "heightPx", "densityDpi", "refreshHz", "safeArea",
                    optional = setOf("frameRate", "sustainedFps", "viewport", "fullscreen", "fullscreenPreferred"), type = false)
                val a = d.getValue("safeArea").jsonObject
                a.fields("left", "top", "right", "bottom", type = false)
                ScreenId(screen) to DisplayInfo(d.int("widthPx", 1), d.int("heightPx", 1), d.int("densityDpi", 1),
                    d.number("refreshHz", true), SafeArea(a.int("left"), a.int("top"), a.int("right"), a.int("bottom")),
                    d["frameRate"]?.let { FrameRatePreference.valueOf(d.string("frameRate")) } ?: FrameRatePreference.AUTO,
                    d["sustainedFps"]?.let { d.number("sustainedFps", true) },
                    d["viewport"]?.let { it.jsonObject.let { v ->
                        v.fields("widthPx", "heightPx", type = false); Viewport(v.int("widthPx", 1), v.int("heightPx", 1))
                    } }, d["fullscreen"]?.let { d.bool("fullscreen") }, d["fullscreenPreferred"]?.let { d.bool("fullscreenPreferred") })
            }.toMap()
            val decoders = o.getValue("decoders").jsonArray.map { e -> e.jsonObject.let {
                it.fields("codecString", "hardware", type = false)
                DecoderSupport(it.string("codecString", true), it.bool("hardware"))
            } }
            WireMessage.Capabilities(OutputCapabilities(LatencyClass.valueOf(o.string("latency")), decoders, displays,
                o.int("touch", 0, 10), o.keys(), o.enumSet<AudioCodec>("audio"), o.bool("microphone"), if ("prefersH264" in o) o.bool("prefersH264") else false))
        }
        "input-capabilities" -> {
            o.fields("codecs", "screens", "touch", "keys", "microphone", "keyFrameRequests", "reconfigure")
            val screens = o.getValue("screens").jsonArray.map { ScreenId(primitiveInt(it, 0, 255)) }
            check(screens.toSet().size == screens.size, "Duplicate screen")
            WireMessage.InputCapabilitiesMessage(InputCapabilities(o.enumSet<VideoCodec>("codecs"), screens.toSet(),
                o.bool("touch"), o.keys(), o.bool("microphone"), o.bool("keyFrameRequests"),
                ReconfigureCost.valueOf(o.string("reconfigure"))))
        }
        "client" -> { o.fields("client"); WireMessage.Client(o.string("client", true)) }
        "restart" -> { o.fields(); WireMessage.Restart }
        "keyframe" -> { o.fields("screen"); WireMessage.Keyframe(ScreenId(o.int("screen", 0, 255))) }
        "stats" -> {
            o.fields("screen", "decodeMs", "decodeQueue", "dropped", "rendered", "decodedLatencyMs",
                "renderedLatencyMs", "clockOffsetUs", "rttUs", optional = setOf("audioBufferedMs", "audioUnderruns"))
            check(o.containsKey("audioBufferedMs") == o.containsKey("audioUnderruns"), "Incomplete audio stats")
            o.us("decodedLatencyMs")
            listOf("clockOffsetUs", "rttUs").forEach { if (o[it] != JsonNull) {
                val s = o.string(it); check(Regex("^(0|-?[1-9][0-9]*)$").matches(s) && s.toLongOrNull() != null, "Invalid clock")
            } }
            WireMessage.Stats(OutputStats(Origin("", ""), ScreenId(o.int("screen", 0, 255)), o.us("decodeMs"),
                o.int("decodeQueue"), o.counter("dropped"), o.counter("rendered"), o.us("renderedLatencyMs")),
                decodeMs = if (o["decodeMs"] == JsonNull) null else o.number("decodeMs"),
                renderedLatencyMs = if (o["renderedLatencyMs"] == JsonNull) null else o.number("renderedLatencyMs"),
                decodedLatencyMs = if (o["decodedLatencyMs"] == JsonNull) null else o.number("decodedLatencyMs"),
                clockOffsetUs = if (o["clockOffsetUs"] == JsonNull) null else o.string("clockOffsetUs").toLong(),
                rttUs = if (o["rttUs"] == JsonNull) null else o.string("rttUs").toLong(),
                audioBufferedMs = o["audioBufferedMs"]?.takeIf { it != JsonNull }?.let { o.number("audioBufferedMs") },
                audioUnderruns = o["audioUnderruns"]?.let { o.counter("audioUnderruns") })
        }
        "error" -> { o.fields("code", "message"); WireMessage.Error(o.string("code", true), o.string("message")) }
        else -> if (allowUnknown) WireMessage.Unknown(o) else throw WireFormatException("Unknown message type")
    }

    private fun JsonObject.fields(vararg required: String, optional: Set<String> = emptySet(), type: Boolean = true) {
        val all = required.toSet() + optional + if (type) setOf("type") else emptySet()
        check(keys.all { it in all } && required.all { containsKey(it) }, "Unexpected or missing field")
    }
    private fun JsonObject.string(key: String, nonempty: Boolean = false): String {
        val p = getValue(key).jsonPrimitive
        check(p.isString && (!nonempty || p.content.isNotEmpty()), "Invalid string: $key")
        return p.content
    }
    private fun JsonObject.bool(key: String): Boolean {
        val p = getValue(key).jsonPrimitive; check(!p.isString && p.booleanOrNull != null, "Invalid boolean")
        return p.boolean
    }
    private fun primitiveInt(e: JsonElement, min: Int, max: Int): Int {
        val p = e.jsonPrimitive; val value = p.intOrNull
        check(!p.isString && value != null && value in min..max, "Invalid integer")
        return value!!
    }
    private fun JsonObject.int(key: String, min: Int = 0, max: Int = Int.MAX_VALUE): Int = primitiveInt(getValue(key), min, max)
    private fun JsonObject.number(key: String, positive: Boolean = false): Double {
        val p = getValue(key).jsonPrimitive; val n = p.doubleOrNull
        check(!p.isString && n != null && n.isFinite() && (if (positive) n > 0 else n >= 0), "Invalid number")
        return n!!
    }
    private fun JsonObject.counter(key: String): Long {
        val n = number(key); check(n <= 9007199254740991.0 && n % 1 == 0.0, "Invalid counter")
        return n.toLong()
    }
    private fun JsonObject.us(key: String): Long? = if (getValue(key) == JsonNull) null else {
        val n = number(key); check(n <= Long.MAX_VALUE / 1000.0, "Invalid duration"); (n * 1000).toLong()
    }
    private fun JsonObject.keys(): Set<KeyCode> {
        val list = getValue("keys").jsonArray.map { e ->
            val code = primitiveInt(e, 0, 65535)
            KeyCode.entries.firstOrNull { it.wireCode == code } ?: throw WireFormatException("Unknown key")
        }
        check(list.size == list.toSet().size, "Duplicate key"); return list.toSet()
    }
    private inline fun <reified T : Enum<T>> JsonObject.enumSet(key: String): Set<T> {
        val list = getValue(key).jsonArray.map { e ->
            val p = e.jsonPrimitive; check(p.isString, "Expected enum string"); enumValueOf<T>(p.content)
        }
        check(list.size == list.toSet().size, "Duplicate enum"); return list.toSet()
    }
}
