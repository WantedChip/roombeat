#include "AudioEngineBridge.h"
#include "OboeAudioPlayer.h"
#include "buffer/AudioJitterBuffer.h"
#include "codec/OpusEncoderWrapper.h"

#include <android/log.h>
#include <atomic>
#include <cmath>
#include <exception>
#include <memory>
#include <mutex>

#define LOG_TAG "RoomBeatAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace roombeat {

// Result status codes returned to JNI layer
enum EngineResult : jint {
    SUCCESS = 0,
    ERROR_INVALID_STATE = -1,
    ERROR_STREAM_OPEN_FAILED = -2,
    ERROR_STREAM_START_FAILED = -3,
    ERROR_UNKNOWN = -100
};

class AudioEngine {
public:
    AudioEngine() = default;
    ~AudioEngine() {
        teardown();
    }

    // Disallow copy and assignment for thread safety and RAII guarantees
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    jint init() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (isInitialized_) {
            LOGI("AudioEngine already initialized.");
            return EngineResult::SUCCESS;
        }

        LOGI("Initializing RoomBeat AudioEngine via OboeAudioPlayer (48kHz, stereo, low latency)...");
        const oboe::Result result = player_.open();
        if (result != oboe::Result::OK) {
            LOGE("Failed to open audio player stream. Error: %s", oboe::convertToText(result));
            return EngineResult::ERROR_STREAM_OPEN_FAILED;
        }

        isInitialized_ = true;
        return EngineResult::SUCCESS;
    }

    jint startStream() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (!isInitialized_) {
            LOGE("Cannot start stream: AudioEngine is not initialized.");
            return EngineResult::ERROR_INVALID_STATE;
        }

        const oboe::Result result = player_.start();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe audio stream. Error: %s", oboe::convertToText(result));
            return EngineResult::ERROR_STREAM_START_FAILED;
        }

        LOGI("Oboe audio stream started successfully.");
        return EngineResult::SUCCESS;
    }

    jint stopStream() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        player_.stop();
        LOGI("Oboe audio stream stopped.");
        return EngineResult::SUCCESS;
    }

    jint getAudioLatencyMillis() {
        const double latencyMs = player_.getLatencyMillis();
        return static_cast<jint>(std::round(latencyMs));
    }

    jint teardown() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        player_.close();
        isInitialized_ = false;
        LOGI("AudioEngine teardown complete.");
        return EngineResult::SUCCESS;
    }

    void setChannelVolume(float volumeDb) {
        player_.setChannelVolume(volumeDb);
    }

    void setMasterVolume(float volumeDb) {
        player_.setMasterVolume(volumeDb);
    }

    void setMuted(bool isMuted) {
        player_.setMuted(isMuted);
    }

    void setSpeedPpm(int32_t ppm) {
        speedPpm_ = ppm;
        auto* jitter = getAttachedJitterBuffer();
        if (jitter) {
            jitter->setSpeedPpm(ppm);
        }
    }

    int32_t getSpeedPpm() const {
        auto* jitter = getAttachedJitterBuffer();
        if (jitter) {
            return jitter->getSpeedPpm();
        }
        return speedPpm_;
    }

    OboeAudioPlayer& getPlayer() {
        return player_;
    }

    bool getAudioLevels(float* outLevels, int32_t size) const {
        if (!outLevels || size < 4) return false;
        player_.getAudioLevels(outLevels);
        return true;
    }

private:
    std::mutex engineMutex_;
    OboeAudioPlayer player_;
    bool isInitialized_{false};
    int32_t speedPpm_{0};
};

// Global singleton instance managed safely with a mutex
static std::unique_ptr<AudioEngine> gAudioEngine = nullptr;
static std::mutex gEngineMutex;

static AudioEngine* getOrCreateEngine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gAudioEngine) {
        gAudioEngine = std::make_unique<AudioEngine>();
    }
    return gAudioEngine.get();
}

static std::atomic<roombeat::buffer::AudioJitterBuffer*> gAttachedJitterBuffer{nullptr};

void setAttachedJitterBuffer(roombeat::buffer::AudioJitterBuffer* jitterBuffer) {
    gAttachedJitterBuffer.store(jitterBuffer, std::memory_order_release);
}

roombeat::buffer::AudioJitterBuffer* getAttachedJitterBuffer() {
    return gAttachedJitterBuffer.load(std::memory_order_acquire);
}

static std::unique_ptr<roombeat::audio::OpusEncoderWrapper> gCaptureEncoder = nullptr;
static std::mutex gCaptureEncoderMutex;

static roombeat::audio::OpusEncoderWrapper* getOrCreateCaptureEncoder() {
    std::lock_guard<std::mutex> lock(gCaptureEncoderMutex);
    if (!gCaptureEncoder) {
        gCaptureEncoder = std::make_unique<roombeat::audio::OpusEncoderWrapper>(
            roombeat::audio::OpusEncoderWrapper::DEFAULT_SAMPLE_RATE,
            roombeat::audio::OpusEncoderWrapper::DEFAULT_CHANNELS,
            roombeat::audio::OpusEncoderWrapper::DEFAULT_BITRATE,
            roombeat::audio::OpusEncoderWrapper::DEFAULT_COMPLEXITY
        );
    }
    return gCaptureEncoder.get();
}

static jint destroyEngine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    setAttachedJitterBuffer(nullptr);
    {
        std::lock_guard<std::mutex> lockEnc(gCaptureEncoderMutex);
        if (gCaptureEncoder) {
            gCaptureEncoder->resetState();
        }
    }
    if (gAudioEngine) {
        const jint result = gAudioEngine->teardown();
        gAudioEngine.reset();
        return result;
    }
    return EngineResult::SUCCESS;
}

void setEngineAudioSource(std::shared_ptr<AudioSource> source) {
    auto* engine = getOrCreateEngine();
    if (engine) {
        engine->getPlayer().setAudioSource(std::move(source));
    }
}

} // namespace roombeat

// Helper macros for exception-safe JNI execution
#define JNI_METHOD_WRAPPER(callExpr, defaultError) \
    try { \
        return (callExpr); \
    } catch (const std::exception& ex) { \
        LOGE("Unhandled C++ exception in JNI: %s", ex.what()); \
        return (defaultError); \
    } catch (...) { \
        LOGE("Unhandled unknown C++ exception in JNI"); \
        return (defaultError); \
    }

extern "C" {

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeInitEngine(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::getOrCreateEngine()->init(), roombeat::EngineResult::ERROR_UNKNOWN)
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeStartStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::getOrCreateEngine()->startStream(), roombeat::EngineResult::ERROR_UNKNOWN)
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeStopStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::getOrCreateEngine()->stopStream(), roombeat::EngineResult::ERROR_UNKNOWN)
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLatencyMillis(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::getOrCreateEngine()->getAudioLatencyMillis(), 0)
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeTeardownEngine(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::destroyEngine(), roombeat::EngineResult::ERROR_UNKNOWN)
}

/*
 * Buffer feeding & telemetry query JNI methods
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeWriteAudioFrames(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloatArray audioData,
    jint numFrames
) {
    if (!audioData || numFrames <= 0) return 0;
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (!engine) return 0;

        jfloat* data = env->GetFloatArrayElements(audioData, nullptr);
        if (!data) return 0;

        const jint written = engine->getPlayer().write(reinterpret_cast<const float*>(data), numFrames);
        env->ReleaseFloatArrayElements(audioData, data, JNI_ABORT);
        return written;
    } catch (const std::exception& ex) {
        LOGE("Unhandled exception in nativeWriteAudioFrames: %s", ex.what());
        return 0;
    } catch (...) {
        LOGE("Unknown exception in nativeWriteAudioFrames");
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeWritePcm16Frames(
    JNIEnv* env,
    jobject /*thiz*/,
    jshortArray audioData,
    jint numFrames
) {
    if (!audioData || numFrames <= 0) return 0;
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (!engine) return 0;

        jshort* data = env->GetShortArrayElements(audioData, nullptr);
        if (!data) return 0;

        const jint written = engine->getPlayer().write(reinterpret_cast<const int16_t*>(data), numFrames);
        env->ReleaseShortArrayElements(audioData, data, JNI_ABORT);
        return written;
    } catch (const std::exception& ex) {
        LOGE("Unhandled exception in nativeWritePcm16Frames: %s", ex.what());
        return 0;
    } catch (...) {
        LOGE("Unknown exception in nativeWritePcm16Frames");
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAvailableFrames(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(roombeat::getOrCreateEngine()->getPlayer().getAvailableFrames(), 0)
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeClearBuffer(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) engine->getPlayer().clearBuffer();
    } catch (...) {
        LOGE("Exception in nativeClearBuffer");
    }
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetUnderrunCount(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    JNI_METHOD_WRAPPER(static_cast<jlong>(roombeat::getOrCreateEngine()->getPlayer().getUnderrunCount()), 0)
}

/*
 * Direct alias exports matching methods without 'native' prefix
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_initEngine(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeInitEngine(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_startStream(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeStartStream(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_stopStream(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeStopStream(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getAudioLatencyMillis(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLatencyMillis(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_teardownEngine(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeTeardownEngine(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_writeAudioFrames(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioData,
    jint numFrames
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeWriteAudioFrames(env, thiz, audioData, numFrames);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_writePcm16Frames(
    JNIEnv* env,
    jobject thiz,
    jshortArray audioData,
    jint numFrames
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeWritePcm16Frames(env, thiz, audioData, numFrames);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getAvailableFrames(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAvailableFrames(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_clearBuffer(
    JNIEnv* env,
    jobject thiz
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeClearBuffer(env, thiz);
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getUnderrunCount(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetUnderrunCount(env, thiz);
}

/*
 * Implementation of DefaultJniBridge JNI exports
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeInitEngine(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeInitEngine(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeStartStream(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeStartStream(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeStopStream(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeStopStream(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetAudioLatencyMillis(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLatencyMillis(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeTeardownEngine(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeTeardownEngine(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeWriteAudioFrames(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioData,
    jint numFrames
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeWriteAudioFrames(env, thiz, audioData, numFrames);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeWritePcm16Frames(
    JNIEnv* env,
    jobject thiz,
    jshortArray audioData,
    jint numFrames
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeWritePcm16Frames(env, thiz, audioData, numFrames);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetAvailableFrames(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAvailableFrames(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeClearBuffer(
    JNIEnv* env,
    jobject thiz
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeClearBuffer(env, thiz);
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetUnderrunCount(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetUnderrunCount(env, thiz);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeAttachJitterBuffer(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong jitterBufferHandle
) {
    if (jitterBufferHandle == 0) {
        roombeat::setAttachedJitterBuffer(nullptr);
        roombeat::setEngineAudioSource(nullptr);
        return JNI_TRUE;
    }
    auto* jitterBuffer = reinterpret_cast<roombeat::buffer::AudioJitterBuffer*>(jitterBufferHandle);
    roombeat::setAttachedJitterBuffer(jitterBuffer);
    if (jitterBuffer) {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) {
            jitterBuffer->setSpeedPpm(engine->getSpeedPpm());
        }
    }
    std::shared_ptr<roombeat::AudioSource> sharedSource(jitterBuffer, [](roombeat::AudioSource*){});
    roombeat::setEngineAudioSource(sharedSource);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_attachJitterBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong jitterBufferHandle
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeAttachJitterBuffer(env, thiz, jitterBufferHandle);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeAttachJitterBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong jitterBufferHandle
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeAttachJitterBuffer(env, thiz, jitterBufferHandle);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativePushAudioChunk(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
) {
    if (!opusData || length <= 0 || offset < 0) {
        return JNI_FALSE;
    }
    auto* jitterBuffer = roombeat::getAttachedJitterBuffer();
    if (!jitterBuffer) {
        return JNI_FALSE;
    }
    if (static_cast<size_t>(length) > roombeat::buffer::kMaxOpusPayloadBytes) {
        return JNI_FALSE;
    }

    uint8_t tempPayload[roombeat::buffer::kMaxOpusPayloadBytes];
    env->GetByteArrayRegion(opusData, offset, length, reinterpret_cast<jbyte*>(tempPayload));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }

    const bool queued = jitterBuffer->pushPacket(
        static_cast<uint64_t>(seq),
        static_cast<int64_t>(presentationTimeUs),
        tempPayload,
        static_cast<size_t>(length)
    );
    return queued ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_pushAudioChunk(
    JNIEnv* env,
    jobject thiz,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativePushAudioChunk(
        env, thiz, seq, presentationTimeUs, opusData, offset, length
    );
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativePushAudioChunk(
    JNIEnv* env,
    jobject thiz,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativePushAudioChunk(
        env, thiz, seq, presentationTimeUs, opusData, offset, length
    );
}

/*
 * Volume & Gain Control JNI implementations
 */

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetChannelVolume(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat volumeDb
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) {
            engine->setChannelVolume(static_cast<float>(volumeDb));
        }
    } catch (const std::exception& ex) {
        LOGE("Exception in nativeSetChannelVolume: %s", ex.what());
    } catch (...) {
        LOGE("Unknown exception in nativeSetChannelVolume");
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMasterVolume(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat volumeDb
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) {
            engine->setMasterVolume(static_cast<float>(volumeDb));
        }
    } catch (const std::exception& ex) {
        LOGE("Exception in nativeSetMasterVolume: %s", ex.what());
    } catch (...) {
        LOGE("Unknown exception in nativeSetMasterVolume");
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMuted(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean isMuted
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) {
            engine->setMuted(static_cast<bool>(isMuted));
        }
    } catch (const std::exception& ex) {
        LOGE("Exception in nativeSetMuted: %s", ex.what());
    } catch (...) {
        LOGE("Unknown exception in nativeSetMuted");
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setChannelVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetChannelVolume(env, thiz, volumeDb);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setMasterVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMasterVolume(env, thiz, volumeDb);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setMuted(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMuted(env, thiz, isMuted);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetChannelVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetChannelVolume(env, thiz, volumeDb);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetMasterVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMasterVolume(env, thiz, volumeDb);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetMuted(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMuted(env, thiz, isMuted);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetTargetStartTimeUs(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong targetTimeUs
) {
    auto* jitterBuffer = roombeat::getAttachedJitterBuffer();
    if (jitterBuffer) {
        jitterBuffer->setTargetStartTimeUs(static_cast<int64_t>(targetTimeUs));
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setTargetStartTimeUs(
    JNIEnv* env,
    jobject thiz,
    jlong targetTimeUs
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetTargetStartTimeUs(env, thiz, targetTimeUs);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetTargetStartTimeUs(
    JNIEnv* env,
    jobject thiz,
    jlong targetTimeUs
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetTargetStartTimeUs(env, thiz, targetTimeUs);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeFlushAndSeek(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong newInitialSeq,
    jlong newTargetStartTimeUs
) {
    auto* jitterBuffer = roombeat::getAttachedJitterBuffer();
    if (jitterBuffer) {
        jitterBuffer->flushAndSeek(static_cast<uint64_t>(newInitialSeq), static_cast<int64_t>(newTargetStartTimeUs));
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_flushAndSeek(
    JNIEnv* env,
    jobject thiz,
    jlong newInitialSeq,
    jlong newTargetStartTimeUs
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeFlushAndSeek(env, thiz, newInitialSeq, newTargetStartTimeUs);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeFlushAndSeek(
    JNIEnv* env,
    jobject thiz,
    jlong newInitialSeq,
    jlong newTargetStartTimeUs
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeFlushAndSeek(env, thiz, newInitialSeq, newTargetStartTimeUs);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeEncodeFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jshortArray pcmBuffer,
    jbyteArray outputBuffer
) {
    if (!pcmBuffer || !outputBuffer) return -1;
    try {
        auto* encoder = roombeat::getOrCreateCaptureEncoder();
        if (!encoder || !encoder->isValid()) return -1;

        const jsize pcmLen = env->GetArrayLength(pcmBuffer);
        const jsize outCap = env->GetArrayLength(outputBuffer);
        if (pcmLen < roombeat::audio::OpusEncoderWrapper::DEFAULT_INTERLEAVED_SAMPLES || outCap <= 0) {
            return -2;
        }

        jshort* pcmData = env->GetShortArrayElements(pcmBuffer, nullptr);
        if (!pcmData) return -1;

        jbyte* outData = env->GetByteArrayElements(outputBuffer, nullptr);
        if (!outData) {
            env->ReleaseShortArrayElements(pcmBuffer, pcmData, JNI_ABORT);
            return -1;
        }

        const int encodedBytes = encoder->encode(
            reinterpret_cast<const int16_t*>(pcmData),
            roombeat::audio::OpusEncoderWrapper::DEFAULT_FRAME_SIZE,
            reinterpret_cast<uint8_t*>(outData),
            outCap
        );

        env->ReleaseShortArrayElements(pcmBuffer, pcmData, JNI_ABORT);
        env->ReleaseByteArrayElements(outputBuffer, outData, 0);

        return encodedBytes;
    } catch (const std::exception& ex) {
        LOGE("Unhandled exception in nativeEncodeFrame: %s", ex.what());
        return -100;
    } catch (...) {
        LOGE("Unknown exception in nativeEncodeFrame");
        return -100;
    }
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_encodeFrame(
    JNIEnv* env,
    jobject thiz,
    jshortArray pcmBuffer,
    jbyteArray outputBuffer
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeEncodeFrame(env, thiz, pcmBuffer, outputBuffer);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeEncodeFrame(
    JNIEnv* env,
    jobject thiz,
    jshortArray pcmBuffer,
    jbyteArray outputBuffer
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeEncodeFrame(env, thiz, pcmBuffer, outputBuffer);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetSpeedPpm(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint ppm
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (engine) {
            engine->setSpeedPpm(static_cast<int32_t>(ppm));
        }
    } catch (const std::exception& ex) {
        LOGE("Exception in nativeSetSpeedPpm: %s", ex.what());
    } catch (...) {
        LOGE("Unknown exception in nativeSetSpeedPpm");
    }
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetSpeedPpm(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    try {
        auto* engine = roombeat::getOrCreateEngine();
        return engine ? engine->getSpeedPpm() : 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setSpeedPpm(
    JNIEnv* env,
    jobject thiz,
    jint ppm
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetSpeedPpm(env, thiz, ppm);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getSpeedPpm(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetSpeedPpm(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetSpeedPpm(
    JNIEnv* env,
    jobject thiz,
    jint ppm
) {
    Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetSpeedPpm(env, thiz, ppm);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetSpeedPpm(
    JNIEnv* env,
    jobject thiz
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetSpeedPpm(env, thiz);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLevels(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloatArray outLevels
) {
    if (!outLevels) return JNI_FALSE;
    jsize len = env->GetArrayLength(outLevels);
    if (len < 4) return JNI_FALSE;

    try {
        auto* engine = roombeat::getOrCreateEngine();
        if (!engine) return JNI_FALSE;

        float levels[4];
        if (engine->getAudioLevels(levels, 4)) {
            env->SetFloatArrayRegion(outLevels, 0, 4, levels);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getAudioLevels(
    JNIEnv* env,
    jobject thiz,
    jfloatArray outLevels
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLevels(env, thiz, outLevels);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetAudioLevels(
    JNIEnv* env,
    jobject thiz,
    jfloatArray outLevels
) {
    return Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLevels(env, thiz, outLevels);
}

} // extern "C"
