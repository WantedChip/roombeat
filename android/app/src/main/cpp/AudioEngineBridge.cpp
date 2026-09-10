#include "AudioEngineBridge.h"

#include <oboe/Oboe.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <cmath>
#include <cstring>
#include <exception>

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

// Target audio specifications per project guidelines
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannelCount = 2; // Stereo

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override {
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

        LOGI("Initializing RoomBeat AudioEngine (48kHz, stereo, low latency)...");
        isInitialized_ = true;
        return EngineResult::SUCCESS;
    }

    jint startStream() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (!isInitialized_) {
            LOGE("Cannot start stream: AudioEngine is not initialized.");
            return EngineResult::ERROR_INVALID_STATE;
        }

        if (stream_ && stream_->getState() == oboe::StreamState::Started) {
            LOGI("Audio stream is already running.");
            return EngineResult::SUCCESS;
        }

        // Cleanly close and release any existing stream
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::I16)
               ->setChannelCount(kChannelCount)
               ->setSampleRate(kSampleRate)
               ->setUsage(oboe::Usage::Media)
               ->setContentType(oboe::ContentType::Music)
               ->setDataCallback(this)
               ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open Oboe audio stream. Error: %s", oboe::convertToText(result));
            return EngineResult::ERROR_STREAM_OPEN_FAILED;
        }

        LOGI("Oboe audio stream opened: sampleRate=%d, channels=%d, bufferCapacity=%d",
             stream_->getSampleRate(),
             stream_->getChannelCount(),
             stream_->getBufferCapacityInFrames());

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe audio stream. Error: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return EngineResult::ERROR_STREAM_START_FAILED;
        }

        LOGI("Oboe audio stream started successfully.");
        return EngineResult::SUCCESS;
    }

    jint stopStream() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (!stream_) {
            LOGI("Audio stream is not running.");
            return EngineResult::SUCCESS;
        }

        oboe::Result result = stream_->requestStop();
        if (result != oboe::Result::OK) {
            LOGW("Failed to requestStop stream: %s", oboe::convertToText(result));
        }

        result = stream_->close();
        if (result != oboe::Result::OK) {
            LOGW("Failed to close stream: %s", oboe::convertToText(result));
        }

        stream_.reset();
        LOGI("Oboe audio stream stopped and closed.");
        return EngineResult::SUCCESS;
    }

    jint getAudioLatencyMillis() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (!stream_) {
            return 0;
        }

        auto latencyResult = stream_->calculateLatencyMillis();
        if (latencyResult) {
            double latencyMs = latencyResult.value();
            if (latencyMs < 0.0) latencyMs = 0.0;
            return static_cast<jint>(std::round(latencyMs));
        }

        // Fallback: calculate estimated latency from buffer size and sample rate
        int32_t bufferSize = stream_->getBufferSizeInFrames();
        int32_t sampleRate = stream_->getSampleRate();
        if (sampleRate > 0 && bufferSize > 0) {
            double estimatedMs = (static_cast<double>(bufferSize) / sampleRate) * 1000.0;
            return static_cast<jint>(std::round(estimatedMs));
        }

        return 0;
    }

    jint teardown() {
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
        isInitialized_ = false;
        LOGI("AudioEngine teardown complete.");
        return EngineResult::SUCCESS;
    }

    // oboe::AudioStreamDataCallback implementation
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* /*oboeStream*/,
        void* audioData,
        int32_t numFrames
    ) override {
        // Output silence (16-bit PCM stereo: 2 channels * 2 bytes per frame)
        const size_t bytesToWrite = static_cast<size_t>(numFrames) * kChannelCount * sizeof(int16_t);
        std::memset(audioData, 0, bytesToWrite);
        return oboe::DataCallbackResult::Continue;
    }

    // oboe::AudioStreamErrorCallback implementation
    void onErrorBeforeClose(oboe::AudioStream* /*oboeStream*/, oboe::Result error) override {
        LOGW("Oboe stream error before close: %s", oboe::convertToText(error));
    }

    void onErrorAfterClose(oboe::AudioStream* /*oboeStream*/, oboe::Result error) override {
        LOGW("Oboe stream error after close: %s", oboe::convertToText(error));
        std::lock_guard<std::mutex> lock(engineMutex_);
        if (stream_) {
            stream_.reset();
        }
    }

private:
    std::mutex engineMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
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
        jint result = gAudioEngine->teardown();
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

} // extern "C"

