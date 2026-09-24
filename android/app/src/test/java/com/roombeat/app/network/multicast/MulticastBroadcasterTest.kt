package com.roombeat.app.network.multicast

import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class MulticastBroadcasterTest {

    private lateinit var fakeSocket: FakeMulticastSocketWrapper
    private lateinit var broadcaster: MulticastBroadcaster
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        fakeSocket = FakeMulticastSocketWrapper()
        broadcaster = MulticastBroadcaster(
            multicastAddress = MulticastBroadcaster.DEFAULT_MULTICAST_ADDRESS,
            port = MulticastBroadcaster.DEFAULT_MULTICAST_PORT,
            socketWrapper = fakeSocket,
            framing = AudioFraming.JSON,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun defaultConfiguration_isConfiguredProperly() {
        assertEquals("239.255.42.99", broadcaster.multicastAddress)
        assertEquals(9876, broadcaster.port)
        assertEquals(AudioFraming.JSON, broadcaster.framing)
        assertEquals(0L, broadcaster.totalPacketsSent)
        assertEquals(0L, broadcaster.totalBytesSent)
        assertEquals(-1L, broadcaster.lastSentSeq)
        assertFalse(broadcaster.isSocketClosed)
    }

    @Test
    fun sendAudioChunk_jsonFraming_transmitsValidJsonPacket() {
        val opusBytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val success = broadcaster.sendAudioChunk(
            seq = 42L,
            targetPresentationTime = 1_000_000L,
            opusData = opusBytes
        )

        assertTrue(success)
        assertEquals(1L, broadcaster.totalPacketsSent)
        assertEquals(42L, broadcaster.lastSentSeq)
        assertEquals(1, fakeSocket.sentPackets.size)

        val sentPacket = fakeSocket.sentPackets[0]
        assertEquals("239.255.42.99", sentPacket.address)
        assertEquals(9876, sentPacket.port)

        val packet = PacketSerializer.deserialize(sentPacket.data) as? RoomBeatPacket.AudioChunk
        assertNotNull(packet)
        assertEquals(42L, packet!!.seq)
        assertEquals(1_000_000L, packet.targetPresentationTime)
        val decodedOpus = Base64.getDecoder().decode(packet.opusFrame)
        assertArrayEquals(opusBytes, decodedOpus)
    }

    @Test
    fun sendAudioChunk_binaryFraming_transmitsValidRbacDatagram() {
        broadcaster.framing = AudioFraming.BINARY
        val opusBytes = byteArrayOf(0x7F, 0x01, 0x02, 0x03)
        val success = broadcaster.sendAudioChunk(
            seq = 100L,
            targetPresentationTime = 5_555_555L,
            opusData = opusBytes
        )

        assertTrue(success)
        assertEquals(1, fakeSocket.sentPackets.size)
        val sentData = fakeSocket.sentPackets[0].data

        // Verify binary header
        val buffer = ByteBuffer.wrap(sentData)
        val magic = ByteArray(4)
        buffer.get(magic)
        assertArrayEquals(MulticastBroadcaster.BINARY_MAGIC, magic)
        assertEquals(MulticastBroadcaster.BINARY_VERSION, buffer.get())
        assertEquals(100L, buffer.long)
        assertEquals(5_555_555L, buffer.long)
        assertEquals(4, buffer.int)
        val payload = ByteArray(4)
        buffer.get(payload)
        assertArrayEquals(opusBytes, payload)
    }

    @Test
    fun sendPacket_transmitsPrebuiltAudioChunkPacket() {
        val base64Data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val chunk = RoomBeatPacket.AudioChunk(
            seq = 77L,
            opusFrame = base64Data,
            targetPresentationTime = 888_000L
        )

        val success = broadcaster.sendPacket(chunk)
        assertTrue(success)
        assertEquals(1L, broadcaster.totalPacketsSent)
        assertEquals(77L, broadcaster.lastSentSeq)

        val parsed = PacketSerializer.deserialize(fakeSocket.sentPackets[0].data) as? RoomBeatPacket.AudioChunk
        assertNotNull(parsed)
        assertEquals(77L, parsed!!.seq)
        assertEquals(base64Data, parsed.opusFrame)
    }

    @Test
    fun sendAudioChunk_withOffsetAndLength_onlyTransmitsSubarray() {
        val fullData = byteArrayOf(0, 0, 11, 22, 33, 0, 0)
        broadcaster.sendAudioChunk(
            seq = 5L,
            targetPresentationTime = 20_000L,
            opusData = fullData,
            offset = 2,
            length = 3
        )

        val packet = PacketSerializer.deserialize(fakeSocket.sentPackets[0].data) as RoomBeatPacket.AudioChunk
        val decoded = Base64.getDecoder().decode(packet.opusFrame)
        assertArrayEquals(byteArrayOf(11, 22, 33), decoded)
    }

    @Test
    fun hostTransmits50PacketsPerSecond_verifiesFramePacingAndCounters() {
        // Transmit 50 consecutive 20ms audio frames (representing 1 full second of audio streaming)
        val frameDurationUs = 20_000L // 20ms
        val basePresentationUs = 10_000_000L
        val dummyOpusFrame = ByteArray(320) { it.toByte() } // Standard 128kbps Opus 20ms frame

        for (seq in 0L until 50L) {
            val presentationTime = basePresentationUs + (seq * frameDurationUs)
            val success = broadcaster.sendAudioChunk(
                seq = seq,
                targetPresentationTime = presentationTime,
                opusData = dummyOpusFrame
            )
            assertTrue(success)
        }

        assertEquals(50L, broadcaster.totalPacketsSent)
        assertEquals(49L, broadcaster.lastSentSeq)
        assertEquals(50, fakeSocket.sentPackets.size)
        assertTrue(broadcaster.totalBytesSent > 50 * 320)
    }

    @Test
    fun asyncTransmitMethods_executeOnDispatcher() = runTest(testDispatcher) {
        val opusBytes = byteArrayOf(9, 8, 7)
        val success1 = broadcaster.sendAudioChunkAsync(1L, 1000L, opusBytes)
        assertTrue(success1)

        val chunk = RoomBeatPacket.AudioChunk(2L, "AQID", 2000L)
        val success2 = broadcaster.sendPacketAsync(chunk)
        assertTrue(success2)

        assertEquals(2L, broadcaster.totalPacketsSent)
        assertEquals(2L, broadcaster.lastSentSeq)
    }

    @Test
    fun close_isIdempotent_andStopsFurtherTransmissions() {
        assertFalse(broadcaster.isSocketClosed)

        broadcaster.close()
        assertTrue(broadcaster.isSocketClosed)
        assertTrue(fakeSocket.isClosed)

        // Further sends must fail cleanly
        val dummyOpus = byteArrayOf(1)
        val success = broadcaster.sendAudioChunk(1L, 1L, dummyOpus)
        assertFalse(success)

        // Multiple close calls must be safe
        broadcaster.close()
        assertTrue(broadcaster.isSocketClosed)
    }

    @Test
    fun socketException_isHandledGracefullyWithoutCrashing() {
        // Create a custom failing wrapper
        val failingSocket = object : MulticastSocketWrapper {
            override val isClosed: Boolean = false
            override fun joinGroup(multicastAddress: String, port: Int) {}
            override fun leaveGroup(multicastAddress: String, port: Int) {}
            override fun send(data: ByteArray, targetAddress: String, port: Int) {
                throw IOException("Simulated network link down")
            }
            override fun receive(bufferSize: Int, timeoutMs: Int): MulticastDatagramPacket? = null
            override fun close() {}
        }

        val testBroadcaster = MulticastBroadcaster(socketWrapper = failingSocket)
        val result = testBroadcaster.sendAudioChunk(1L, 1L, byteArrayOf(1, 2))
        assertFalse(result)
        assertEquals(0L, testBroadcaster.totalPacketsSent)
        testBroadcaster.close()
    }

    @Test
    fun resetStats_clearsAllTelemetryCounters() {
        broadcaster.sendAudioChunk(1L, 100L, byteArrayOf(1, 2))
        assertEquals(1L, broadcaster.totalPacketsSent)

        broadcaster.resetStats()
        assertEquals(0L, broadcaster.totalPacketsSent)
        assertEquals(0L, broadcaster.totalBytesSent)
        assertEquals(-1L, broadcaster.lastSentSeq)
        assertEquals(0L, broadcaster.lastSentTimestampMs)
    }
}
