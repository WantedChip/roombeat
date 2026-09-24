package com.roombeat.app.network.multicast

import android.util.Log
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Framing strategy for transmitting audio chunk datagrams over UDP multicast.
 */
enum class AudioFraming {
    /**
     * Standard JSON envelope serialized as [RoomBeatPacket.AudioChunk] with Base64-encoded Opus frame.
     */
    JSON,

    /**
     * Ultra-compact binary header: Magic 'RBAC' (4B) + Version (1B) + Seq (8B) + T_target (8B) + Length (4B) + raw Opus bytes.
     */
    BINARY
}

/**
 * High-throughput UDP Multicast Audio Broadcaster for RoomBeat.
 *
 * Transmits 20ms Opus-compressed audio chunks (`AUDIO_CHUNK`) from the host device
 * to all connected peer clients simultaneously with zero broadcast fan-out overhead.
 * Decoupled from physical Wi-Fi hardware via [MulticastSocketWrapper] for 100% JVM headless testing.
 */
class MulticastBroadcaster(
    val multicastAddress: String = DEFAULT_MULTICAST_ADDRESS,
    val port: Int = DEFAULT_MULTICAST_PORT,
    private val socketWrapper: MulticastSocketWrapper = RealMulticastSocketWrapper(),
    var framing: AudioFraming = AudioFraming.JSON,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    companion object {
        private const val TAG = "MulticastBroadcaster"

        const val DEFAULT_MULTICAST_ADDRESS = "239.255.42.99"
        const val DEFAULT_MULTICAST_PORT = 9876

        /**
         * Magic 4-byte header identifying binary RoomBeat Audio Chunk ("RBAC").
         */
        val BINARY_MAGIC = byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte())
        const val BINARY_VERSION: Byte = 1
        const val BINARY_HEADER_SIZE = 4 + 1 + 8 + 8 + 4 // 25 bytes

        /**
         * Encodes an audio chunk into a compact binary datagram.
         */
        fun encodeBinary(
            seq: Long,
            targetPresentationTime: Long,
            opusData: ByteArray,
            offset: Int = 0,
            length: Int = opusData.size - offset
        ): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= opusData.size) {
                "Invalid offset/length: offset=$offset, length=$length, size=${opusData.size}"
            }
            val buffer = ByteBuffer.allocate(BINARY_HEADER_SIZE + length)
            buffer.put(BINARY_MAGIC)
            buffer.put(BINARY_VERSION)
            buffer.putLong(seq)
            buffer.putLong(targetPresentationTime)
            buffer.putInt(length)
            buffer.put(opusData, offset, length)
            return buffer.array()
        }

        /**
         * Encodes an audio chunk into a [RoomBeatPacket.AudioChunk] JSON datagram.
         */
        fun encodeJson(
            seq: Long,
            targetPresentationTime: Long,
            opusData: ByteArray,
            offset: Int = 0,
            length: Int = opusData.size - offset
        ): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= opusData.size) {
                "Invalid offset/length: offset=$offset, length=$length, size=${opusData.size}"
            }
            val slice = if (offset == 0 && length == opusData.size) {
                opusData
            } else {
                opusData.copyOfRange(offset, offset + length)
            }
            val base64Opus = Base64.getEncoder().encodeToString(slice)
            val packet = RoomBeatPacket.AudioChunk(
                seq = seq,
                opusFrame = base64Opus,
                targetPresentationTime = targetPresentationTime
            )
            return PacketSerializer.serializeToBytes(packet)
        }
    }

    private val isClosed = AtomicBoolean(false)
    private val _totalPacketsSent = AtomicLong(0L)
    private val _totalBytesSent = AtomicLong(0L)
    private val _lastSentSeq = AtomicLong(-1L)
    private val _lastSentTimestampMs = AtomicLong(0L)

    val isSocketClosed: Boolean get() = isClosed.get() || socketWrapper.isClosed
    val totalPacketsSent: Long get() = _totalPacketsSent.get()
    val totalBytesSent: Long get() = _totalBytesSent.get()
    val lastSentSeq: Long get() = _lastSentSeq.get()
    val lastSentTimestampMs: Long get() = _lastSentTimestampMs.get()

    /**
     * Non-blocking transmit method: broadcasts an audio chunk over UDP multicast.
     * Uses current [framing] strategy (default: [AudioFraming.JSON]).
     *
     * @return true if transmitted successfully, false if closed or error.
     */
    fun sendAudioChunk(
        seq: Long,
        targetPresentationTime: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size - offset
    ): Boolean {
        if (isClosed.get()) return false
        return try {
            val bytes = when (framing) {
                AudioFraming.BINARY -> encodeBinary(seq, targetPresentationTime, opusData, offset, length)
                AudioFraming.JSON -> encodeJson(seq, targetPresentationTime, opusData, offset, length)
            }
            socketWrapper.send(bytes, multicastAddress, port)
            _totalPacketsSent.incrementAndGet()
            _totalBytesSent.addAndGet(bytes.size.toLong())
            _lastSentSeq.set(seq)
            _lastSentTimestampMs.set(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            if (!isClosed.get()) {
                Log.w(TAG, "Failed to broadcast audio chunk seq=$seq: ${e.message}")
            }
            false
        }
    }

    /**
     * Non-blocking transmit method: broadcasts a [RoomBeatPacket.AudioChunk] over UDP multicast.
     *
     * @return true if transmitted successfully, false if closed or error.
     */
    fun sendPacket(packet: RoomBeatPacket.AudioChunk): Boolean {
        if (isClosed.get()) return false
        return try {
            val bytes = PacketSerializer.serializeToBytes(packet)
            socketWrapper.send(bytes, multicastAddress, port)
            _totalPacketsSent.incrementAndGet()
            _totalBytesSent.addAndGet(bytes.size.toLong())
            _lastSentSeq.set(packet.seq)
            _lastSentTimestampMs.set(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            if (!isClosed.get()) {
                Log.w(TAG, "Failed to broadcast packet seq=${packet.seq}: ${e.message}")
            }
            false
        }
    }

    /**
     * Coroutine-friendly asynchronous transmit method on IO dispatcher.
     */
    suspend fun sendAudioChunkAsync(
        seq: Long,
        targetPresentationTime: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size - offset
    ): Boolean = withContext(ioDispatcher) {
        sendAudioChunk(seq, targetPresentationTime, opusData, offset, length)
    }

    /**
     * Coroutine-friendly asynchronous transmit method on IO dispatcher.
     */
    suspend fun sendPacketAsync(packet: RoomBeatPacket.AudioChunk): Boolean = withContext(ioDispatcher) {
        sendPacket(packet)
    }

    /**
     * Resets transmission statistics counters.
     */
    fun resetStats() {
        _totalPacketsSent.set(0L)
        _totalBytesSent.set(0L)
        _lastSentSeq.set(-1L)
        _lastSentTimestampMs.set(0L)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                socketWrapper.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket wrapper: ${e.message}")
            }
        }
    }
}
