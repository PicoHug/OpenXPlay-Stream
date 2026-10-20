// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.stream.clink

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.openxplay.stream.clink.protocol.*
import org.openxplay.stream.clink.trace.*
import org.openxplay.stream.core.*
import kotlin.test.*

@Timeout(15)
class ClinkTraceTest {
    @Test fun sensitiveServicesAndUndecryptableFramesHaveNoFields() {
        val secrets = Fields(mapOf(1 to FieldValue.Bytes("012345 private-key accessory-identity cert-body".toByteArray()))).encode()
        for (service in listOf("auth", "cert")) assertTrue(ClinkTraceFields.decode(service, secrets).isEmpty())
        assertTrue(ClinkTraceFields.decode("control", secrets, false).isEmpty())
        val safe = Fields(mapOf(1 to FieldValue.Varint(42uL), 2 to FieldValue.Bytes(ByteArray(100) { 65 }))).encode()
        val decoded = ClinkTraceFields.decode("control", safe)
        assertEquals(ClinkTraceField.Number(42uL), decoded[1])
        assertEquals(ClinkTraceField.Bytes(100, "A".repeat(64)), decoded[2])
        assertTrue(ClinkTraceFields.decode("control", byteArrayOf(-1)).isEmpty())
        ClinkTrace.NONE.record(ClinkTraceEvent.Error(1, 1, "test", "Test", "test"))
    }
    @Test fun loopbackRecordsTimelineWithoutAuthenticationPayloads() {
        val events = CopyOnWriteArrayList<ClinkTraceEvent>()
        val packets = CopyOnWriteArrayList<ByteArray>()
        val ports = freePorts()
        val input = ClipInput(BaselineClip())
        val output = ClinkOutput(StaticClinkLink(ClinkAddresses("127.0.0.1", "127.0.0.1", ports.auth, "secret-accessory")),
            PinProvider { "012345" }, MemoryTrustStore(), ports = ports, vehicleData = true, trace = ClinkTrace { events += it },
            mediaTap = ClinkMediaTap { bytes, offset, length -> packets += bytes.copyOfRange(offset, offset + length) })
        val hub = ProjectionHub(input)
        val peer = SimulatedHeadUnit(ports, sendVehicleSamples = false)
        try {
            hub.start(); hub.addOutput(output)
            peer.await("media trace") { events.filterIsInstance<ClinkTraceEvent.Media>().any { it.rtpPackets > 0 } }
            peer.requestKeyframe()
            peer.screenNotice(5)
            peer.await("screen notice requests a key frame") {
                events.any { it is ClinkTraceEvent.Video && it.event == "keyframe_request" && it.source == "head_unit_m5" }
            }
            peer.touch(0, listOf(PixelContact(1, 640, 360)))
            peer.await("input trace") { events.any { it is ClinkTraceEvent.Input } }
            peer.await("sensor connected") { peer.sensorConnected }
            val undecryptable = Fields(mapOf(1 to FieldValue.Bytes("undecryptable-secret".toByteArray()))).encode()
            peer.sensorBytes(Frame(777, 1, 0, 1, 0, 2, 1, undecryptable).encode())
            peer.await("redacted undecryptable frame") {
                events.filterIsInstance<ClinkTraceEvent.Frame>().any { it.service == "sensor" && it.sequence == 777L }
            }
            assertTrue(events.filterIsInstance<ClinkTraceEvent.Frame>().single { it.sequence == 777L }.fields.isEmpty())
            assertFalse(events.joinToString().contains("undecryptable-secret"))
            val rtsp = events.filterIsInstance<ClinkTraceEvent.Rtsp>()
            assertEquals("TX", rtsp.first().direction)
            assertTrue(rtsp.first().startLine.startsWith("OPTIONS "))
            assertTrue(rtsp.any { it.direction == "RX" })
            val frames = events.filterIsInstance<ClinkTraceEvent.Frame>()
            assertTrue(frames.any { it.service == "control" && it.fields.isNotEmpty() })
            for (service in listOf("auth", "cert")) {
                val redacted = frames.filter { it.service == service }
                assertTrue(redacted.isNotEmpty()); assertTrue(redacted.all { it.fields.isEmpty() })
                assertTrue(redacted.any { it.bodySize > 0 })
            }
            val sockets = events.filterIsInstance<ClinkTraceEvent.Socket>()
            assertTrue(sockets.any { it.event == "LISTEN" })
            assertTrue(sockets.any { it.event == "ACCEPTED" })
            assertTrue(sockets.indexOfFirst { it.event == "DIAL" } < sockets.indexOfFirst { it.event == "CONNECTED" })
            assertTrue(events.any { it is ClinkTraceEvent.Negotiation })
            assertTrue(packets.isNotEmpty()); assertEquals(0x80, packets.first()[0].toInt() and 255)
            assertEquals(0x47, packets.first()[12].toInt() and 255)
            assertTrue(events.map { it.session }.distinct().size == 1)
            assertFalse(events.joinToString().contains("012345"))
            assertFalse(events.joinToString().contains("secret-accessory"))
            peer.assertHealthy()
        } finally { peer.expectRelayClose(); hub.stop(); peer.close() }
    }
}
