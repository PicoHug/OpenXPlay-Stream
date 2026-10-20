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
            // A delimiter only marks a boundary; ahead of the slice it hides the keyframe from the head unit,
            // which inspects the first NAL unit of every access unit (2026-10-19).
            if (type in listOf(1, 5, 6)) {
                DataOutputStream(current).apply { writeInt(unit.size); write(unit) }
            }
        }
        flush()
        frames = accessUnits.toList()
        require(frames.size == 30 && Nal.isKeyFrame(frames.first(), VideoCodec.H264))
    }
}

/** Same bundled picture and playback cadence as the AVC placeholder, encoded as Main level 3.1. */
public class BundledHevcPlaceholderVideo : PlaceholderVideo {
    override val fps: Int = 30
    override val format: VideoFormat
    override val frames: List<ByteArray>
    init {
        // From kotlin/clink/src/main/resources:
        // /opt/homebrew/bin/ffmpeg -hide_banner -loglevel error -y -i placeholder-1280x720.h264 -an -c:v libx265 -pix_fmt yuv420p -profile:v main -level:v 3.1 -x265-params 'keyint=30:min-keyint=30:scenecut=0:open-gop=0:repeat-headers=1:aud=0:bframes=0:annexb=1:log-level=error' -f hevc placeholder-1280x720.h265
        val bytes = checkNotNull(javaClass.getResourceAsStream("/placeholder-1280x720.h265")) {
            "missing_hevc_placeholder"
        }.use { it.readBytes() }
        val units = Nal.units(bytes, Nal.Format.ANNEX_B).toList()
        fun type(nal: ByteArray): Int = (nal[0].toInt() ushr 1) and 63
        val config = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                // hvcC: Main, compatibility 0x60000000, level 93, four-byte NAL lengths.
                out.write(byteArrayOf(1, 1, 0x60, 0, 0, 0, 0x90.toByte(), 0, 0, 0, 0, 0, 93,
                    0xf0.toByte(), 0, 0xfc.toByte(), 0xfd.toByte(), 0xf8.toByte(), 0xf8.toByte(), 0, 0, 0x0f, 3))
                for (kind in 32..34) {
                    val nal = units.first { type(it) == kind }
                    out.writeByte(0x80 or kind); out.writeShort(1); out.writeShort(nal.size); out.write(nal)
                }
            }
        }.toByteArray()
        format = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, config, fps.toDouble())
        val accessUnits = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        fun flush() { if (current.size() > 0) { accessUnits += current.toByteArray(); current = ByteArrayOutputStream() } }
        for (unit in units) {
            if (type(unit) in 0..31) {
                // first_slice_segment_in_pic_flag marks a new picture without requiring AUDs.
                require(unit.size >= 3)
                if (unit[2].toInt() and 0x80 != 0) flush()
                DataOutputStream(current).apply { writeInt(unit.size); write(unit) }
            }
        }
        flush()
        frames = accessUnits.toList()
        require(frames.size == 30 && Nal.isKeyFrame(frames.first(), VideoCodec.H265))
    }
}
