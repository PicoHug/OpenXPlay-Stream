// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14.

package org.openxplay.stream.clink

import java.util.ArrayDeque
import org.openxplay.stream.core.*
import org.openxplay.stream.core.audio.PcmMixer

/** Dedicated, paced voice sender. Callback work only retains commands in a bounded queue. */
internal class VoiceDelivery {
    private sealed interface Command {
        data class Format(val stream: Int, val format: AudioFormat?) : Command
        data class Packet(val packet: AudioPacket, val arrivalUs: Long) : Command
        data object Reset : Command
    }
    private val commands = ArrayDeque<Command>()
    private var bytes = 0
    @Synchronized fun format(stream: Int, format: AudioFormat?) { commands.addLast(Command.Format(stream, format)) }
    @Synchronized fun packet(packet: AudioPacket) {
        if (packet.data.size > 192_000) return
        while (bytes + packet.data.size > 192_000) {
            val old = commands.firstOrNull { it is Command.Packet } ?: break
            commands.remove(old); bytes -= (old as Command.Packet).packet.data.size
        }
        commands.addLast(Command.Packet(packet, System.nanoTime() / 1000)); bytes += packet.data.size
    }
    @Synchronized fun reset() { commands.clear(); bytes = 0; commands.addLast(Command.Reset) }
    /** Queued commands and PCM bytes, for diagnostics and tests. */
    @Synchronized fun queued(): Pair<Int, Int> = commands.size to bytes
    @Synchronized private fun peek(): Command? = commands.peekFirst()
    @Synchronized private fun poll(): Command? = commands.pollFirst()?.also {
        if (it is Command.Packet) bytes -= it.packet.data.size
    }

    fun run(active: () -> Boolean, session: () -> ClinkSession?, valid: (AudioPacket) -> Boolean) {
        var mixer = PcmMixer()
        val streams = mutableSetOf<Int>()
        val formats = mutableMapOf<Int, AudioFormat>()
        val tail = ArrayDeque<ByteArray>()
        var started: ClinkSession? = null
        var hasData = false
        val buffers = mutableMapOf<Int, VoiceStream>()
        val endedMixer = mutableSetOf<Int>()
        val pacer = VoicePacer()
        var next = 0L
        var epoch = 0L
        var frames = 0L
        fun finish() {
            val owner = started ?: return
            if (owner.isPlaying) {
                if (tail.isNotEmpty()) {
                    val data = tail.fold(byteArrayOf()) { a, b -> a + b }
                    owner.sendVoice(data, waitForAck = true)
                    val end = maxOf(epoch + frames * 20_000_000, System.nanoTime() + tail.size * 20_000_000L)
                    while (active() && owner.isPlaying && System.nanoTime() < end) Thread.sleep(2)
                }
                if (owner.isPlaying) owner.voiceState(false)
            }
            tail.clear(); started = null; frames = 0
        }
        while (active()) {
            val owner = session()
            if (started != null && (started !== owner || !started!!.isPlaying)) {
                tail.clear(); started = null; mixer = PcmMixer(); streams.clear(); formats.clear(); buffers.clear(); endedMixer.clear(); hasData = false
            }
            while (true) {
                val head = peek() ?: break
                if (head is Command.Format) {
                    buffers[head.stream]?.let { buffer ->
                        buffer.end()
                        if (buffer.drained && endedMixer.add(head.stream)) mixer.endStream(head.stream)
                    }
                    if (head.format != null && (buffers[head.stream]?.drained == false ||
                        !mixer.streamDrained(head.stream))) break
                }
                when (val command = poll() ?: break) {
                    Command.Reset -> {
                        finish(); mixer = PcmMixer(); streams.clear(); formats.clear()
                        buffers.clear(); endedMixer.clear(); hasData = false
                    }
                    is Command.Format -> {
                        streams.remove(command.stream)
                        formats.remove(command.stream)
                        val f = command.format
                        if (f != null && (f.role == AudioRole.VOICE || f.role == AudioRole.TELEPHONY) &&
                            f.codec == AudioCodec.PCM_S16LE &&
                            f.sampleRate in 8000..48000 && f.channels in 1..2) {
                            mixer.removeStream(command.stream)
                            mixer.addStream(command.stream, 16000, 1)
                            streams.add(command.stream); formats[command.stream] = f
                            buffers[command.stream] = VoiceStream(f.sampleRate, f.channels)
                            endedMixer.remove(command.stream)
                        }
                    }
                    is Command.Packet -> if (command.packet.stream in streams && valid(command.packet)) {
                        buffers[command.packet.stream]?.push(command.packet.data, command.arrivalUs)
                        hasData = true
                    }
                }
            }
            if (owner == null || !owner.audioReady) { Thread.sleep(5); continue }
            val now = System.nanoTime()
            if (now < next) { Thread.sleep(2); continue }
            if (!hasData && started == null) { Thread.sleep(2); continue }
            fun readFrame(): ByteArray? {
                for ((stream, buffer) in buffers) {
                    buffer.read(System.nanoTime() / 1000)?.let { mixer.feed(stream, it) }
                    if (buffer.drained && endedMixer.add(stream)) mixer.endStream(stream)
                }
                return mixer.readFrame()
            }
            if (started == null && buffers.values.none { it.ready }) { Thread.sleep(2); continue }
            val frame = readFrame()
            if (frame == null) { finish(); hasData = false; Thread.sleep(2); continue }
            if (started == null) {
                owner.voiceState(true); started = owner; epoch = System.nanoTime(); frames = 0
                pacer.reset(epoch / 1000)
                tail.addLast(frame); frames++
                // Prime 60 ms immediately, retaining one frame for the acknowledged tail.
                repeat(3) { readFrame()?.let { tail.addLast(it); frames++ } }
                if (tail.size > 1) {
                    val data = java.io.ByteArrayOutputStream()
                    while (tail.size > 1) data.write(tail.removeFirst())
                    owner.sendVoice(data.toByteArray())
                }
            } else {
                tail.addLast(frame); frames++
                if (tail.size > 1) owner.sendVoice(tail.removeFirst())
            }
            next = pacer.next(System.nanoTime() / 1000) * 1000
        }
    }
}
