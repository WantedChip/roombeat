#include "AudioEngineBridge.h"
#include "OboeAudioPlayer.h"

#include <android/log.h>
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

    OboeAudioPlayer& getPlayer() {
        return player_;
    }

private:
    std::mutex engineMutex_;
    OboeAudioPlayer player_;
    bool isInitialized_{false};
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

static jint destroyEngine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gAudioEngine) {
        const jint result = gAudioEngine->teardown();
        gAudioEngine.reset();
        return result;
    }
    return EngineResult::SUCCESS;
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

} // extern "C"
