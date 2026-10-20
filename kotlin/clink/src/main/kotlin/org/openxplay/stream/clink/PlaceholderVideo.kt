// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.clink

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.openxplay.stream.core.*
import org.openxplay.stream.core.codec.AvcConfig
import org.openxplay.stream.core.codec.Nal

/** Immutable, repeatable access units; the first frame must be an IDR. */
public interface PlaceholderVideo {
    public val format: VideoFormat
    public val frames: List<ByteArray>
    public val fps: Int
}

/** Bundled offline-generated black Constrained Baseline 3.1, 720p30, one-second GOP. */
public class BundledPlaceholderVideo : PlaceholderVideo {
    override val fps: Int = 30
    override val format: VideoFormat
    override val frames: List<ByteArray>
    public val avcConfig: AvcConfig
    init {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/placeholder-1280x720.h264")) {
            "missing_placeholder"
        }.use { it.readBytes() }
        val units = Nal.units(bytes, Nal.Format.ANNEX_B).toList()
        val sps = units.first { it[0].toInt() and 31 == 7 }
        val pps = units.first { it[0].toInt() and 31 == 8 }
        val config = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
                out.writeShort(sps.size); out.write(sps); out.writeByte(1)
                out.writeShort(pps.size); out.write(pps)
            }
        }.toByteArray()
        avcConfig = AvcConfig.parse(config)
        format = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264, config, fps.toDouble())
        val accessUnits = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        fun flush() { if (current.size() > 0) { accessUnits += current.toByteArray(); current = ByteArrayOutputStream() } }
        for (unit in units) {
            val type = unit[0].toInt() and 31
            if (type == 9) flush()
            // The delimiter only marks the boundary; keeping it in front of the slice hid the keyframe
            // from the head unit, which inspects byte 4 of every access unit (2026-10-19).
            if (type in listOf(1, 5, 6)) {
                DataOutputStream(current).apply { writeInt(unit.size); write(unit) }
            }
        }
        flush()
        frames = accessUnits.toList()
        require(frames.size == 30 && Nal.isKeyFrame(frames.first(), VideoCodec.H264))
    }
}
