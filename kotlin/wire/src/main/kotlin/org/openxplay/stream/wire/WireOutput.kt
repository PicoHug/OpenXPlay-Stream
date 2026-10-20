// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

package org.openxplay.stream.wire

import org.openxplay.stream.core.*
import org.openxplay.stream.core.wire.*

/**
 * Producer-side binding. [id] must equal the remote consumer's hello name for hub origin fencing.
 * [name] identifies this producer in its outgoing hello.
 * [contextEnabled] opts into hub context delivery; the peer must also negotiate support.
 */
public class WireOutput(
    private val transport: WireTransport,
    override val id: String = "wire-input",
    private val client: String = "default",
    private val name: String = "wire-output",
    private val inputCapabilities: InputCapabilities = unknownInputCapabilities,
    private val maxQueuedMessages: Int = 256,
    private val maxQueuedBytes: Long = 8L * 1024 * 1024,
    private val maxQueueDurationUs: Long = 100_000,
    private val onError: (Throwable) -> Unit = {},
    private val contextEnabled: Boolean = false,
) : ProjectionOutput {
    private val lock = Any()
    private var connection: WireConnection? = null

    init {
        require(id.isNotEmpty() && name.isNotEmpty() && client.isNotEmpty())
        require(maxQueuedMessages > 0 && maxQueuedBytes > 0 && maxQueueDurationUs >= 0)
    }
    override fun open(host: OutputHost) {
        synchronized(lock) {
            if (connection != null) return
            connection = WireConnection(transport, WireMessage.Hello(WireRole.PRODUCER, name, client),
                listOf(WireMessage.InputCapabilitiesMessage(inputCapabilities)), maxQueuedMessages, maxQueuedBytes,
                maxQueueDurationUs, { message, origin ->
                    when (message) {
                        is WireMessage.Unknown -> Unit
                        is WireMessage.Capabilities -> host.capabilities(message.value)
                        is WireMessage.Client -> Unit
                        // Display-settings requests have no Kotlin hub route; Android passes them in-process.
                        is WireMessage.DisplaySettings -> Unit
                        WireMessage.Restart -> if (inputCapabilities.reconfigure != ReconfigureCost.UNSUPPORTED) host.requestRestart(origin)
                        is WireMessage.Keyframe -> host.control(ControlEvent.KeyFrameRequest(message.screen, origin))
                        is WireMessage.Stats -> host.stats(message.value.copy(origin = origin))
                        is WireMessage.Binary -> {
                            val b = message.value
                            val event = when (b.header.kind) {
                                BinaryKind.TOUCH -> ControlEvent.Touch(ScreenId(b.header.stream), OxpWebCodec.decodeTouch(b.payload), origin)
                                BinaryKind.KEY -> OxpWebCodec.decodeKey(b.payload).let { ControlEvent.Key(it.code, it.down, origin) }
                                BinaryKind.MICROPHONE -> ControlEvent.MicPacket(b.header.stream, b.payload, b.header.ptsUs, origin)
                                else -> throw WireFormatException("Unexpected consumer binary kind")
                            }
                            host.control(event)
                        }
                        else -> throw WireFormatException("Unexpected consumer message")
                    }
                }, { screen, origin -> host.control(ControlEvent.KeyFrameRequest(screen, origin)) }, onError, expectedPeerName = id).also { it.start() }
        }
    }
    override fun wantsContext(): Boolean = contextEnabled
    override fun onContext(update: ContextUpdate) { current()?.context(update) }
    override fun onSession(state: SessionState, config: SessionConfig) { current()?.session(state) }
    override fun onVideoFormat(screen: ScreenId, format: VideoFormat?) { current()?.format(screen, format) }
    override fun onVideoFrame(frame: VideoFrame) { current()?.frame(frame) }
    override fun onAudioFormat(stream: Int, format: AudioFormat?) { current()?.audioFormat(stream, format) }
    override fun onAudioPacket(packet: AudioPacket) { current()?.audioPacket(packet) }
    override fun onMicrophone(stream: Int, request: MicRequest?) { current()?.enqueue(WireMessage.Microphone(stream, request)) }
    override fun close() {
        val old = synchronized(lock) { connection.also { connection = null } }
        old?.close()
    }
    private fun current(): WireConnection? = synchronized(lock) { connection }
}
