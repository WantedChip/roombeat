package com.roombeat.app.network.multicast

import android.content.Context
import android.util.Log
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.system.DefaultLockFactory
import com.roombeat.app.system.LockHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed payload of an audio chunk received over UDP multicast.
 */
data class AudioChunkData(
    val seq: Long,
    val targetPresentationTime: Long,
    val opusData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunkData
        return seq == other.seq &&
                targetPresentationTime == other.targetPresentationTime &&
                opusData.contentEquals(other.opusData)
    }

    override fun hashCode(): Int {
        var result = seq.hashCode()
        result = 31 * result + targetPresentationTime.hashCode()
        result = 31 * result + opusData.contentHashCode()
        return result
    }
}

/**
 * High-throughput UDP Multicast Audio Receiver for RoomBeat.
 *
 * Joins the multicast group, manages the [WifiManager.MulticastLock] lifecycle,
 * runs a non-blocking packet receive loop on [Dispatchers.IO], parses incoming audio
 * frames, and bridges them directly into the native jitter buffer / audio engine.
 */
class MulticastReceiver(
    val multicastAddress: String = MulticastBroadcaster.DEFAULT_MULTICAST_ADDRESS,
    val port: Int = MulticastBroadcaster.DEFAULT_MULTICAST_PORT,
    socketWrapper: MulticastSocketWrapper? = null,
    private val lockHandle: LockHandle? = null,
    private val context: Context? = null,
    var nativeEngine: NativeAudioEngine? = null,
    var jitterBuffer: AudioJitterBuffer? = null,
    var onChunkReceived: ((seq: Long, targetPresentationTime: Long, opusData: ByteArray) -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    companion object {
        private const val TAG = "MulticastReceiver"
        const val MULTICAST_LOCK_TAG = "RoomBeat:MulticastReceiver"
        const val RECEIVE_BUFFER_SIZE = 4096
        const val RECEIVE_TIMEOUT_MS = 200

        /**
         * Safely parses an incoming datagram payload into an [AudioChunkData].
         * Supports both ultra-compact binary framing ("RBAC") and JSON [RoomBeatPacket.AudioChunk].
         * Returns null on any malformed, truncated, or corrupt bytes without throwing.
         */
        fun parsePacket(data: ByteArray): AudioChunkData? {
            if (data.isEmpty()) return null

            // 1. Check for compact binary framing: 'RBAC'
            if (data.size >= MulticastBroadcaster.BINARY_HEADER_SIZE &&
                data[0] == 'R'.code.toByte() &&
                data[1] == 'B'.code.toByte() &&
                data[2] == 'A'.code.toByte() &&
                data[3] == 'C'.code.toByte()
            ) {
                return try {
                    val buffer = ByteBuffer.wrap(data)
                    buffer.get() // 'R'
                    buffer.get() // 'B'
                    buffer.get() // 'A'
                    buffer.get() // 'C'
                    val version = buffer.get()
                    if (version == MulticastBroadcaster.BINARY_VERSION) {
                        val seq = buffer.long
                        val targetPresentationTime = buffer.long
                        val length = buffer.int
                        if (length in 0..buffer.remaining()) {
                            val opus = ByteArray(length)
                            buffer.get(opus)
                            AudioChunkData(seq, targetPresentationTime, opus)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }

            // 2. Check for JSON packet framing: RoomBeatPacket.AudioChunk
            return try {
                val packet = PacketSerializer.deserialize(data)
                if (packet is RoomBeatPacket.AudioChunk) {
                    val opusBytes = try {
                        Base64.getDecoder().decode(packet.opusFrame)
                    } catch (_: Exception) {
                        packet.opusFrame.toByteArray(Charsets.ISO_8859_1)
                    }
                    AudioChunkData(packet.seq, packet.targetPresentationTime, opusBytes)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private val effectiveSocket: MulticastSocketWrapper =
        socketWrapper ?: RealMulticastSocketWrapper(boundPort = port)

    private val effectiveLock: LockHandle? =
        lockHandle ?: context?.let {
            try {
                DefaultLockFactory(it).createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create MulticastLock: ${e.message}")
                null
            }
        }

    private val isRunning = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val jobScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var receiveJob: Job? = null

    private val _receivedPacketsCount = AtomicLong(0L)
    private val _receivedBytesCount = AtomicLong(0L)
    private val _corruptPacketsCount = AtomicLong(0L)
    private val _lastReceivedSeq = AtomicLong(-1L)
    private val _lastReceivedTimestampMs = AtomicLong(0L)

    val isReceiving: Boolean get() = isRunning.get() && !isClosed.get()
    val isLockHeld: Boolean get() = effectiveLock?.isHeld == true
    val receivedPacketsCount: Long get() = _receivedPacketsCount.get()
    val receivedBytesCount: Long get() = _receivedBytesCount.get()
    val corruptPacketsCount: Long get() = _corruptPacketsCount.get()
    val lastReceivedSeq: Long get() = _lastReceivedSeq.get()
    val lastReceivedTimestampMs: Long get() = _lastReceivedTimestampMs.get()

    /**
     * Starts listening for multicast audio chunks.
     * Acquires [WifiManager.MulticastLock], joins the multicast group, and starts the receive loop.
     * Idempotent: safe to call multiple times.
     */
    @Synchronized
    fun start() {
        if (isClosed.get()) throw IllegalStateException("MulticastReceiver is already closed")
        if (isRunning.get()) return

        // 1. Acquire MulticastLock to prevent kernel/Wi-Fi chip packet dropping
        try {
            effectiveLock?.let { lock ->
                if (!lock.isHeld) {
                    lock.acquire()
                    Log.d(TAG, "MulticastLock acquired for audio streaming")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring MulticastLock: ${e.message}")
        }

        // 2. Join the multicast group
        try {
            effectiveSocket.joinGroup(multicastAddress, port)
        } catch (e: Exception) {
            Log.w(TAG, "Error joining multicast group $multicastAddress:$port: ${e.message}")
        }

        isRunning.set(true)

        // 3. Launch receive loop on IO dispatcher
        receiveJob = jobScope.launch {
            receiveLoop()
        }
    }

    private suspend fun receiveLoop() {
        while (isRunning.get() && !isClosed.get() && jobScope.isActive) {
            val datagram = try {
                effectiveSocket.receive(RECEIVE_BUFFER_SIZE, RECEIVE_TIMEOUT_MS)
            } catch (e: Exception) {
                if (isRunning.get() && !isClosed.get()) {
                    Log.w(TAG, "Socket receive error: ${e.message}")
                }
                null
            }

            if (datagram != null && datagram.data.isNotEmpty()) {
                val chunk = parsePacket(datagram.data)
                if (chunk != null) {
                    _receivedPacketsCount.incrementAndGet()
                    _receivedBytesCount.addAndGet(datagram.data.size.toLong())
                    _lastReceivedSeq.set(chunk.seq)
                    _lastReceivedTimestampMs.set(System.currentTimeMillis())

                    // Route directly to jitter buffer if attached
                    try {
                        jitterBuffer?.pushPacket(
                            sequenceNumber = chunk.seq,
                            presentationTimeUs = chunk.targetPresentationTime,
                            payload = chunk.opusData
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error pushing chunk to jitterBuffer: ${e.message}")
                    }

                    // Route to native audio engine if attached
                    try {
                        nativeEngine?.pushAudioChunk(
                            seq = chunk.seq,
                            presentationTimeUs = chunk.targetPresentationTime,
                            opusData = chunk.opusData
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error pushing chunk to nativeEngine: ${e.message}")
                    }

                    // Route to registered listener/consumer callback
                    try {
                        onChunkReceived?.invoke(chunk.seq, chunk.targetPresentationTime, chunk.opusData)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in onChunkReceived callback: ${e.message}")
                    }
                } else {
                    _corruptPacketsCount.incrementAndGet()
                    Log.w(TAG, "Dropped corrupt or unparseable datagram (${datagram.data.size} bytes)")
                }
            }
        }
    }

    /**
     * Stops the receive loop, leaves the multicast group, and releases [WifiManager.MulticastLock].
     * Idempotent: safe to call multiple times.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.get()) return
        isRunning.set(false)

        receiveJob?.cancel()
        receiveJob = null

        // 1. Leave multicast group
        try {
            effectiveSocket.leaveGroup(multicastAddress, port)
        } catch (e: Exception) {
            Log.w(TAG, "Error leaving multicast group: ${e.message}")
        }

        // 2. Reliably release MulticastLock
        try {
            effectiveLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }

    /**
     * Resets packet and byte counters.
     */
    fun resetStats() {
        _receivedPacketsCount.set(0L)
        _receivedBytesCount.set(0L)
        _corruptPacketsCount.set(0L)
        _lastReceivedSeq.set(-1L)
        _lastReceivedTimestampMs.set(0L)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            stop()
            try {
                effectiveSocket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing multicast socket: ${e.message}")
            }
        }
    }
}
