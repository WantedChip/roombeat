#ifndef ROOMBEAT_AUDIO_ENGINE_BRIDGE_H
#define ROOMBEAT_AUDIO_ENGINE_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
#include <memory>
namespace roombeat {
class AudioSource;
namespace buffer {
class AudioJitterBuffer;
}
void setEngineAudioSource(std::shared_ptr<AudioSource> source);
void setAttachedJitterBuffer(roombeat::buffer::AudioJitterBuffer* jitterBuffer);
roombeat::buffer::AudioJitterBuffer* getAttachedJitterBuffer();
}
extern "C" {
#endif

/*
 * Lifecycle JNI methods for com.roombeat.app.audio.NativeAudioEngine
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeInitEngine(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeStartStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeStopStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAudioLatencyMillis(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeTeardownEngine(
    JNIEnv* env,
    jobject thiz
);

/*
 * Buffer feeding & telemetry query JNI methods
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeWriteAudioFrames(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeWritePcm16Frames(
    JNIEnv* env,
    jobject thiz,
    jshortArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetAvailableFrames(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeClearBuffer(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeGetUnderrunCount(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeAttachJitterBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong jitterBufferHandle
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativePushAudioChunk(
    JNIEnv* env,
    jobject thiz,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetChannelVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMasterVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_nativeSetMuted(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted
);

/*
 * Direct alias methods without 'native' prefix for JNI flexibility
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_initEngine(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_startStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_stopStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getAudioLatencyMillis(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_teardownEngine(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_writeAudioFrames(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_writePcm16Frames(
    JNIEnv* env,
    jobject thiz,
    jshortArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getAvailableFrames(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_clearBuffer(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_getUnderrunCount(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_attachJitterBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong jitterBufferHandle
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_pushAudioChunk(
    JNIEnv* env,
    jobject thiz,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setChannelVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setMasterVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_setMuted(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted
);

/*
 * JNI methods for nested DefaultJniBridge
 */

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeInitEngine(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeStartStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeStopStream(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetAudioLatencyMillis(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeTeardownEngine(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeWriteAudioFrames(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeWritePcm16Frames(
    JNIEnv* env,
    jobject thiz,
    jshortArray audioData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetAvailableFrames(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeClearBuffer(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeGetUnderrunCount(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeAttachJitterBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong jitterBufferHandle
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativePushAudioChunk(
    JNIEnv* env,
    jobject thiz,
    jlong seq,
    jlong presentationTimeUs,
    jbyteArray opusData,
    jint offset,
    jint length
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetChannelVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetMasterVolume(
    JNIEnv* env,
    jobject thiz,
    jfloat volumeDb
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_NativeAudioEngine_00024DefaultJniBridge_nativeSetMuted(
    JNIEnv* env,
    jobject thiz,
    jboolean isMuted
);

#ifdef __cplusplus
}
#endif

#endif // ROOMBEAT_AUDIO_ENGINE_BRIDGE_H
