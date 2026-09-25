package com.roombeat.app.capture

import android.util.Log
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.audio.codec.OpusConstants
import com.roombeat.app.audio.codec.OpusEncoder
import com.roombeat.app.network.multicast.MulticastBroadcaster
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle states of [CaptureStreamingBridge].
 */
enum class StreamingBridgeState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR
}

/**
 * Diagnostic and performance telemetry statistics for [CaptureStreamingBridge].
 *
 * @param totalFramesProcessed Total number of PCM frames ingested from the capture engine.
 * @param packetsSent Total number of multicast packets successfully broadcast to the network.
 * @param bytesSent Total number of compressed audio payload bytes broadcast.
 * @param silenceFramesSuppressed Total number of frames classified as silent where multicast transmission was suppressed.
 * @param activeFramesSent Total number of active audio frames transmitted.
 * @param localFramesPushed Total number of frames pushed to the local host's jitter buffer.
 * @param encodingErrors Total count of Opus encoding failures.
 * @param broadcastErrors Total count of socket broadcast failures.
 * @param lastSequenceNumber Most recent sequence number assigned.
 * @param lastPresentationTimeUs Most recent presentation timestamp computed (in microseconds).
 * @param avgEncodingTimeMicros Moving/cumulative average encoding duration per 20ms frame (in microseconds).
 */
data class CaptureStreamingStats(
    val totalFramesProcessed: Long = 0L,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val silenceFramesSuppressed: Long = 0L,
    val activeFramesSent: Long = 0L,
    val localFramesPushed: Long = 0L,
    val encodingErrors: Long = 0L,
    val broadcastErrors: Long = 0L,
    val lastSequenceNumber: Long = -1L,
    val lastPresentationTimeUs: Long = 0L,
    val avgEncodingTimeMicros: Double = 0.0
)

/**
 * Configuration descriptor for [CaptureStreamingBridge].
 *
 * @param lanJitterBufferMicros Network presentation lead time added to the monotonic clock (default 120,000 µs = 120ms).
 * @param suppressSilence When true, frames classified as silent by [AudioSilenceDetector] are not transmitted over Wi-Fi.
 * @param incrementSeqOnSilence When true, sequence numbers advance even for suppressed silent frames to maintain timeline alignment.
 * @param feedLocalOnSilence When true, silent frames are also pushed to the local jitter buffer (defaults to false for symmetry).
 * @param initialSeq Starting sequence number (default 0L).
 * @param frameDurationMicros Nominal frame duration in microseconds (default 20,000 µs = 20ms at 48kHz stereo).
 */
data class StreamingBridgeConfig(
    val lanJitterBufferMicros: Long = DEFAULT_LAN_JITTER_BUFFER_MICROS,
    val suppressSilence: Boolean = true,
    val incrementSeqOnSilence: Boolean = true,
    val feedLocalOnSilence: Boolean = false,
    val initialSeq: Long = 0L,
    val frameDurationMicros: Long = DEFAULT_FRAME_DURATION_MICROS
) {
    companion object {
        /** Default LAN lead time: 120ms (120,000 µs). */
        const val DEFAULT_LAN_JITTER_BUFFER_MICROS = 120_000L

        /** Standard 20ms frame duration at 48kHz stereo. */
        const val DEFAULT_FRAME_DURATION_MICROS = 20_000L
    }
}

/**
 * Decoupled facade interface for Opus encoding, enabling 100% deterministic JVM unit testing
 * without native library loading.
 */
interface CaptureOpusEncoder : AutoCloseable {
    fun encode(pcmData: ShortArray, frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES): ByteArray?
    fun encode(pcmData: ShortArray, frameSize: Int, outputBuffer: ByteArray): Int
    fun reset(): Int = 0
    override fun close() {}
}

/**
 * Default production implementation of [CaptureOpusEncoder] delegating to [OpusEncoder].
 */
class DefaultCaptureOpusEncoder(
    private val encoder: OpusEncoder = OpusEncoder()
) : CaptureOpusEncoder {
    override fun encode(pcmData: ShortArray, frameSize: Int): ByteArray? {
        return encoder.encode(pcmData, frameSize)
    }

    override fun encode(pcmData: ShortArray, frameSize: Int, outputBuffer: ByteArray): Int {
        return encoder.encode(pcmData, frameSize, outputBuffer)
    }

    override fun reset(): Int {
        return encoder.reset()
    }

    override fun close() {
        encoder.close()
    }
}

/**
 * Alternative implementation of [CaptureOpusEncoder] delegating to [NativeAudioEngine.encodeFrame].
 */
class NativeAudioEngineCaptureEncoder(
    private val engine: NativeAudioEngine = NativeAudioEngine()
) : CaptureOpusEncoder {
    override fun encode(pcmData: ShortArray, frameSize: Int): ByteArray? {
        return engine.encodeFrame(pcmData)
    }

    override fun encode(pcmData: ShortArray, frameSize: Int, outputBuffer: ByteArray): Int {
        return engine.encodeFrame(pcmData, outputBuffer)
    }
}

/**
 * Decoupled facade interface for UDP multicast packet broadcasting.
 */
interface CaptureBroadcaster : AutoCloseable {
    fun sendAudioChunk(
        seq: Long,
        targetPresentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size
    ): Boolean

    fun sendPacket(packet: RoomBeatPacket.AudioChunk): Boolean = false

    val isClosed: Boolean get() = false

    override fun close() {}
}

/**
 * Default production implementation of [CaptureBroadcaster] wrapping [MulticastBroadcaster].
 */
class MulticastCaptureBroadcaster(
    val broadcaster: MulticastBroadcaster
) : CaptureBroadcaster {
    override fun sendAudioChunk(
        seq: Long,
        targetPresentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        return broadcaster.sendAudioChunk(
            seq = seq,
            targetPresentationTime = targetPresentationTimeUs,
            opusData = opusData,
            offset = offset,
            length = length
        )
    }

    override fun sendPacket(packet: RoomBeatPacket.AudioChunk): Boolean {
        return broadcaster.sendPacket(packet)
    }

    override val isClosed: Boolean get() = broadcaster.isSocketClosed

    override fun close() {
        broadcaster.close()
    }
}

/**
 * Decoupled facade interface for the host's local jitter buffer feeding.
 */
interface CaptureLocalBuffer {
    fun pushPacket(
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ): Boolean
}

/**
 * Default production implementation of [CaptureLocalBuffer] wrapping [AudioJitterBuffer].
 */
class JitterBufferCaptureLocalBuffer(
    val jitterBuffer: AudioJitterBuffer
) : CaptureLocalBuffer {
    override fun pushPacket(
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        return jitterBuffer.pushPacket(
            sequenceNumber = sequenceNumber,
            presentationTimeUs = presentationTimeUs,
            payload = payload,
            offset = offset,
            length = length
        )
    }
}

/**
 * Alternative implementation of [CaptureLocalBuffer] wrapping [NativeAudioEngine.pushAudioChunk].
 */
class NativeEngineCaptureLocalBuffer(
    val engine: NativeAudioEngine = NativeAudioEngine()
) : CaptureLocalBuffer {
    override fun pushPacket(
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        return engine.pushAudioChunk(
            seq = sequenceNumber,
            presentationTimeUs = presentationTimeUs,
            opusData = payload,
            offset = offset,
            length = length
        )
    }
}

/**
 * Bridge streaming real-time PCM audio captured from [SystemAudioCaptureEngine] into Opus frames,
 * timestamping each frame against the monotonic presentation clock ($T_{presentation} = T_{now} + \text{LAN\_LEAD}$),
 * broadcasting `AUDIO_CHUNK` packets over UDP multicast, and feeding the local host's [AudioJitterBuffer]
 * so host speaker and peer devices play in synchronous lock.
 *
 * Implements:
 * 1. 20ms PCM frame ingestion (1920 short samples / 3840 bytes).
 * 2. Real-time Opus encoding with sub-1.5ms execution.
 * 3. Target presentation timestamp calculation:
 *    $$T_{\text{presentation}} = \text{MonotonicClock.nowMicros()} + \text{lanJitterBufferMicros}$$
 * 4. Multicast UDP broadcasting via [CaptureBroadcaster].
 * 5. Local host [AudioJitterBuffer] feeding for synchronized host speaker playback.
 * 6. Wi-Fi bandwidth conservation: suppresses multicast broadcast during silence frames while maintaining sequence number continuity.
 * 7. Full lifecycle management ([start], [stop], [pause], [resume]), decoupled facade architecture, and telemetry tracking.
 */
class CaptureStreamingBridge(
    val config: StreamingBridgeConfig = StreamingBridgeConfig(),
    var encoder: CaptureOpusEncoder = DefaultCaptureOpusEncoder(),
    var broadcaster: CaptureBroadcaster? = null,
    var localBuffer: CaptureLocalBuffer? = null,
    var clock: MonotonicClock = SystemMonotonicClock,
    private val scope: CoroutineScope? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "CaptureStreamingBridge"
    }

    /**
     * Secondary constructor binding to concrete [MulticastBroadcaster] and [AudioJitterBuffer].
     */
    constructor(
        broadcaster: MulticastBroadcaster,
        jitterBuffer: AudioJitterBuffer,
        config: StreamingBridgeConfig = StreamingBridgeConfig(),
        clock: MonotonicClock = SystemMonotonicClock
    ) : this(
        config = config,
        encoder = DefaultCaptureOpusEncoder(),
        broadcaster = MulticastCaptureBroadcaster(broadcaster),
        localBuffer = JitterBufferCaptureLocalBuffer(jitterBuffer),
        clock = clock
    )

    /**
     * Secondary constructor binding to [MulticastBroadcaster] and [NativeAudioEngine].
     */
    constructor(
        broadcaster: MulticastBroadcaster,
        engine: NativeAudioEngine,
        config: StreamingBridgeConfig = StreamingBridgeConfig(),
        clock: MonotonicClock = SystemMonotonicClock
    ) : this(
        config = config,
        encoder = NativeAudioEngineCaptureEncoder(engine),
        broadcaster = MulticastCaptureBroadcaster(broadcaster),
        localBuffer = NativeEngineCaptureLocalBuffer(engine),
        clock = clock
    )

    private val lifecycleLock = Any()
    private val isClosed = AtomicBoolean(false)

    private val _state = MutableStateFlow(StreamingBridgeState.IDLE)
    val state: StateFlow<StreamingBridgeState> = _state.asStateFlow()

    val isStreaming: Boolean get() = _state.value == StreamingBridgeState.RUNNING

    // Sequence numbering
    private val nextSequenceNumber = AtomicLong(config.initialSeq)

    // Telemetry counters
    private val _totalFramesProcessed = AtomicLong(0L)
    private val _packetsSent = AtomicLong(0L)
    private val _bytesSent = AtomicLong(0L)
    private val _silenceFramesSuppressed = AtomicLong(0L)
    private val _activeFramesSent = AtomicLong(0L)
    private val _localFramesPushed = AtomicLong(0L)
    private val _encodingErrors = AtomicLong(0L)
    private val _broadcastErrors = AtomicLong(0L)
    private val _lastSequenceNumber = AtomicLong(-1L)
    private val _lastPresentationTimeUs = AtomicLong(0L)

    // Moving average encoding time
    private val totalEncodeDurationUs = AtomicLong(0L)
    private val totalEncodedFramesCount = AtomicLong(0L)

    private val _statsFlow = MutableStateFlow(CaptureStreamingStats())
    val statsFlow: StateFlow<CaptureStreamingStats> = _statsFlow.asStateFlow()

    val stats: CaptureStreamingStats
        get() = CaptureStreamingStats(
            totalFramesProcessed = _totalFramesProcessed.get(),
            packetsSent = _packetsSent.get(),
            bytesSent = _bytesSent.get(),
            silenceFramesSuppressed = _silenceFramesSuppressed.get(),
            activeFramesSent = _activeFramesSent.get(),
            localFramesPushed = _localFramesPushed.get(),
            encodingErrors = _encodingErrors.get(),
            broadcastErrors = _broadcastErrors.get(),
            lastSequenceNumber = _lastSequenceNumber.get(),
            lastPresentationTimeUs = _lastPresentationTimeUs.get(),
            avgEncodingTimeMicros = avgEncodingTimeMicros
        )

    val avgEncodingTimeMicros: Double
        get() {
            val count = totalEncodedFramesCount.get()
            return if (count > 0L) totalEncodeDurationUs.get().toDouble() / count else 0.0
        }

    /**
     * Optional callback for capture streaming errors.
     */
    var onError: ((message: String, cause: Throwable?) -> Unit)? = null

    /**
     * Optional listener invoked on state change.
     */
    var onStateChanged: ((StreamingBridgeState) -> Unit)? = null

    // Attached capture engine reference
    private var attachedCaptureEngine: SystemAudioCaptureEngine? = null
    private var flowCollectionJob: Job? = null

    /**
     * Starts the capture streaming bridge, transitioning state to [StreamingBridgeState.RUNNING].
     */
    fun start(): Boolean {
        synchronized(lifecycleLock) {
            check(!isClosed.get()) { "CaptureStreamingBridge is closed" }
            if (_state.value == StreamingBridgeState.RUNNING) {
                return true
            }
            updateState(StreamingBridgeState.RUNNING)
            Log.i(TAG, "CaptureStreamingBridge started (LAN lead: ${config.lanJitterBufferMicros}µs)")
            return true
        }
    }

    /**
     * Pauses the bridge, transitioning state to [StreamingBridgeState.PAUSED].
     * Frames ingested while paused are safely ignored.
     */
    fun pause() {
        synchronized(lifecycleLock) {
            if (_state.value == StreamingBridgeState.RUNNING) {
                updateState(StreamingBridgeState.PAUSED)
                Log.i(TAG, "CaptureStreamingBridge paused")
            }
        }
    }

    /**
     * Resumes the bridge from [StreamingBridgeState.PAUSED] to [StreamingBridgeState.RUNNING].
     */
    fun resume() {
        synchronized(lifecycleLock) {
            if (_state.value == StreamingBridgeState.PAUSED) {
                updateState(StreamingBridgeState.RUNNING)
                Log.i(TAG, "CaptureStreamingBridge resumed")
            }
        }
    }

    /**
     * Stops the bridge, transitioning state to [StreamingBridgeState.STOPPED].
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (_state.value == StreamingBridgeState.STOPPED) return
            updateState(StreamingBridgeState.STOPPED)
            detachCaptureEngine()
            Log.i(TAG, "CaptureStreamingBridge stopped")
        }
    }

    /**
     * Attaches directly to a [SystemAudioCaptureEngine] instance via direct callback
     * for zero-latency frame processing on the audio recording thread.
     */
    fun attachCaptureEngine(engine: SystemAudioCaptureEngine) {
        synchronized(lifecycleLock) {
            detachCaptureEngine()
            attachedCaptureEngine = engine
            engine.onAudioFrameCaptured = { frame ->
                processFrame(frame)
            }
            Log.i(TAG, "Attached to SystemAudioCaptureEngine")
        }
    }

    /**
     * Attaches to a [SystemAudioCaptureEngine] using coroutine flow observation.
     */
    fun attachCaptureEngineFlow(engine: SystemAudioCaptureEngine, coroutineScope: CoroutineScope? = scope) {
        synchronized(lifecycleLock) {
            detachCaptureEngine()
            attachedCaptureEngine = engine
            val effectiveScope = coroutineScope ?: CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            flowCollectionJob = effectiveScope.launch {
                engine.audioFrames.collect { frame ->
                    processFrame(frame)
                }
            }
            Log.i(TAG, "Attached to SystemAudioCaptureEngine audioFrames flow")
        }
    }

    /**
     * Detaches any attached capture engine and cancels coroutine flow collection.
     */
    fun detachCaptureEngine() {
        synchronized(lifecycleLock) {
            flowCollectionJob?.cancel()
            flowCollectionJob = null
            attachedCaptureEngine?.let { engine ->
                if (engine.onAudioFrameCaptured != null) {
                    engine.onAudioFrameCaptured = null
                }
            }
            attachedCaptureEngine = null
        }
    }

    /**
     * Ingests and processes a single 20ms captured PCM audio frame.
     *
     * 1. Checks bridge state (returns immediately if not [StreamingBridgeState.RUNNING]).
     * 2. Inspects silence status: if silent and [StreamingBridgeConfig.suppressSilence] is enabled,
     *    suppresses multicast transmission to conserve Wi-Fi bandwidth while advancing sequence numbers.
     * 3. Encodes PCM samples to Opus payload.
     * 4. Calculates target presentation timestamp ($T_{presentation} = T_{now} + \text{LAN\_LEAD}$).
     * 5. Broadcasts [RoomBeatPacket.AudioChunk] over UDP multicast.
     * 6. Pushes the packet to the local host's [AudioJitterBuffer] for synchronous playback.
     */
    fun processFrame(frame: CapturedAudioFrame) {
        if (_state.value != StreamingBridgeState.RUNNING) {
            return
        }

        _totalFramesProcessed.incrementAndGet()

        // 1. Check silence state
        val isSilent = frame.isSilent
        if (isSilent && config.suppressSilence) {
            val suppressedSeq = if (config.incrementSeqOnSilence) {
                nextSequenceNumber.getAndIncrement()
            } else {
                nextSequenceNumber.get()
            }

            _silenceFramesSuppressed.incrementAndGet()
            _lastSequenceNumber.set(suppressedSeq)

            // Optional local feed on silence (defaults to false for perfect symmetry with peers)
            if (config.feedLocalOnSilence) {
                feedLocalSilence(suppressedSeq, frame)
            }

            publishStats()
            return
        }

        // 2. Active audio frame: allocate sequence number
        val seq = nextSequenceNumber.getAndIncrement()
        _lastSequenceNumber.set(seq)

        // 3. Encode PCM16 frame to Opus
        val encodeStartNs = System.nanoTime()
        val opusBytes = try {
            encoder.encode(frame.pcmData, frame.frameSizePerChannel)
        } catch (e: Exception) {
            _encodingErrors.incrementAndGet()
            val errorMsg = "Exception encoding PCM frame seq=$seq: ${e.message}"
            Log.e(TAG, errorMsg, e)
            onError?.invoke(errorMsg, e)
            publishStats()
            return
        }

        val encodeDurationUs = (System.nanoTime() - encodeStartNs) / 1000L
        totalEncodeDurationUs.addAndGet(encodeDurationUs)
        totalEncodedFramesCount.incrementAndGet()

        if (opusBytes == null || opusBytes.isEmpty()) {
            _encodingErrors.incrementAndGet()
            val errorMsg = "Opus encoder returned null or empty bytes for frame seq=$seq"
            Log.w(TAG, errorMsg)
            onError?.invoke(errorMsg, null)
            publishStats()
            return
        }

        // 4. Compute target presentation timestamp
        val nowUs = clock.nowMicros()
        val targetPresentationTimeUs = nowUs + config.lanJitterBufferMicros
        _lastPresentationTimeUs.set(targetPresentationTimeUs)

        // 5. Broadcast over UDP Multicast
        broadcaster?.let { b ->
            try {
                val sent = b.sendAudioChunk(
                    seq = seq,
                    targetPresentationTimeUs = targetPresentationTimeUs,
                    opusData = opusBytes
                )
                if (sent) {
                    _packetsSent.incrementAndGet()
                    _bytesSent.addAndGet(opusBytes.size.toLong())
                    _activeFramesSent.incrementAndGet()
                } else {
                    _broadcastErrors.incrementAndGet()
                }
            } catch (e: Exception) {
                _broadcastErrors.incrementAndGet()
                Log.w(TAG, "Exception broadcasting audio chunk seq=$seq: ${e.message}")
            }
        }

        // 6. Push to local host's jitter buffer
        localBuffer?.let { buffer ->
            try {
                val buffered = buffer.pushPacket(
                    sequenceNumber = seq,
                    presentationTimeUs = targetPresentationTimeUs,
                    payload = opusBytes
                )
                if (buffered) {
                    _localFramesPushed.incrementAndGet()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception pushing to local jitter buffer seq=$seq: ${e.message}")
            }
        }

        publishStats()
    }

    private fun feedLocalSilence(seq: Long, frame: CapturedAudioFrame) {
        val nowUs = clock.nowMicros()
        val targetPresentationTimeUs = nowUs + config.lanJitterBufferMicros
        try {
            val opusBytes = encoder.encode(frame.pcmData, frame.frameSizePerChannel)
            if (opusBytes != null && opusBytes.isNotEmpty()) {
                localBuffer?.pushPacket(
                    sequenceNumber = seq,
                    presentationTimeUs = targetPresentationTimeUs,
                    payload = opusBytes
                )
                _localFramesPushed.incrementAndGet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding local silence frame seq=$seq: ${e.message}")
        }
    }

    private fun updateState(newState: StreamingBridgeState) {
        _state.value = newState
        try {
            onStateChanged?.invoke(newState)
        } catch (e: Exception) {
            Log.w(TAG, "Error in onStateChanged callback: ${e.message}")
        }
    }

    private fun publishStats() {
        _statsFlow.value = stats
    }

    /**
     * Resets all telemetry statistics counters and sequence numbers.
     */
    fun resetStats(newInitialSeq: Long = config.initialSeq) {
        nextSequenceNumber.set(newInitialSeq)
        _totalFramesProcessed.set(0L)
        _packetsSent.set(0L)
        _bytesSent.set(0L)
        _silenceFramesSuppressed.set(0L)
        _activeFramesSent.set(0L)
        _localFramesPushed.set(0L)
        _encodingErrors.set(0L)
        _broadcastErrors.set(0L)
        _lastSequenceNumber.set(-1L)
        _lastPresentationTimeUs.set(0L)
        totalEncodeDurationUs.set(0L)
        totalEncodedFramesCount.set(0L)
        publishStats()
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            stop()
            try {
                encoder.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing encoder: ${e.message}")
            }
            try {
                broadcaster?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing broadcaster: ${e.message}")
            }
        }
    }
}
