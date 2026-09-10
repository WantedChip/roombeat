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
    }

    private val lock = Any()

    @Volatile
    private var _state: AudioEngineState = AudioEngineState.UNINITIALIZED

    @Volatile
    private var _lastError: String? = null

    val state: AudioEngineState
        get() = _state

    val lastError: String?
        get() = _lastError

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

        if (result == SUCCESS) {
            AudioEngineResult.Success
        } else {
            AudioEngineResult.Error(result, "Native teardown returned code $result")
        }
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
    }
}
