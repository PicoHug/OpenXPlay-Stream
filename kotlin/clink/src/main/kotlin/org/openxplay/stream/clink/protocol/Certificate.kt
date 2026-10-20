// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink.protocol

import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.*

/** Structural checks only; no manufacturer signature or vehicle identity verification. */
public object Certificate {
    /** Returns the head unit's self-described identity (names and ids), which nothing verifies. */
    public fun check(body: ByteArray): Map<String, String> {
        val encoded = Fields.decode(body).bytes(1)
        require(encoded.size <= 87384 && encoded.size % 4 == 0)
        val raw = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encode(raw).contentEquals(encoded))
        require(raw.size in 1..65536)
        val obj = jsonObject(raw)
        require(obj.keys.all { it in setOf("content", "signature", "signAlgorithm", "version", "cryptoConfigIndex", "salt") })
        val version = obj["version"] as? JsonPrimitive ?: error("invalid_certificate")
        require(!version.isString && version.intOrNull == 1)
        require(text(obj, "signAlgorithm", 32) == "SHA256withRSA")
        text(obj, "cryptoConfigIndex", 128)
        if ("salt" in obj) text(obj, "salt", 256)
        fun signedBytes(key: String, limit: Int): ByteArray {
            val array = obj[key] as? JsonArray ?: error("invalid_certificate")
            require(array.size in 1..limit)
            return ByteArray(array.size) { n ->
                val v = array[n] as? JsonPrimitive ?: error("invalid_certificate")
                require(!v.isString)
                val value = v.intOrNull ?: error("invalid_certificate")
                require(value in -128..127); value.toByte()
            }
        }
        require(signedBytes("signature", 1024).size >= 256)
        val metadata = jsonObject(signedBytes("content", 32768))
        require(metadata.keys == setOf("short_name", "product_name", "vendor_name", "vendor_id", "product_id"))
        return metadata.keys.associateWith { text(metadata, it, if (it.endsWith("_id")) 64 else 512) }
    }
    private fun text(obj: JsonObject, key: String, limit: Int): String {
        val value = obj[key] as? JsonPrimitive ?: error("invalid_certificate")
        require(value.isString && value.content.toByteArray().size in 1..limit &&
            value.content.none { Character.isISOControl(it) || it == '\ufffd' })
        return value.content
    }
}

internal fun jsonObject(bytes: ByteArray): JsonObject {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    rejectDuplicateKeys(text)
    return Json.parseToJsonElement(text) as? JsonObject ?: error("invalid_json_object")
}

internal fun rejectDuplicateKeys(text: String) {
    val stack = mutableListOf<MutableSet<String>?>()
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{' -> stack.add(mutableSetOf())
            '[' -> stack.add(null)
            '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
            '"' -> {
                val start = i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') i++
                    i++
                }
                require(i < text.length)
                var next = i + 1
                while (next < text.length && text[next].isWhitespace()) next++
                if (next < text.length && text[next] == ':') {
                    val key = (Json.parseToJsonElement(text.substring(start, i + 1)) as JsonPrimitive).content
                    val keys = stack.lastOrNull() ?: error("invalid_json_key")
                    require(keys.add(key)) { "duplicate_json_key" }
                }
            }
        }
        require(stack.size <= 16) { "network_bootstrap_limit" }
        i++
    }
}
