package com.roombeat.app.network.multicast

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.system.LockHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FakeLockHandle : LockHandle {
    override var isHeld: Boolean = false
    var acquireCount = 0
    var releaseCount = 0
    var isRefCounted = false

    override fun acquire() {
        acquireCount++
        isHeld = true
    }

    override fun release() {
        releaseCount++
        isHeld = false
    }

    override fun setReferenceCounted(refCounted: Boolean) {
        isRefCounted = refCounted
    }
}

class FakeAudioEngineBridge : AudioEngineBridge {
    override fun initEngine(): Int = 0
    override fun startStream(): Int = 0
    override fun stopStream(): Int = 0
    override fun getAudioLatencyMillis(): Int = 0
    override fun teardownEngine(): Int = 0

    val pushedChunks = CopyOnWriteArrayList<AudioChunkData>()

    override fun pushAudioChunk(
        seq: Long,
        presentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        val slice = opusData.copyOfRange(offset, offset + length)
        pushedChunks.add(AudioChunkData(seq, presentationTimeUs, slice))
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MulticastReceiverTest {

    private lateinit var fakeSocket: FakeMulticastSocketWrapper
    private lateinit var fakeLock: FakeLockHandle
    private lateinit var receiver: MulticastReceiver

    @Before
    fun setUp() {
        fakeSocket = FakeMulticastSocketWrapper()
        fakeLock = FakeLockHandle()
        receiver = MulticastReceiver(
            multicastAddress = MulticastBroadcaster.DEFAULT_MULTICAST_ADDRESS,
            port = MulticastBroadcaster.DEFAULT_MULTICAST_PORT,
            socketWrapper = fakeSocket,
            lockHandle = fakeLock
        )
    }

    // --- MulticastLock Lifecycle Tests ---

    @Test
    fun multicastLockLifecycle_acquiredOnStart_releasedOnStop() {
        assertFalse(fakeLock.isHeld)
        assertEquals(0, fakeLock.acquireCount)
        assertEquals(0, fakeLock.releaseCount)

        receiver.start()
        assertTrue(fakeLock.isHeld)
        assertTrue(receiver.isLockHeld)
        assertEquals(1, fakeLock.acquireCount)

        receiver.stop()
        assertFalse(fakeLock.isHeld)
        assertFalse(receiver.isLockHeld)
        assertEquals(1, fakeLock.releaseCount)
    }

    @Test
    fun multicastLockLifecycle_releasedOnClose() {
        receiver.start()
        assertTrue(fakeLock.isHeld)

        receiver.close()
        assertFalse(fakeLock.isHeld)
        assertEquals(1, fakeLock.releaseCount)
    }

    @Test
    fun startAndStop_areIdempotent() {
        receiver.start()
        receiver.start() // Second call must do nothing
        assertEquals(1, fakeLock.acquireCount)

        receiver.stop()
        receiver.stop() // Second call must do nothing
        assertEquals(1, fakeLock.releaseCount)

        receiver.close()
        receiver.close() // Multiple close calls safe
    }

    // --- Packet Parsing Tests ---

    @Test
    fun parsePacket_validBinaryRbacDatagram_parsesCorrectly() {
        val opusBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val binaryData = MulticastBroadcaster.encodeBinary(
            seq = 88L,
            targetPresentationTime = 123456789L,
            opusData = opusBytes
        )

        val chunk = MulticastReceiver.parsePacket(binaryData)
        assertNotNull(chunk)
        assertEquals(88L, chunk!!.seq)
        assertEquals(123456789L, chunk.targetPresentationTime)
        assertArrayEquals(opusBytes, chunk.opusData)
    }

    @Test
    fun parsePacket_validJsonAudioChunk_parsesCorrectly() {
        val opusBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val jsonData = MulticastBroadcaster.encodeJson(
            seq = 101L,
            targetPresentationTime = 987654321L,
            opusData = opusBytes
        )

        val chunk = MulticastReceiver.parsePacket(jsonData)
        assertNotNull(chunk)
        assertEquals(101L, chunk!!.seq)
        assertEquals(987654321L, chunk.targetPresentationTime)
        assertArrayEquals(opusBytes, chunk.opusData)
    }

    @Test
    fun parsePacket_malformedAndCorruptPackets_returnsNullSafely() {
        // Empty bytes
        assertNull(MulticastReceiver.parsePacket(ByteArray(0)))

        // Random garbage bytes
        assertNull(MulticastReceiver.parsePacket(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))

        // Truncated binary header (only 10 bytes instead of 25)
        val truncatedHeader = byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 1, 0, 0, 0, 0, 1)
        assertNull(MulticastReceiver.parsePacket(truncatedHeader))

        // Binary header with invalid version
        val badVersion = ByteBuffer.allocate(25).apply {
            put(MulticastBroadcaster.BINARY_MAGIC)
            put(99.toByte()) // Bad version
            putLong(1L)
            putLong(2L)
            putInt(0)
        }.array()
        assertNull(MulticastReceiver.parsePacket(badVersion))

        // Binary header claiming 500 bytes payload but buffer ends prematurely
        val prematureEnd = ByteBuffer.allocate(25).apply {
            put(MulticastBroadcaster.BINARY_MAGIC)
            put(1.toByte())
            putLong(1L)
            putLong(2L)
            putInt(500) // Claims 500 bytes, but array has 0 remaining
        }.array()
        assertNull(MulticastReceiver.parsePacket(prematureEnd))

        // Invalid JSON
        val malformedJson = "{ \"type\": \"AUDIO_CHUNK\", \"seq\": ".toByteArray()
        assertNull(MulticastReceiver.parsePacket(malformedJson))

        // Wrong packet type
        val wrongType = PacketSerializer.serializeToBytes(RoomBeatPacket.PeerPing(12345L))
        assertNull(MulticastReceiver.parsePacket(wrongType))
    }

    // --- End-to-End Multicast Broadcast and Reception ---

    @Test
    fun broadcastToReceiver_routesToCallback_jitterBuffer_andNativeEngine() {
        val receivedViaCallback = CopyOnWriteArrayList<AudioChunkData>()
        receiver.onChunkReceived = { seq, targetTime, opus ->
            receivedViaCallback.add(AudioChunkData(seq, targetTime, opus))
        }

        val testJitterBuffer = AudioJitterBuffer()
        receiver.jitterBuffer = testJitterBuffer

        val fakeEngineBridge = FakeAudioEngineBridge()
        val testNativeEngine = NativeAudioEngine(fakeEngineBridge)
        receiver.nativeEngine = testNativeEngine

        receiver.start()

        val opusPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val packetData = MulticastBroadcaster.encodeBinary(
            seq = 1L,
            targetPresentationTime = 50_000L,
            opusData = opusPayload
        )

        // Deliver packet via socket queue
        fakeSocket.enqueuePacket(MulticastDatagramPacket(packetData, "239.255.42.99", 9876))

        // Wait for coroutine receive loop to process
        val deadline = System.currentTimeMillis() + 1000
        while (receiver.receivedPacketsCount < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(1L, receiver.receivedPacketsCount)
        assertEquals(1L, receiver.lastReceivedSeq)

        // Verify callback was invoked
        assertEquals(1, receivedViaCallback.size)
        assertEquals(1L, receivedViaCallback[0].seq)
        assertEquals(50_000L, receivedViaCallback[0].targetPresentationTime)
        assertArrayEquals(opusPayload, receivedViaCallback[0].opusData)

        // Verify native engine bridge received chunk
        assertEquals(1, fakeEngineBridge.pushedChunks.size)
        assertEquals(1L, fakeEngineBridge.pushedChunks[0].seq)
        assertArrayEquals(opusPayload, fakeEngineBridge.pushedChunks[0].opusData)

        // Verify jitter buffer stats updated
        val stats = testJitterBuffer.getStats()
        assertEquals(1L, stats.totalPacketsReceived)

        receiver.close()
        testJitterBuffer.close()
    }

    // --- Multi-Peer Concurrent Delivery (8 Clients, 1 Broadcaster, 0 Fan-Out Overhead) ---

    @Test
    fun multiPeerConcurrentDelivery_8ClientsReceiveSimultaneouslyWithZeroFanoutOverhead() {
        val hostSocket = FakeMulticastSocketWrapper()
        val broadcaster = MulticastBroadcaster(socketWrapper = hostSocket)

        val clientCount = 8
        val clientSockets = List(clientCount) { FakeMulticastSocketWrapper() }
        val clientReceivers = List(clientCount) { i ->
            MulticastReceiver(
                socketWrapper = clientSockets[i],
                lockHandle = FakeLockHandle()
            )
        }

        // Link all 8 client sockets to the host multicast socket
        for (clientSocket in clientSockets) {
            hostSocket.link(clientSocket)
        }

        // Start all 8 receivers
        for (clientReceiver in clientReceivers) {
            clientReceiver.start()
        }

        // Host broadcasts 50 packets (20ms frames = 1 full second of audio streaming at 50fps)
        val packetCount = 50
        val dummyOpusFrame = ByteArray(320) { (it % 128).toByte() }

        for (seq in 0 until packetCount) {
            val presentationTimeUs = 10_000_000L + (seq * 20_000L)
            val success = broadcaster.sendAudioChunk(
                seq = seq.toLong(),
                targetPresentationTime = presentationTimeUs,
                opusData = dummyOpusFrame
            )
            assertTrue(success)
        }

        // VERIFY ZERO BROADCAST FAN-OUT OVERHEAD ON HOST:
        // Host socket performed EXACTLY 50 sends (1 write per chunk) — NOT 50 * 8 = 400!
        assertEquals(packetCount.toLong(), broadcaster.totalPacketsSent)
        assertEquals(packetCount, hostSocket.sentPackets.size)

        // Wait for all 8 clients to receive all 50 packets
        val deadline = System.currentTimeMillis() + 3000
        var allDone = false
        while (!allDone && System.currentTimeMillis() < deadline) {
            allDone = clientReceivers.all { it.receivedPacketsCount == packetCount.toLong() }
            if (!allDone) Thread.sleep(20)
        }

        // VERIFY ALL 8 CLIENTS RECEIVED EVERY PACKET CONCURRENTLY
        for (i in 0 until clientCount) {
            val r = clientReceivers[i]
            assertEquals("Client $i should have received all $packetCount packets", packetCount.toLong(), r.receivedPacketsCount)
            assertEquals("Client $i last received seq should be ${packetCount - 1}", (packetCount - 1).toLong(), r.lastReceivedSeq)
            assertEquals("Client $i should have 0 corrupt packets", 0L, r.corruptPacketsCount)
        }

        // Cleanup
        for (clientReceiver in clientReceivers) {
            clientReceiver.close()
        }
        broadcaster.close()
    }

    // --- Graceful Handling of Corrupt / Malformed Packets ---

    @Test
    fun corruptAndMalformedPackets_areHandledGracefullyWithoutCrashing() {
        val receivedChunks = CopyOnWriteArrayList<AudioChunkData>()
        receiver.onChunkReceived = { seq, targetTime, opus ->
            receivedChunks.add(AudioChunkData(seq, targetTime, opus))
        }

        receiver.start()

        // 1. Enqueue 3 completely corrupt datagrams
        fakeSocket.enqueuePacket(MulticastDatagramPacket(byteArrayOf(0x00, 0x01, 0x02), "239.255.42.99", 9876))
        fakeSocket.enqueuePacket(MulticastDatagramPacket("not json at all".toByteArray(), "239.255.42.99", 9876))
        fakeSocket.enqueuePacket(MulticastDatagramPacket(byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 0, 0), "239.255.42.99", 9876))

        // 2. Enqueue 1 valid packet
        val validOpus = byteArrayOf(42, 43, 44)
        val validData = MulticastBroadcaster.encodeBinary(seq = 777L, targetPresentationTime = 999L, opusData = validOpus)
        fakeSocket.enqueuePacket(MulticastDatagramPacket(validData, "239.255.42.99", 9876))

        // Wait for receiver
        val deadline = System.currentTimeMillis() + 1000
        while (receiver.receivedPacketsCount < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(1L, receiver.receivedPacketsCount)
        assertEquals(3L, receiver.corruptPacketsCount)
        assertEquals(777L, receiver.lastReceivedSeq)
        assertEquals(1, receivedChunks.size)
        assertEquals(777L, receivedChunks[0].seq)

        receiver.close()
    }

    @Test
    fun resetStats_clearsAllTelemetryCounters() {
        fakeSocket.enqueuePacket(MulticastDatagramPacket(byteArrayOf(1), "239.255.42.99", 9876))
        val deadline = System.currentTimeMillis() + 500
        while (receiver.corruptPacketsCount < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        receiver.resetStats()
        assertEquals(0L, receiver.receivedPacketsCount)
        assertEquals(0L, receiver.receivedBytesCount)
        assertEquals(0L, receiver.corruptPacketsCount)
        assertEquals(-1L, receiver.lastReceivedSeq)
        assertEquals(0L, receiver.lastReceivedTimestampMs)

        receiver.close()
    }
}
