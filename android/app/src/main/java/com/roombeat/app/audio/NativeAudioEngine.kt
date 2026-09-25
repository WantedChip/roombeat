package com.roombeat.app.audio

import android.util.Log

/**
 * Lifecycle states of the RoomBeat audio engine.
 */
enum class AudioEngineState {
    UNINITIALIZED,
    INITIALIZED,
    STREAMING,
    STOPPED,
    ERROR
}

/**
 * Result representation for audio engine operations.
 */
sealed class AudioEngineResult {
    data object Success : AudioEngineResult()
    data class Error(val code: Int, val message: String) : AudioEngineResult()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
}

/**
 * Native bridge interface defining low-level audio lifecycle operations.
 * Allows decoupling JVM testing from NDK shared library loading.
 */
interface AudioEngineBridge {
    fun initEngine(): Int
    fun startStream(): Int
    fun stopStream(): Int
    fun getAudioLatencyMillis(): Int
    fun teardownEngine(): Int

    fun writeAudioFrames(audioData: FloatArray, numFrames: Int): Int = 0
    fun writePcm16Frames(audioData: ShortArray, numFrames: Int): Int = 0
    fun getAvailableFrames(): Int = 0
    fun clearBuffer() {}
    fun getUnderrunCount(): Long = 0L
    fun attachJitterBuffer(handle: Long): Boolean = false
    fun pushAudioChunk(
        seq: Long,
        presentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size
    ): Boolean = false
    fun setChannelVolume(volumeDb: Float) {}
    fun setMasterVolume(volumeDb: Float) {}
    fun setMuted(isMuted: Boolean) {}
    fun setTargetStartTimeUs(targetTimeUs: Long) {}
    fun flushAndSeek(newInitialSeq: Long, newTargetStartTimeUs: Long) {}
    fun encodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int = 0
}

/**
 * Kotlin JNI wrapper and state manager for the RoomBeat native audio engine.
 *
 * Backed by Google Oboe on Android (48kHz, stereo, low latency).
 * Handles native library loading safely to support host-side JVM unit tests
 * without UnsatisfiedLinkError crashes.
 */
open class NativeAudioEngine(
    private val customBridge: AudioEngineBridge? = null
) {
    companion object {
        private const val TAG = "NativeAudioEngine"
        private const val LIBRARY_NAME = "roombeat_audio"

        const val SUCCESS = 0
        const val ERROR_INVALID_STATE = -1
        const val ERROR_STREAM_OPEN_FAILED = -2
        const val ERROR_STREAM_START_FAILED = -3
        const val ERROR_LIBRARY_NOT_LOADED = -10
        const val ERROR_UNKNOWN = -100

        @Volatile
        private var isLibraryLoaded = false

        init {
            loadNativeLibrary()
        }

        /**
         * Safely attempts to load the native shared library.
         * Returns true if loaded successfully, false otherwise.
         */
        @Synchronized
        fun loadNativeLibrary(): Boolean {
            if (isLibraryLoaded) return true
            return try {
                System.loadLibrary(LIBRARY_NAME)
                isLibraryLoaded = true
                Log.i(TAG, "Successfully loaded native library: $LIBRARY_NAME")
                true
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                Log.w(TAG, "Native library '$LIBRARY_NAME' not available in this environment: ${e.message}")
                false
            } catch (e: SecurityException) {
                isLibraryLoaded = false
                Log.e(TAG, "SecurityException loading native library '$LIBRARY_NAME': ${e.message}")
                false
            }
        }

        /**
         * Whether the native shared library is currently loaded.
         */
        val isNativeLoaded: Boolean
            get() = isLibraryLoaded

        // Default singleton instance for convenience and global access
        private val defaultInstance: NativeAudioEngine by lazy { NativeAudioEngine() }

        // Forwarding methods for companion object access
        val state: AudioEngineState get() = defaultInstance.state
        val lastError: String? get() = defaultInstance.lastError

        fun initEngine(): Boolean = defaultInstance.initEngine()
        fun startStream(): Boolean = defaultInstance.startStream()
        fun stopStream(): Boolean = defaultInstance.stopStream()
        fun getAudioLatencyMillis(): Int = defaultInstance.getAudioLatencyMillis()
        fun teardownEngine(): Boolean = defaultInstance.teardownEngine()

        fun initEngineWithResult(): AudioEngineResult = defaultInstance.initEngineWithResult()
        fun startStreamWithResult(): AudioEngineResult = defaultInstance.startStreamWithResult()
        fun stopStreamWithResult(): AudioEngineResult = defaultInstance.stopStreamWithResult()
        fun teardownEngineWithResult(): AudioEngineResult = defaultInstance.teardownEngineWithResult()

        fun writeAudioFrames(audioData: FloatArray, numFrames: Int): Int =
            defaultInstance.writeAudioFrames(audioData, numFrames)

        fun writePcm16Frames(audioData: ShortArray, numFrames: Int): Int =
            defaultInstance.writePcm16Frames(audioData, numFrames)

        fun getAvailableFrames(): Int = defaultInstance.getAvailableFrames()
        fun clearBuffer() = defaultInstance.clearBuffer()
        fun getUnderrunCount(): Long = defaultInstance.getUnderrunCount()
        fun attachJitterBuffer(jitterBuffer: com.roombeat.app.audio.buffer.AudioJitterBuffer?): Boolean =
            defaultInstance.attachJitterBuffer(jitterBuffer)

        fun pushAudioChunk(
            seq: Long,
            presentationTimeUs: Long,
            opusData: ByteArray,
            offset: Int = 0,
            length: Int = opusData.size
        ): Boolean = defaultInstance.pushAudioChunk(seq, presentationTimeUs, opusData, offset, length)

        val channelVolumeDb: Float get() = defaultInstance.channelVolumeDb
        val masterVolumeDb: Float get() = defaultInstance.masterVolumeDb
        val isMuted: Boolean get() = defaultInstance.isMuted

        fun setChannelVolume(volumeDb: Float) = defaultInstance.setChannelVolume(volumeDb)
        fun setMasterVolume(volumeDb: Float) = defaultInstance.setMasterVolume(volumeDb)
        fun setMuted(isMuted: Boolean) = defaultInstance.setMuted(isMuted)
        fun setTargetStartTimeUs(targetTimeUs: Long) = defaultInstance.setTargetStartTimeUs(targetTimeUs)
        fun flushAndSeek(newInitialSeq: Long, newTargetStartTimeUs: Long) = defaultInstance.flushAndSeek(newInitialSeq, newTargetStartTimeUs)
        fun encodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int = defaultInstance.encodeFrame(pcmData, outputBuffer)
        fun encodeFrame(pcmData: ShortArray): ByteArray? = defaultInstance.encodeFrame(pcmData)
    }

    private val lock = Any()

    @Volatile
    private var _state: AudioEngineState = AudioEngineState.UNINITIALIZED

    @Volatile
    private var _lastError: String? = null

    @Volatile
    private var _channelVolumeDb: Float = 0.0f

    @Volatile
    private var _masterVolumeDb: Float = 0.0f

    @Volatile
    private var _isMuted: Boolean = false

    val state: AudioEngineState
        get() = _state

    val lastError: String?
        get() = _lastError

    val channelVolumeDb: Float
        get() = _channelVolumeDb

    val masterVolumeDb: Float
        get() = _masterVolumeDb

    val isMuted: Boolean
        get() = _isMuted

    fun setChannelVolume(volumeDb: Float) {
        _channelVolumeDb = volumeDb
        val bridge = resolveBridge() ?: return
        try {
            bridge.setChannelVolume(volumeDb)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting channel volume: ${e.message}")
        }
    }

    fun setMasterVolume(volumeDb: Float) {
        _masterVolumeDb = volumeDb
        val bridge = resolveBridge() ?: return
        try {
            bridge.setMasterVolume(volumeDb)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting master volume: ${e.message}")
        }
    }

    fun setMuted(isMuted: Boolean) {
        _isMuted = isMuted
        val bridge = resolveBridge() ?: return
        try {
            bridge.setMuted(isMuted)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting muted state: ${e.message}")
        }
    }

    val isLibraryAvailable: Boolean
        get() = customBridge != null || isLibraryLoaded

    /**
     * Initializes the native audio engine.
     * Transitions state to [AudioEngineState.INITIALIZED] on success.
     */
    fun initEngine(): Boolean = initEngineWithResult().isSuccess

    fun initEngineWithResult(): AudioEngineResult = synchronized(lock) {
        if (_state == AudioEngineState.INITIALIZED) {
            return AudioEngineResult.Success
        }
        if (_state == AudioEngineState.STREAMING) {
            return AudioEngineResult.Success
        }

        val bridge = resolveBridge()
        if (bridge == null) {
            val message = "Native library '$LIBRARY_NAME' is not loaded and no mock bridge provided."
            _lastError = message
            _state = AudioEngineState.ERROR
            return AudioEngineResult.Error(ERROR_LIBRARY_NOT_LOADED, message)
        }

        val result = try {
            bridge.initEngine()
        } catch (e: Exception) {
            val message = "Exception during native initEngine: ${e.message}"
            _lastError = message
            _state = AudioEngineState.ERROR
            return AudioEngineResult.Error(ERROR_UNKNOWN, message)
        }

        if (result == SUCCESS) {
            _state = AudioEngineState.INITIALIZED
            _lastError = null
            AudioEngineResult.Success
        } else {
            val message = "Failed to initialize native audio engine (code: $result)"
            _lastError = message
            _state = AudioEngineState.ERROR
            AudioEngineResult.Error(result, message)
        }
    }

    /**
     * Opens and starts the Oboe low-latency audio stream.
     * Transitions state to [AudioEngineState.STREAMING] on success.
     */
    fun startStream(): Boolean = startStreamWithResult().isSuccess

    fun startStreamWithResult(): AudioEngineResult = synchronized(lock) {
        if (_state == AudioEngineState.STREAMING) {
            return AudioEngineResult.Success
        }

        if (_state == AudioEngineState.UNINITIALIZED || _state == AudioEngineState.ERROR) {
            val initRes = initEngineWithResult()
            if (!initRes.isSuccess) {
                return initRes
            }
        }

        val bridge = resolveBridge()
        if (bridge == null) {
            val message = "Native library '$LIBRARY_NAME' is not loaded."
            _lastError = message
            _state = AudioEngineState.ERROR
            return AudioEngineResult.Error(ERROR_LIBRARY_NOT_LOADED, message)
        }

        val result = try {
            bridge.startStream()
        } catch (e: Exception) {
            val message = "Exception during native startStream: ${e.message}"
            _lastError = message
            _state = AudioEngineState.ERROR
            return AudioEngineResult.Error(ERROR_UNKNOWN, message)
        }

        if (result == SUCCESS) {
            _state = AudioEngineState.STREAMING
            _lastError = null
            AudioEngineResult.Success
        } else {
            val message = "Failed to start audio stream (code: $result)"
            _lastError = message
            _state = AudioEngineState.ERROR
            AudioEngineResult.Error(result, message)
        }
    }

    /**
     * Requests the audio stream to stop and closes the stream.
     * Transitions state to [AudioEngineState.STOPPED] on success.
     */
    fun stopStream(): Boolean = stopStreamWithResult().isSuccess

    fun stopStreamWithResult(): AudioEngineResult = synchronized(lock) {
        if (_state != AudioEngineState.STREAMING) {
            // Already stopped or not yet streaming
            if (_state == AudioEngineState.INITIALIZED) {
                _state = AudioEngineState.STOPPED
            }
            return AudioEngineResult.Success
        }

        val bridge = resolveBridge()
        if (bridge == null) {
            _state = AudioEngineState.STOPPED
            return AudioEngineResult.Success
        }

        val result = try {
            bridge.stopStream()
        } catch (e: Exception) {
            val message = "Exception during native stopStream: ${e.message}"
            _lastError = message
            _state = AudioEngineState.ERROR
            return AudioEngineResult.Error(ERROR_UNKNOWN, message)
        }

        if (result == SUCCESS) {
            _state = AudioEngineState.STOPPED
            _lastError = null
            AudioEngineResult.Success
        } else {
            val message = "Failed to stop audio stream (code: $result)"
            _lastError = message
            _state = AudioEngineState.ERROR
            AudioEngineResult.Error(result, message)
        }
    }

    /**
     * Queries the estimated or calculated audio output latency in milliseconds.
     * Returns a non-negative integer millisecond latency, or 0 if inactive/unavailable.
     */
    fun getAudioLatencyMillis(): Int = synchronized(lock) {
        val bridge = resolveBridge() ?: return 0
        return try {
            val latency = bridge.getAudioLatencyMillis()
            if (latency < 0) 0 else latency
        } catch (e: Exception) {
            Log.w(TAG, "Error querying audio latency: ${e.message}")
            0
        }
    }

    /**
     * Tears down the audio engine, stopping any active stream and releasing all native resources.
     * Transitions state to [AudioEngineState.UNINITIALIZED].
     */
    fun teardownEngine(): Boolean = teardownEngineWithResult().isSuccess

    fun teardownEngineWithResult(): AudioEngineResult = synchronized(lock) {
        val bridge = resolveBridge()
        val result = if (bridge != null) {
            try {
                bridge.teardownEngine()
            } catch (e: Exception) {
                Log.w(TAG, "Exception during native teardownEngine: ${e.message}")
                ERROR_UNKNOWN
            }
        } else {
            SUCCESS
        }

        _state = AudioEngineState.UNINITIALIZED
        _lastError = null
        attachedJitterBuffer = null

        if (result == SUCCESS) {
            AudioEngineResult.Success
        } else {
            AudioEngineResult.Error(result, "Native teardown returned code $result")
        }
    }

    /**
     * Writes interleaved stereo Float32 audio frames into the native playback ring buffer.
     * Returns the number of frames actually written.
     */
    fun writeAudioFrames(audioData: FloatArray, numFrames: Int): Int {
        val bridge = resolveBridge() ?: return 0
        return try {
            bridge.writeAudioFrames(audioData, numFrames)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing float audio frames: ${e.message}")
            0
        }
    }

    /**
     * Writes interleaved stereo PCM16 audio frames into the native playback ring buffer,
     * converting them to Float32 on the native side.
     * Returns the number of frames actually written.
     */
    fun writePcm16Frames(audioData: ShortArray, numFrames: Int): Int {
        val bridge = resolveBridge() ?: return 0
        return try {
            bridge.writePcm16Frames(audioData, numFrames)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing PCM16 audio frames: ${e.message}")
            0
        }
    }

    /**
     * Returns the number of audio frames currently queued in the native ring buffer.
     */
    fun getAvailableFrames(): Int {
        val bridge = resolveBridge() ?: return 0
        return try {
            bridge.getAvailableFrames()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying available frames: ${e.message}")
            0
        }
    }

    /**
     * Discards all queued frames in the native ring buffer.
     */
    fun clearBuffer() {
        val bridge = resolveBridge() ?: return
        try {
            bridge.clearBuffer()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing buffer: ${e.message}")
        }
    }

    /**
     * Returns the total number of underrun frames (silence rendered due to buffer starvation).
     */
    fun getUnderrunCount(): Long {
        val bridge = resolveBridge() ?: return 0L
        return try {
            bridge.getUnderrunCount()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying underrun count: ${e.message}")
            0L
        }
    }

    @Volatile
    private var attachedJitterBuffer: com.roombeat.app.audio.buffer.AudioJitterBuffer? = null

    /**
     * Attaches an AudioJitterBuffer directly to the Oboe audio stream as its AudioSource provider.
     */
    fun attachJitterBuffer(jitterBuffer: com.roombeat.app.audio.buffer.AudioJitterBuffer?): Boolean {
        this.attachedJitterBuffer = jitterBuffer
        val bridge = resolveBridge() ?: return false
        return try {
            bridge.attachJitterBuffer(jitterBuffer?.handle ?: 0L)
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching jitter buffer: ${e.message}")
            false
        }
    }

    /**
     * Directly pushes an incoming audio chunk into the attached jitter buffer via fast JNI bridge.
     */
    fun pushAudioChunk(
        seq: Long,
        presentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size
    ): Boolean {
        val currentBuffer = attachedJitterBuffer
        if (!isLibraryLoaded && customBridge == null && currentBuffer != null) {
            return currentBuffer.pushPacket(seq, presentationTimeUs, opusData, offset, length)
        }
        val bridge = resolveBridge() ?: return false
        return try {
            bridge.pushAudioChunk(seq, presentationTimeUs, opusData, offset, length)
        } catch (e: Exception) {
            Log.w(TAG, "Error pushing audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Arms the attached jitter buffer and native engine with a target presentation start timestamp.
     */
    fun setTargetStartTimeUs(targetTimeUs: Long) {
        val currentBuffer = attachedJitterBuffer
        if (currentBuffer != null) {
            currentBuffer.targetStartTimeUs = targetTimeUs
        }
        val bridge = resolveBridge() ?: return
        try {
            bridge.setTargetStartTimeUs(targetTimeUs)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting target start time: ${e.message}")
        }
    }

    /**
     * Flushes the attached jitter buffer and realigns presentation timing.
     */
    fun flushAndSeek(newInitialSeq: Long, newTargetStartTimeUs: Long) {
        val currentBuffer = attachedJitterBuffer
        if (currentBuffer != null) {
            currentBuffer.flushAndSeek(newInitialSeq, newTargetStartTimeUs)
        }
        val bridge = resolveBridge() ?: return
        try {
            bridge.flushAndSeek(newInitialSeq, newTargetStartTimeUs)
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing and seeking audio engine: ${e.message}")
        }
    }

    private val fallbackEncoderHandle: Long by lazy {
        com.roombeat.app.audio.codec.DefaultOpusCodecBridge.INSTANCE.encoderCreate(
            com.roombeat.app.audio.codec.OpusConstants.SAMPLE_RATE,
            com.roombeat.app.audio.codec.OpusConstants.CHANNELS,
            com.roombeat.app.audio.codec.OpusConstants.DEFAULT_BITRATE,
            com.roombeat.app.audio.codec.OpusConstants.DEFAULT_COMPLEXITY
        )
    }

    /**
     * Encodes a 20ms PCM16 buffer (1920 short samples) into the provided output byte buffer.
     * Returns the number of compressed bytes written, or a negative error code.
     */
    fun encodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int {
        val bridge = resolveBridge()
        if (bridge != null) {
            return try {
                bridge.encodeFrame(pcmData, outputBuffer)
            } catch (e: Exception) {
                Log.w(TAG, "Error encoding frame: ${e.message}")
                -1
            }
        }
        return com.roombeat.app.audio.codec.DefaultOpusCodecBridge.INSTANCE.encoderEncodeShort(
            fallbackEncoderHandle,
            pcmData,
            com.roombeat.app.audio.codec.OpusConstants.FRAME_SIZE_SAMPLES,
            outputBuffer,
            outputBuffer.size
        )
    }

    /**
     * Convenience method encoding a 20ms PCM16 buffer to a freshly allocated ByteArray.
     */
    fun encodeFrame(pcmData: ShortArray): ByteArray? {
        val outBuffer = ByteArray(com.roombeat.app.audio.codec.OpusConstants.MAX_PACKET_BYTES)
        val bytes = encodeFrame(pcmData, outBuffer)
        return if (bytes > 0) outBuffer.copyOf(bytes) else null
    }

    private fun resolveBridge(): AudioEngineBridge? {
        if (customBridge != null) return customBridge
        if (isLibraryLoaded) return DefaultJniBridge
        return null
    }

    /**
     * Default JNI bridge delegating directly to external native functions in roombeat_audio.so.
     */
    private object DefaultJniBridge : AudioEngineBridge {
        override fun initEngine(): Int = nativeInitEngine()
        override fun startStream(): Int = nativeStartStream()
        override fun stopStream(): Int = nativeStopStream()
        override fun getAudioLatencyMillis(): Int = nativeGetAudioLatencyMillis()
        override fun teardownEngine(): Int = nativeTeardownEngine()

        override fun writeAudioFrames(audioData: FloatArray, numFrames: Int): Int =
            nativeWriteAudioFrames(audioData, numFrames)

        override fun writePcm16Frames(audioData: ShortArray, numFrames: Int): Int =
            nativeWritePcm16Frames(audioData, numFrames)

        override fun getAvailableFrames(): Int = nativeGetAvailableFrames()

        override fun clearBuffer() = nativeClearBuffer()

        override fun getUnderrunCount(): Long = nativeGetUnderrunCount()

        override fun attachJitterBuffer(handle: Long): Boolean = nativeAttachJitterBuffer(handle)

        override fun pushAudioChunk(
            seq: Long,
            presentationTimeUs: Long,
            opusData: ByteArray,
            offset: Int,
            length: Int
        ): Boolean = nativePushAudioChunk(seq, presentationTimeUs, opusData, offset, length)

        override fun setChannelVolume(volumeDb: Float) = nativeSetChannelVolume(volumeDb)
        override fun setMasterVolume(volumeDb: Float) = nativeSetMasterVolume(volumeDb)
        override fun setMuted(isMuted: Boolean) = nativeSetMuted(isMuted)
        override fun setTargetStartTimeUs(targetTimeUs: Long) = nativeSetTargetStartTimeUs(targetTimeUs)
        override fun flushAndSeek(newInitialSeq: Long, newTargetStartTimeUs: Long) = nativeFlushAndSeek(newInitialSeq, newTargetStartTimeUs)
        override fun encodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int = nativeEncodeFrame(pcmData, outputBuffer)

        @JvmStatic
        private external fun nativeInitEngine(): Int

        @JvmStatic
        private external fun nativeStartStream(): Int

        @JvmStatic
        private external fun nativeStopStream(): Int

        @JvmStatic
        private external fun nativeGetAudioLatencyMillis(): Int

        @JvmStatic
        private external fun nativeTeardownEngine(): Int

        @JvmStatic
        private external fun nativeWriteAudioFrames(audioData: FloatArray, numFrames: Int): Int

        @JvmStatic
        private external fun nativeWritePcm16Frames(audioData: ShortArray, numFrames: Int): Int

        @JvmStatic
        private external fun nativeGetAvailableFrames(): Int

        @JvmStatic
        private external fun nativeClearBuffer()

        @JvmStatic
        private external fun nativeGetUnderrunCount(): Long

        @JvmStatic
        private external fun nativeAttachJitterBuffer(handle: Long): Boolean

        @JvmStatic
        private external fun nativePushAudioChunk(
            seq: Long,
            presentationTimeUs: Long,
            opusData: ByteArray,
            offset: Int,
            length: Int
        ): Boolean

        @JvmStatic
        private external fun nativeSetChannelVolume(volumeDb: Float)

        @JvmStatic
        private external fun nativeSetMasterVolume(volumeDb: Float)

        @JvmStatic
        private external fun nativeSetMuted(isMuted: Boolean)

        @JvmStatic
        private external fun nativeSetTargetStartTimeUs(targetTimeUs: Long)

        @JvmStatic
        private external fun nativeFlushAndSeek(newInitialSeq: Long, newTargetStartTimeUs: Long)

        @JvmStatic
        private external fun nativeEncodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int
    }
}
