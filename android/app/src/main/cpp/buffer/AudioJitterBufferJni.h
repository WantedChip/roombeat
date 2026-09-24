#ifndef ROOMBEAT_AUDIO_JITTER_BUFFER_JNI_H
#define ROOMBEAT_AUDIO_JITTER_BUFFER_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * JNI methods for com.roombeat.app.audio.buffer.AudioJitterBuffer
 */

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeCreate(
    JNIEnv* env,
    jclass clazz,
    jint targetDepthMs,
    jint capacityFrames
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushPacket(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jlong sequenceNumber,
    jlong presentationTimeUs,
    jbyteArray payload,
    jint offset,
    jint length
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePushDecodedFrame(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jlong sequenceNumber,
    jlong presentationTimeUs,
    jfloatArray pcmData,
    jint numFrames
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativePullFrames(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jfloatArray outputBuffer,
    jint numFrames
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeSetTargetDepthMs(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jint depthMs
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetTargetDepthMs(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetQueuedFrames(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jlongArray JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeGetStats(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeReset(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_AudioJitterBuffer_nativeAttachToEngine(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

/*
 * DefaultAudioJitterBufferBridge instance method aliases
 */

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeCreate(
    JNIEnv* env, jobject thiz, jint targetDepthMs, jint capacityFrames);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePushPacket(
    JNIEnv* env, jobject thiz, jlong handle, jlong sequenceNumber, jlong presentationTimeUs, jbyteArray payload, jint offset, jint length);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePushDecodedFrame(
    JNIEnv* env, jobject thiz, jlong handle, jlong sequenceNumber, jlong presentationTimeUs, jfloatArray pcmData, jint numFrames);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativePullFrames(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray outputBuffer, jint numFrames);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeSetTargetDepthMs(
    JNIEnv* env, jobject thiz, jlong handle, jint depthMs);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetTargetDepthMs(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetQueuedFrames(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jlongArray JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeGetStats(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeReset(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jboolean JNICALL
Java_com_roombeat_app_audio_buffer_DefaultAudioJitterBufferBridge_nativeAttachToEngine(
    JNIEnv* env, jobject thiz, jlong handle);

#ifdef __cplusplus
}
#endif

#endif // ROOMBEAT_AUDIO_JITTER_BUFFER_JNI_H
