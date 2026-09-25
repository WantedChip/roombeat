#include "AudioJitterBufferJni.h"
#include "AudioJitterBuffer.h"
#include "AudioEngineBridge.h"
#include "OboeAudioPlayer.h"
#include <android/log.h>
#include <memory>

#define LOG_TAG "RoomBeatJitterBufferJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)



namespace {

inline roombeat::buffer::AudioJitterBuffer* getJitterBuffer(jlong handle) {
    return reinterpret_cast<roombeat::buffer::AudioJitterBuffer*>(handle);
}

} // anonymous namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeCreate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint targetDepthMs,
    jint capacityFrames
) {
    try {
        auto* buffer = new roombeat::buffer::AudioJitterBuffer(
            targetDepthMs > 0 ? targetDepthMs : roombeat::buffer::kDefaultTargetDepthMs,
            capacityFrames > 0 ? static_cast<size_t>(capacityFrames) : roombeat::buffer::kDefaultCapacityFrames
        );
        return reinterpret_cast<jlong>(buffer);
    } catch (const std::exception& e) {
        LOGE("Failed to create AudioJitterBuffer: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    if (buffer) {
        delete buffer;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushPacket(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong sequenceNumber,
    jlong presentationTimeUs,
    jbyteArray payload,
    jint offset,
    jint length
) {
    auto* buffer = getJitterBuffer(handle);
    if (!buffer || !payload || length <= 0 || offset < 0) {
        return JNI_FALSE;
    }

    if (static_cast<size_t>(length) > roombeat::buffer::kMaxOpusPayloadBytes) {
        return JNI_FALSE;
    }

    uint8_t tempPayload[roombeat::buffer::kMaxOpusPayloadBytes];
    env->GetByteArrayRegion(payload, offset, length, reinterpret_cast<jbyte*>(tempPayload));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }

    const bool queued = buffer->pushPacket(
        static_cast<uint64_t>(sequenceNumber),
        static_cast<int64_t>(presentationTimeUs),
        tempPayload,
        static_cast<size_t>(length)
    );
    return queued ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushDecodedFrame(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong sequenceNumber,
    jlong presentationTimeUs,
    jfloatArray pcmData,
    jint numFrames
) {
    auto* buffer = getJitterBuffer(handle);
    if (!buffer || !pcmData || numFrames <= 0) {
        return JNI_FALSE;
    }

    jfloat* pcmPtr = env->GetFloatArrayElements(pcmData, nullptr);
    if (!pcmPtr) {
        return JNI_FALSE;
    }

    const bool queued = buffer->pushDecodedFrame(
        static_cast<uint64_t>(sequenceNumber),
        static_cast<int64_t>(presentationTimeUs),
        pcmPtr,
        numFrames
    );

    env->ReleaseFloatArrayElements(pcmData, pcmPtr, JNI_ABORT);
    return queued ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePullFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jfloatArray outputBuffer,
    jint numFrames
) {
    auto* buffer = getJitterBuffer(handle);
    if (!buffer || !outputBuffer || numFrames <= 0) {
        return 0;
    }

    jfloat* outPtr = env->GetFloatArrayElements(outputBuffer, nullptr);
    if (!outPtr) {
        return 0;
    }

    const int32_t rendered = buffer->pullFrames(outPtr, numFrames);
    env->ReleaseFloatArrayElements(outputBuffer, outPtr, 0); // Commit changes back to JVM
    return rendered;
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeSetTargetDepthMs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint depthMs
) {
    auto* buffer = getJitterBuffer(handle);
    if (buffer) {
        buffer->setTargetDepthMs(depthMs);
    }
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetTargetDepthMs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    return buffer ? buffer->getTargetDepthMs() : 0;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetQueuedFrames(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    return buffer ? buffer->getQueuedFrames() : 0;
}

JNIEXPORT jlongArray JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetStats(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    if (!buffer) return nullptr;

    const auto stats = buffer->getStats();
    jlong values[9] = {
        stats.totalPacketsReceived,
        stats.packetsPlayed,
        stats.plcCount,
        stats.latePacketsDropped,
        stats.duplicatePacketsDropped,
        stats.underrunCount,
        static_cast<jlong>(stats.currentQueuedFrames),
        static_cast<jlong>(stats.targetDepthMs),
        static_cast<jlong>(stats.consecutiveLostFrames)
    };

    jlongArray result = env->NewLongArray(9);
    if (result) {
        env->SetLongArrayRegion(result, 0, 9, values);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeReset(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    if (buffer) {
        buffer->reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeAttachToEngine(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    if (!buffer) return JNI_FALSE;

    std::shared_ptr<roombeat::AudioSource> source(buffer, [](roombeat::AudioSource*){});
    roombeat::setEngineAudioSource(source);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeSetTargetStartTimeUs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong targetTimeUs
) {
    auto* buffer = getJitterBuffer(handle);
    if (buffer) {
        buffer->setTargetStartTimeUs(static_cast<int64_t>(targetTimeUs));
    }
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetTargetStartTimeUs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* buffer = getJitterBuffer(handle);
    return buffer ? static_cast<jlong>(buffer->getTargetStartTimeUs()) : 0L;
}

/*
 * DefaultAudioJitterBufferBridge instance method aliases
 */

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jint targetDepthMs, jint capacityFrames) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeCreate(env, nullptr, targetDepthMs, capacityFrames);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeDestroy(env, nullptr, handle);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePushPacket(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong sequenceNumber, jlong presentationTimeUs, jbyteArray payload, jint offset, jint length) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushPacket(
        env, nullptr, handle, sequenceNumber, presentationTimeUs, payload, offset, length);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePushDecodedFrame(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong sequenceNumber, jlong presentationTimeUs, jfloatArray pcmData, jint numFrames) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushDecodedFrame(
        env, nullptr, handle, sequenceNumber, presentationTimeUs, pcmData, numFrames);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePullFrames(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray outputBuffer, jint numFrames) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePullFrames(
        env, nullptr, handle, outputBuffer, numFrames);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeSetTargetDepthMs(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint depthMs) {
    Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeSetTargetDepthMs(
        env, nullptr, handle, depthMs);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetTargetDepthMs(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetTargetDepthMs(
        env, nullptr, handle);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetQueuedFrames(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetQueuedFrames(
        env, nullptr, handle);
}

JNIEXPORT jlongArray JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetStats(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetStats(
        env, nullptr, handle);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeReset(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeReset(env, nullptr, handle);
}

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeAttachToEngine(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeAttachToEngine(
        env, nullptr, handle);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeSetTargetStartTimeUs(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong targetTimeUs) {
    Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeSetTargetStartTimeUs(
        env, nullptr, handle, targetTimeUs);
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetTargetStartTimeUs(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetTargetStartTimeUs(
        env, nullptr, handle);
}

} // extern "C"
