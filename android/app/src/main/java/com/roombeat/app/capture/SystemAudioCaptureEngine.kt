package com.roombeat.app.capture

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import com.roombeat.app.audio.codec.OpusConstants
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration descriptor for system audio capture via AudioPlaybackCaptureConfiguration.
 *
 * @param sampleRate Target audio sample rate in Hz (default: 48,000 Hz).
 * @param channelMask Audio channel mask (default: [AudioFormat.CHANNEL_IN_STEREO]).
 * @param audioEncoding Audio encoding format (default: [AudioFormat.ENCODING_PCM_16BIT]).
 * @param frameSizePerChannel Samples per channel per 20ms frame (default: 960).
 * @param channels Number of audio channels (default: 2 for stereo).
 * @param matchingUsages Audio usages to capture from third-party media players.
 * @param minBufferSizeMultiplier Multiplier over minimum hardware buffer size.
 * @param readMode Read mode for AudioRecord ([AudioRecordFacade.READ_BLOCKING]).
 */
data class CaptureConfig(
    val sampleRate: Int = OpusConstants.SAMPLE_RATE,
    val channelMask: Int = AudioFormat.CHANNEL_IN_STEREO,
    val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val frameSizePerChannel: Int = OpusConstants.FRAME_SIZE_SAMPLES,
    val channels: Int = OpusConstants.CHANNELS,
    val matchingUsages: List<Int> = listOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN
    ),
    val minBufferSizeMultiplier: Int = 2,
    val readMode: Int = AudioRecordFacade.READ_BLOCKING
) {
    /** Total interleaved samples per 20ms frame (e.g. 960 * 2 = 1920 shorts). */
    val frameSamples: Int get() = frameSizePerChannel * channels

    /** Total bytes per 20ms frame (e.g. 1920 shorts * 2 bytes = 3840 bytes). */
    val frameBytes: Int get() = frameSamples * 2

    /** Frame duration in milliseconds (e.g. 20ms). */
    val frameDurationMs: Long get() = (frameSizePerChannel * 1000L) / sampleRate
}

/**
 * Representation of a 20ms captured PCM audio frame.
 *
 * @param pcmData Interleaved 16-bit PCM audio samples (1920 samples for 20ms @ 48kHz stereo).
 * @param sampleRate Audio sample rate in Hz (48,000 Hz).
 * @param channels Channel count (2 for stereo).
 * @param timestampNs Monotonic capture timestamp in nanoseconds.
 * @param isSilent True if the silence detector classified this frame as silence.
 * @param rmsDbfs Root-mean-square energy level in dBFS.
 */
data class CapturedAudioFrame(
    val pcmData: ShortArray,
    val sampleRate: Int = OpusConstants.SAMPLE_RATE,
    val channels: Int = OpusConstants.CHANNELS,
    val timestampNs: Long = System.nanoTime(),
    val isSilent: Boolean = false,
    val rmsDbfs: Double = AudioSilenceDetector.SILENCE_DBFS_FLOOR
) {
    val frameSizePerChannel: Int get() = if (channels > 0) pcmData.size / channels else 0
    val durationMs: Double get() = (frameSizePerChannel * 1000.0) / sampleRate

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedAudioFrame) return false
        return pcmData.contentEquals(other.pcmData) &&
                sampleRate == other.sampleRate &&
                channels == other.channels &&
                timestampNs == other.timestampNs &&
                isSilent == other.isSilent &&
                rmsDbfs == other.rmsDbfs
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + isSilent.hashCode()
        result = 31 * result + rmsDbfs.hashCode()
        return result
    }
}

/**
 * Lifecycle states of the [SystemAudioCaptureEngine].
 */
sealed interface CaptureEngineState {
    object Idle : CaptureEngineState
    object Initializing : CaptureEngineState
    object Capturing : CaptureEngineState
    object Stopped : CaptureEngineState
    data class Error(val message: String, val cause: Throwable? = null) : CaptureEngineState
}

/**
 * Decoupled facade for [AudioRecord] enabling deterministic headless JVM unit testing.
 */
interface AudioRecordFacade : AutoCloseable {
    val state: Int
    val recordingState: Int
    val sampleRate: Int
    val channelCount: Int
    val audioFormat: Int
    val bufferSizeInFrames: Int

    fun startRecording()
    fun stop()
    fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int
    fun release()

    override fun close() {
        release()
    }

    companion object {
        const val STATE_UNINITIALIZED = 0
        const val STATE_INITIALIZED = 1

        const val RECORDSTATE_STOPPED = 1
        const val RECORDSTATE_RECORDING = 3

        const val READ_BLOCKING = 0
        const val READ_NON_BLOCKING = 1

        const val SUCCESS = 0
        const val ERROR = -1
        const val ERROR_BAD_VALUE = -2
        const val ERROR_INVALID_OPERATION = -3
        const val ERROR_DEAD_OBJECT = -6
    }
}

/**
 * Default production implementation of [AudioRecordFacade] delegating directly to Android's [AudioRecord].
 */
class AndroidAudioRecordFacade(
    private val audioRecord: AudioRecord
) : AudioRecordFacade {
    override val state: Int get() = audioRecord.state
    override val recordingState: Int get() = audioRecord.recordingState
    override val sampleRate: Int get() = audioRecord.sampleRate
    override val channelCount: Int get() = audioRecord.channelCount
    override val audioFormat: Int get() = audioRecord.audioFormat
    override val bufferSizeInFrames: Int get() = audioRecord.bufferSizeInFrames

    override fun startRecording() {
        audioRecord.startRecording()
    }

    override fun stop() {
        try {
            audioRecord.stop()
        } catch (e: Exception) {
            Log.w("AndroidAudioRecordFacade", "Error stopping AudioRecord: ${e.message}")
        }
    }

    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int {
        return audioRecord.read(audioData, offsetInShorts, sizeInShorts, readMode)
    }

    override fun release() {
        try {
            audioRecord.release()
        } catch (e: Exception) {
            Log.w("AndroidAudioRecordFacade", "Error releasing AudioRecord: ${e.message}")
        }
    }
}

/**
 * Factory abstraction for creating [AudioRecordFacade] instances with [AudioPlaybackCaptureConfiguration].
 */
interface AudioRecordFactory {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun createAudioRecord(
        mediaProjection: MediaProjection?,
        config: CaptureConfig = CaptureConfig()
    ): AudioRecordFacade
}

/**
 * Production implementation of [AudioRecordFactory] constructing [AudioPlaybackCaptureConfiguration]
 * and building [AudioRecord] with media audio stream matching.
 */
class DefaultAudioRecordFactory : AudioRecordFactory {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun createAudioRecord(
        mediaProjection: MediaProjection?,
        config: CaptureConfig
    ): AudioRecordFacade {
        requireNotNull(mediaProjection) { "MediaProjection must not be null" }
        val playbackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection).apply {
            config.matchingUsages.forEach { usage ->
                addMatchingUsage(usage)
            }
        }.build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(config.audioEncoding)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelMask)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelMask,
            config.audioEncoding
        )

        val bufferSize = if (minBufferSize > 0) {
            maxOf(minBufferSize * config.minBufferSizeMultiplier, config.frameBytes * 2)
        } else {
            config.frameBytes * 4
        }

        val audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackCaptureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        return AndroidAudioRecordFacade(audioRecord)
    }
}

/**
 * High-performance System Audio Capture Engine.
 *
 * Implements:
 * 1. Building [AudioPlaybackCaptureConfiguration] adding [AudioAttributes.USAGE_MEDIA],
 *    [AudioAttributes.USAGE_GAME], and [AudioAttributes.USAGE_UNKNOWN].
 * 2. Configuring [AudioRecord] for 48,000 Hz, stereo (2 channels), 16-bit PCM.
 * 3. High-priority audio recording thread reading exact 20ms chunks (1920 16-bit samples) without blocking.
 * 4. Gating silence using [AudioSilenceDetector] with fast attack and hangover duration.
 * 5. Lifecycle management ([startCapture], [stopCapture], [isCapturing]), exposing frames via callback and [SharedFlow].
 * 6. Decoupled facades ([AudioRecordFacade], [AudioRecordFactory]) enabling 100% headless JVM testing.
 */
class SystemAudioCaptureEngine(
    val config: CaptureConfig = CaptureConfig(),
    var audioRecordFactory: AudioRecordFactory = DefaultAudioRecordFactory(),
    val silenceDetector: AudioSilenceDetector = AudioSilenceDetector()
) {
    companion object {
        private const val TAG = "SystemAudioCaptureEngine"
        private const val MAX_CONSECUTIVE_READ_ERRORS = 5
        private const val THREAD_NAME = "RoomBeat-AudioCaptureThread"
    }

    private val captureLock = Any()

    @Volatile
    var isCapturing: Boolean = false
        private set

    private var activeRecord: AudioRecordFacade? = null
    private var captureThread: Thread? = null

    private val _captureState = MutableStateFlow<CaptureEngineState>(CaptureEngineState.Idle)
    val captureState: StateFlow<CaptureEngineState> = _captureState.asStateFlow()

    private val _audioFrames = MutableSharedFlow<CapturedAudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFrames: SharedFlow<CapturedAudioFrame> = _audioFrames.asSharedFlow()

    /**
     * Optional direct callback invoked immediately upon capturing each 20ms PCM frame.
     */
    var onAudioFrameCaptured: ((CapturedAudioFrame) -> Unit)? = null

    /**
     * Optional error callback invoked when capture initialization or loop fails.
     */
    var onError: ((message: String, cause: Throwable?) -> Unit)? = null

    // Telemetry counters
    private val _totalFramesCaptured = AtomicLong(0L)
    val totalFramesCaptured: Long get() = _totalFramesCaptured.get()

    private val _totalSilentFrames = AtomicLong(0L)
    val totalSilentFrames: Long get() = _totalSilentFrames.get()

    private val _totalActiveFrames = AtomicLong(0L)
    val totalActiveFrames: Long get() = _totalActiveFrames.get()

    private val _totalReadErrors = AtomicLong(0L)
    val totalReadErrors: Long get() = _totalReadErrors.get()

    /**
     * Starts system audio capture using the provided [MediaProjectionHandle].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(handle: MediaProjectionHandle): Boolean {
        return startCapture(handle.rawProjection)
    }

    /**
     * Starts system audio capture using the provided [MediaProjection] token.
     *
     * Initializes [AudioRecord] via [audioRecordFactory], transitions to [CaptureEngineState.Capturing],
     * and launches the high-priority background capture thread.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(mediaProjection: MediaProjection? = null): Boolean {
        synchronized(captureLock) {
            if (isCapturing && activeRecord != null) {
                Log.d(TAG, "Capture already active")
                return true
            }

            _captureState.value = CaptureEngineState.Initializing
            Log.i(TAG, "Initializing system audio capture at ${config.sampleRate}Hz stereo 20ms chunks")

            val audioRecord: AudioRecordFacade
            try {
                audioRecord = audioRecordFactory.createAudioRecord(mediaProjection, config)
            } catch (e: Exception) {
                val errorMsg = "Failed to create AudioRecord: ${e.message}"
                Log.e(TAG, errorMsg, e)
                _captureState.value = CaptureEngineState.Error(errorMsg, e)
                onError?.invoke(errorMsg, e)
                return false
            }

            if (audioRecord.state != AudioRecordFacade.STATE_INITIALIZED) {
                val errorMsg = "AudioRecord failed to initialize (state=${audioRecord.state})"
                Log.e(TAG, errorMsg)
                audioRecord.release()
                _captureState.value = CaptureEngineState.Error(errorMsg)
                onError?.invoke(errorMsg, null)
                return false
            }

            try {
                audioRecord.startRecording()
            } catch (e: Exception) {
                val errorMsg = "Exception starting AudioRecord recording: ${e.message}"
                Log.e(TAG, errorMsg, e)
                audioRecord.release()
                _captureState.value = CaptureEngineState.Error(errorMsg, e)
                onError?.invoke(errorMsg, e)
                return false
            }

            if (audioRecord.recordingState != AudioRecordFacade.RECORDSTATE_RECORDING) {
                val errorMsg = "AudioRecord recordingState is not RECORDSTATE_RECORDING (state=${audioRecord.recordingState})"
                Log.e(TAG, errorMsg)
                audioRecord.stop()
                audioRecord.release()
                _captureState.value = CaptureEngineState.Error(errorMsg)
                onError?.invoke(errorMsg, null)
                return false
            }

            activeRecord = audioRecord
            isCapturing = true
            silenceDetector.reset(startSilent = true)

            val thread = Thread({
                runCaptureLoop(audioRecord)
            }, THREAD_NAME).apply {
                isDaemon = true
                try {
                    priority = Thread.MAX_PRIORITY
                } catch (t: Throwable) {
                    // Ignored on headless test environments
                }
                start()
            }

            captureThread = thread
            _captureState.value = CaptureEngineState.Capturing
            Log.i(TAG, "System audio capture started successfully")
            return true
        }
    }

    /**
     * High-priority capture loop reading PCM frames in exact 20ms chunks.
     */
    private fun runCaptureLoop(audioRecord: AudioRecordFacade) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            // Ignored on headless test environments
        }

        val frameSamples = config.frameSamples // 1920 shorts
        val pcmBuffer = ShortArray(frameSamples)
        var consecutiveErrors = 0

        while (isCapturing) {
            var samplesRead = 0
            var fatalError = false

            while (samplesRead < frameSamples && isCapturing) {
                val needed = frameSamples - samplesRead
                val result = audioRecord.read(
                    pcmBuffer,
                    samplesRead,
                    needed,
                    config.readMode
                )

                if (result > 0) {
                    samplesRead += result
                    consecutiveErrors = 0
                } else if (result == 0) {
                    Thread.yield()
                } else {
                    _totalReadErrors.incrementAndGet()
                    consecutiveErrors++
                    Log.w(TAG, "AudioRecord.read returned error code $result (error count: $consecutiveErrors)")

                    if (result == AudioRecordFacade.ERROR_DEAD_OBJECT || consecutiveErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        val errorMsg = "AudioRecord read failed with fatal error code $result"
                        Log.e(TAG, errorMsg)
                        fatalError = true
                        isCapturing = false
                        _captureState.value = CaptureEngineState.Error(errorMsg)
                        onError?.invoke(errorMsg, null)
                    }
                    break
                }
            }

            if (fatalError || !isCapturing) {
                break
            }

            if (samplesRead == frameSamples) {
                _totalFramesCaptured.incrementAndGet()
                val timestampNs = System.nanoTime()
                val silenceResult = silenceDetector.processFrame(pcmBuffer)

                if (silenceResult.isSilent) {
                    _totalSilentFrames.incrementAndGet()
                } else {
                    _totalActiveFrames.incrementAndGet()
                }

                val frame = CapturedAudioFrame(
                    pcmData = pcmBuffer.copyOf(),
                    sampleRate = config.sampleRate,
                    channels = config.channels,
                    timestampNs = timestampNs,
                    isSilent = silenceResult.isSilent,
                    rmsDbfs = silenceResult.rmsDbfs
                )

                // Non-blocking frame delivery
                try {
                    onAudioFrameCaptured?.invoke(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in onAudioFrameCaptured callback: ${e.message}")
                }
                _audioFrames.tryEmit(frame)
            }
        }

        Log.d(TAG, "Exiting capture loop")
    }

    /**
     * Cleanly stops audio capture, releases [AudioRecord], and joins the capture thread.
     */
    fun stopCapture() {
        synchronized(captureLock) {
            if (!isCapturing && activeRecord == null) {
                if (_captureState.value !is CaptureEngineState.Error && _captureState.value != CaptureEngineState.Stopped) {
                    _captureState.value = CaptureEngineState.Stopped
                }
                return
            }

            Log.i(TAG, "Stopping system audio capture")
            isCapturing = false

            // Interrupt and join capture thread
            captureThread?.let { thread ->
                try {
                    thread.interrupt()
                    thread.join(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            captureThread = null

            // Stop and release AudioRecord
            activeRecord?.let { record ->
                try {
                    record.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                }
                try {
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
                }
            }
            activeRecord = null

            if (_captureState.value !is CaptureEngineState.Error) {
                _captureState.value = CaptureEngineState.Stopped
            }
        }
    }

    /**
     * Convenience method to restart capture with a fresh or updated [MediaProjection] token.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restartCapture(mediaProjection: MediaProjection? = null): Boolean {
        stopCapture()
        return startCapture(mediaProjection)
    }

    /**
     * Resets the telemetry counters.
     */
    fun resetMetrics() {
        _totalFramesCaptured.set(0L)
        _totalSilentFrames.set(0L)
        _totalActiveFrames.set(0L)
        _totalReadErrors.set(0L)
    }
}
