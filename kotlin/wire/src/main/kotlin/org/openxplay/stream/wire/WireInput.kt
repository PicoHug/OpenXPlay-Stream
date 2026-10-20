// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.wire

import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*

/**
 * Accepts one producer per start, with an explicit stop fence.
 * Supplies an initial capability hint and reports each peer declaration to the sink at runtime.
 */
public class WireInput(
    private val transport: WireTransport,
    private val name: String = "wire-input",
    private val client: String = "default",
    initialCapabilities: InputCapabilities = unknownInputCapabilities,
    outputCapabilities: OutputCapabilities = emptyOutputCapabilities,
    private val maxQueuedMessages: Int = 256,
    private val onError: (Throwable) -> Unit = {},
) : ProjectionInput {
    private val lock = Any()
    private var connection: WireConnection? = null
    @Volatile private var targetSink: InputSink? = null
    private var outputCapabilities = outputCapabilities
    private var currentClient = client
    private val sequences = mutableMapOf<Int, UInt>()
    @Volatile private var declared = initialCapabilities
    override val capabilities: InputCapabilities get() = declared

    init { require(name.isNotEmpty() && client.isNotEmpty() && maxQueuedMessages > 0) }

    override fun start(config: SessionConfig, sink: InputSink) {
        synchronized(lock) {
            if (connection != null) return
            targetSink = sink
            var session: SessionState? = null
            val formats = mutableSetOf<ScreenId>()
            val audioFormats = mutableSetOf<Int>()
            connection = WireConnection(transport, WireMessage.Hello(WireRole.CONSUMER, name, ContextNegotiation.client(currentClient)),
                listOf(WireMessage.Capabilities(outputCapabilities), WireMessage.Client(currentClient)), maxQueuedMessages, 8L * 1024 * 1024, 100_000,
                receive@ { message, _ ->
                    val sink = targetSink ?: return@receive
                    when (message) {
                        is WireMessage.Context -> sink.context(message.update)
                        is WireMessage.Unknown -> Unit
                        is WireMessage.InputCapabilitiesMessage -> {
                            declared = message.value
                            sink.inputCapabilities(message.value)
                        }
                        is WireMessage.Session -> {
                            if (session?.let { message.state.id.value < it.id.value } != true) {
                                if (session?.id != message.state.id || message.state.phase == SessionPhase.ENDED) {
                                    formats.clear(); audioFormats.clear()
                                }
                                session = message.state; sink.session(message.state)
                            }
                        }
                        is WireMessage.VideoFormatMessage -> {
                            if (message.format == null) formats.remove(message.screen) else formats.add(message.screen)
                            sink.videoFormat(message.screen, message.format)
                        }
                        is WireMessage.AudioFormatMessage -> {
                            if (message.format == null) audioFormats.remove(message.stream) else audioFormats.add(message.stream)
                            sink.audioFormat(message.stream, message.format)
                        }
                        is WireMessage.Microphone -> sink.microphone(message.stream, message.request)
                        // The Kotlin hub has no display-settings or view-area routing; Android passes them in-process.
                        is WireMessage.DisplaySettings, is WireMessage.ViewAreaMessage -> Unit
                        is WireMessage.Binary -> {
                            val b = message.value; val state = session
                            when (b.header.kind) {
                                BinaryKind.VIDEO -> {
                                    val screen = ScreenId(b.header.stream)
                                    if (state == null || state.phase == SessionPhase.ENDED || screen !in formats)
                                        throw WireFormatException("Video requires active session and format")
                                    sink.videoFrame(VideoFrame(screen, state.id, b.payload, b.header.ptsUs,
                                        b.header.flags and OxpWebCodec.KEYFRAME != 0, System.nanoTime()))
                                }
                                BinaryKind.AUDIO -> {
                                    if (state == null || state.phase == SessionPhase.ENDED || b.header.stream !in audioFormats)
                                        throw WireFormatException("Audio requires active session and format")
                                    sink.audioPacket(AudioPacket(b.header.stream, state.id, b.payload, b.header.ptsUs))
                                }
                                else -> throw WireFormatException("Unexpected producer binary kind")
                            }
                        }
                        else -> throw WireFormatException("Unexpected producer message")
                    }
                }, { _, _ -> }, onError).also { it.start() }
        }
    }

    override fun reconfigure(config: SessionConfig): ReconfigureResult =
        if (declared.reconfigure == ReconfigureCost.UNSUPPORTED) ReconfigureResult.UNSUPPORTED else ReconfigureResult.RESTART_SESSION

    override fun restart(config: SessionConfig, sink: InputSink, authorized: Boolean) {
        synchronized(lock) {
            targetSink = sink
            if (authorized && declared.reconfigure != ReconfigureCost.UNSUPPORTED) connection?.enqueue(WireMessage.Restart)
        }
    }

    /** Updates consumer capabilities and queues the declaration without caller-thread I/O. */
    public fun sendCapabilities(value: OutputCapabilities): Boolean = synchronized(lock) {
        outputCapabilities = value; connection?.enqueue(WireMessage.Capabilities(value)) ?: false
    }
    public fun sendClient(value: String): Boolean = synchronized(lock) {
        require(value.isNotEmpty()); currentClient = value
        connection?.enqueue(WireMessage.Client(value)) ?: false
    }
    public fun sendStats(value: OutputStats): Boolean = synchronized(lock) {
        connection?.enqueue(WireMessage.Stats(value)) ?: false
    }

    override fun control(event: ControlEvent): Boolean = synchronized(lock) {
        val run = connection ?: return false
        val message = when (event) {
            is ControlEvent.KeyFrameRequest -> WireMessage.Keyframe(event.screen)
            is ControlEvent.Touch -> binary(BinaryKind.TOUCH, event.screen.value, OxpWebCodec.encodeTouch(event.contacts))
            is ControlEvent.Key -> binary(BinaryKind.KEY, 0, OxpWebCodec.encodeKey(KeyPayload(event.code, event.down)))
            is ControlEvent.MicPacket -> binary(BinaryKind.MICROPHONE, event.stream, event.data, event.ptsUs)
        }
        val accepted = if (currentClient != event.origin.clientId) {
            if (event.origin.clientId.isEmpty()) return false
            run.enqueue(listOf(WireMessage.Client(event.origin.clientId), message)).also {
                if (it) currentClient = event.origin.clientId
            }
        } else run.enqueue(message)
        accepted
    }
    override fun stop() {
        val old = synchronized(lock) { connection.also { connection = null; targetSink = null } }
        old?.close()
    }
    private fun binary(kind: BinaryKind, stream: Int, payload: ByteArray, ptsUs: Long = 0): WireMessage {
        val sequence = sequences[stream] ?: 0u; sequences[stream] = sequence + 1u
        return WireMessage.Binary(BinaryMessage(BinaryHeader(kind, stream, 0, sequence, ptsUs,
            System.nanoTime() / 1000), payload))
    }
}
