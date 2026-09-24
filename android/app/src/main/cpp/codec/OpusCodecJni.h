#ifndef ROOMBEAT_OPUS_CODEC_JNI_H
#define ROOMBEAT_OPUS_CODEC_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * JNI methods for com.roombeat.app.audio.codec.OpusCodec
 */

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderCreate(
    JNIEnv* env,
    jclass clazz,
    jint sampleRate,
    jint channels,
    jint bitrate,
    jint complexity
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeFloat(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jfloatArray pcmInput,
    jint frameSize,
    jbyteArray outputBuffer,
    jint maxOutputBytes
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeShort(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jshortArray pcmInput,
    jint frameSize,
    jbyteArray outputBuffer,
    jint maxOutputBytes
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderReset(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderCreate(
    JNIEnv* env,
    jclass clazz,
    jint sampleRate,
    jint channels
);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDestroy(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeFloat(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jbyteArray opusData,
    jint bytes,
    jfloatArray outputPcm,
    jint frameSize,
    jboolean decodeFec
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeShort(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jbyteArray opusData,
    jint bytes,
    jshortArray outputPcm,
    jint frameSize,
    jboolean decodeFec
);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderReset(
    JNIEnv* env,
    jclass clazz,
    jlong handle
);

JNIEXPORT jdoubleArray JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeRunBenchmark(
    JNIEnv* env,
    jclass clazz,
    jint iterations
);

/*
 * DefaultOpusCodecBridge instance method aliases
 */
JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderCreate(
    JNIEnv* env, jobject thiz, jint sampleRate, jint channels, jint bitrate, jint complexity);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderDestroy(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderEncodeFloat(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray pcmInput, jint frameSize, jbyteArray outputBuffer, jint maxOutputBytes);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderEncodeShort(
    JNIEnv* env, jobject thiz, jlong handle, jshortArray pcmInput, jint frameSize, jbyteArray outputBuffer, jint maxOutputBytes);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderReset(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderCreate(
    JNIEnv* env, jobject thiz, jint sampleRate, jint channels);

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDestroy(
    JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDecodeFloat(
    JNIEnv* env, jobject thiz, jlong handle, jbyteArray opusData, jint bytes, jfloatArray outputPcm, jint frameSize, jboolean decodeFec);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDecodeShort(
    JNIEnv* env, jobject thiz, jlong handle, jbyteArray opusData, jint bytes, jshortArray outputPcm, jint frameSize, jboolean decodeFec);

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderReset(
    JNIEnv* env, jobject thiz, jlong handle);

#ifdef __cplusplus
}
#endif

#endif // ROOMBEAT_OPUS_CODEC_JNI_H
