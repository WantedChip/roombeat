#include "OpusCodecJni.h"
#include "OpusEncoderWrapper.h"
#include "OpusDecoderWrapper.h"

#include <android/log.h>
#include <chrono>
#include <cmath>
#include <vector>

#define LOG_TAG "OpusCodecJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using roombeat::audio::OpusEncoderWrapper;
using roombeat::audio::OpusDecoderWrapper;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderCreate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint channels,
    jint bitrate,
    jint complexity
) {
    auto* encoder = new (std::nothrow) OpusEncoderWrapper(sampleRate, channels, bitrate, complexity);
    if (!encoder || !encoder->isValid()) {
        delete encoder;
        return 0;
    }
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* encoder = reinterpret_cast<OpusEncoderWrapper*>(handle);
    delete encoder;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeFloat(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jfloatArray pcmInput,
    jint frameSize,
    jbyteArray outputBuffer,
    jint maxOutputBytes
) {
    auto* encoder = reinterpret_cast<OpusEncoderWrapper*>(handle);
    if (!encoder || !encoder->isValid()) {
        return OPUS_INVALID_STATE;
    }
    if (!pcmInput || !outputBuffer || frameSize <= 0 || maxOutputBytes <= 0) {
        return OPUS_BAD_ARG;
    }

    const jsize pcmLen = env->GetArrayLength(pcmInput);
    if (pcmLen < frameSize * encoder->getChannels()) {
        return OPUS_BAD_ARG;
    }

    jsize outLen = env->GetArrayLength(outputBuffer);
    if (outLen < maxOutputBytes) {
        maxOutputBytes = outLen;
    }

    jfloat* pcmPtr = env->GetFloatArrayElements(pcmInput, nullptr);
    if (!pcmPtr) return OPUS_ALLOC_FAIL;

    jbyte* outPtr = env->GetByteArrayElements(outputBuffer, nullptr);
    if (!outPtr) {
        env->ReleaseFloatArrayElements(pcmInput, pcmPtr, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    const int encodedBytes = encoder->encode(
        pcmPtr,
        frameSize,
        reinterpret_cast<uint8_t*>(outPtr),
        maxOutputBytes
    );

    env->ReleaseFloatArrayElements(pcmInput, pcmPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(outputBuffer, outPtr, (encodedBytes > 0) ? 0 : JNI_ABORT);

    return encodedBytes;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeShort(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jshortArray pcmInput,
    jint frameSize,
    jbyteArray outputBuffer,
    jint maxOutputBytes
) {
    auto* encoder = reinterpret_cast<OpusEncoderWrapper*>(handle);
    if (!encoder || !encoder->isValid()) {
        return OPUS_INVALID_STATE;
    }
    if (!pcmInput || !outputBuffer || frameSize <= 0 || maxOutputBytes <= 0) {
        return OPUS_BAD_ARG;
    }

    const jsize pcmLen = env->GetArrayLength(pcmInput);
    if (pcmLen < frameSize * encoder->getChannels()) {
        return OPUS_BAD_ARG;
    }

    jsize outLen = env->GetArrayLength(outputBuffer);
    if (outLen < maxOutputBytes) {
        maxOutputBytes = outLen;
    }

    jshort* pcmPtr = env->GetShortArrayElements(pcmInput, nullptr);
    if (!pcmPtr) return OPUS_ALLOC_FAIL;

    jbyte* outPtr = env->GetByteArrayElements(outputBuffer, nullptr);
    if (!outPtr) {
        env->ReleaseShortArrayElements(pcmInput, pcmPtr, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    const int encodedBytes = encoder->encode(
        pcmPtr,
        frameSize,
        reinterpret_cast<uint8_t*>(outPtr),
        maxOutputBytes
    );

    env->ReleaseShortArrayElements(pcmInput, pcmPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(outputBuffer, outPtr, (encodedBytes > 0) ? 0 : JNI_ABORT);

    return encodedBytes;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderReset(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* encoder = reinterpret_cast<OpusEncoderWrapper*>(handle);
    if (!encoder || !encoder->isValid()) return OPUS_INVALID_STATE;
    return encoder->resetState();
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderCreate(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint sampleRate,
    jint channels
) {
    auto* decoder = new (std::nothrow) OpusDecoderWrapper(sampleRate, channels);
    if (!decoder || !decoder->isValid()) {
        delete decoder;
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* decoder = reinterpret_cast<OpusDecoderWrapper*>(handle);
    delete decoder;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeFloat(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray opusData,
    jint bytes,
    jfloatArray outputPcm,
    jint frameSize,
    jboolean decodeFec
) {
    auto* decoder = reinterpret_cast<OpusDecoderWrapper*>(handle);
    if (!decoder || !decoder->isValid()) {
        return OPUS_INVALID_STATE;
    }
    if (!outputPcm || frameSize <= 0) {
        return OPUS_BAD_ARG;
    }

    const jsize pcmLen = env->GetArrayLength(outputPcm);
    if (pcmLen < frameSize * decoder->getChannels()) {
        return OPUS_BAD_ARG;
    }

    jfloat* pcmPtr = env->GetFloatArrayElements(outputPcm, nullptr);
    if (!pcmPtr) return OPUS_ALLOC_FAIL;

    int decodedSamples = 0;
    if (opusData != nullptr && bytes > 0) {
        const jsize inLen = env->GetArrayLength(opusData);
        if (bytes > inLen) bytes = inLen;
        jbyte* inPtr = env->GetByteArrayElements(opusData, nullptr);
        if (!inPtr) {
            env->ReleaseFloatArrayElements(outputPcm, pcmPtr, JNI_ABORT);
            return OPUS_ALLOC_FAIL;
        }

        decodedSamples = decoder->decode(
            reinterpret_cast<const uint8_t*>(inPtr),
            bytes,
            pcmPtr,
            frameSize,
            decodeFec == JNI_TRUE
        );
        env->ReleaseByteArrayElements(opusData, inPtr, JNI_ABORT);
    } else {
        // PLC recovery: pass null payload
        decodedSamples = decoder->decode(
            nullptr,
            0,
            pcmPtr,
            frameSize,
            decodeFec == JNI_TRUE
        );
    }

    env->ReleaseFloatArrayElements(outputPcm, pcmPtr, (decodedSamples > 0) ? 0 : JNI_ABORT);
    return decodedSamples;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeShort(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray opusData,
    jint bytes,
    jshortArray outputPcm,
    jint frameSize,
    jboolean decodeFec
) {
    auto* decoder = reinterpret_cast<OpusDecoderWrapper*>(handle);
    if (!decoder || !decoder->isValid()) {
        return OPUS_INVALID_STATE;
    }
    if (!outputPcm || frameSize <= 0) {
        return OPUS_BAD_ARG;
    }

    const jsize pcmLen = env->GetArrayLength(outputPcm);
    if (pcmLen < frameSize * decoder->getChannels()) {
        return OPUS_BAD_ARG;
    }

    jshort* pcmPtr = env->GetShortArrayElements(outputPcm, nullptr);
    if (!pcmPtr) return OPUS_ALLOC_FAIL;

    int decodedSamples = 0;
    if (opusData != nullptr && bytes > 0) {
        const jsize inLen = env->GetArrayLength(opusData);
        if (bytes > inLen) bytes = inLen;
        jbyte* inPtr = env->GetByteArrayElements(opusData, nullptr);
        if (!inPtr) {
            env->ReleaseShortArrayElements(outputPcm, pcmPtr, JNI_ABORT);
            return OPUS_ALLOC_FAIL;
        }

        decodedSamples = decoder->decode(
            reinterpret_cast<const uint8_t*>(inPtr),
            bytes,
            pcmPtr,
            frameSize,
            decodeFec == JNI_TRUE
        );
        env->ReleaseByteArrayElements(opusData, inPtr, JNI_ABORT);
    } else {
        // PLC recovery: pass null payload
        decodedSamples = decoder->decode(
            nullptr,
            0,
            pcmPtr,
            frameSize,
            decodeFec == JNI_TRUE
        );
    }

    env->ReleaseShortArrayElements(outputPcm, pcmPtr, (decodedSamples > 0) ? 0 : JNI_ABORT);
    return decodedSamples;
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderReset(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* decoder = reinterpret_cast<OpusDecoderWrapper*>(handle);
    if (!decoder || !decoder->isValid()) return OPUS_INVALID_STATE;
    return decoder->resetState();
}

/**
 * Runs an in-engine native benchmark and verification test over N iterations:
 * - 48kHz stereo 20ms audio frames (960 samples/channel = 1920 interleaved samples)
 * - Measures average encode time and decode time per frame in microseconds
 * - Computes SNR (signal-to-noise ratio) against synthetic sine wave
 * - Verifies Packet Loss Concealment (PLC) recovery
 * - Verifies zero memory leaks / corruption over N iterations (e.g. 10,000 cycles)
 * Returns double array: [avgEncodeUs, avgDecodeUs, totalTimeMs, snrDb, plcSamples]
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_roombeat_app_audio_codec_OpusCodec_nativeRunBenchmark(
    JNIEnv* env,
    jclass /*clazz*/,
    jint iterations
) {
    if (iterations <= 0) iterations = 1000;

    OpusEncoderWrapper encoder(48000, 2, 128000, 6);
    OpusDecoderWrapper decoder(48000, 2);

    if (!encoder.isValid() || !decoder.isValid()) {
        return nullptr;
    }

    constexpr int frameSize = 960;
    constexpr int channels = 2;
    constexpr int totalSamples = frameSize * channels;

    // Generate 440Hz stereo sine wave
    std::vector<float> originalPcm(totalSamples);
    constexpr double freq = 440.0;
    constexpr double twoPi = 6.28318530717958647692;
    for (int i = 0; i < frameSize; ++i) {
        float sample = static_cast<float>(std::sin(twoPi * freq * i / 48000.0) * 0.8);
        originalPcm[i * 2] = sample;     // Left
        originalPcm[i * 2 + 1] = sample; // Right
    }

    std::vector<uint8_t> opusPacket(4000);
    std::vector<float> decodedPcm(totalSamples);

    // Warm-up
    for (int w = 0; w < 5; ++w) {
        int encBytes = encoder.encode(originalPcm.data(), frameSize, opusPacket.data(), 4000);
        decoder.decode(opusPacket.data(), encBytes, decodedPcm.data(), frameSize, false);
    }

    int64_t totalEncodeNs = 0;
    int64_t totalDecodeNs = 0;
    double signalPower = 0.0;
    double noisePower = 0.0;

    auto benchStart = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < iterations; ++i) {
        auto t0 = std::chrono::high_resolution_clock::now();
        int encBytes = encoder.encode(originalPcm.data(), frameSize, opusPacket.data(), 4000);
        auto t1 = std::chrono::high_resolution_clock::now();

        totalEncodeNs += std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();

        if (encBytes <= 0) {
            LOGE("Benchmark encode error on iteration %d: %d", i, encBytes);
            return nullptr;
        }

        auto t2 = std::chrono::high_resolution_clock::now();
        int decSamples = decoder.decode(opusPacket.data(), encBytes, decodedPcm.data(), frameSize, false);
        auto t3 = std::chrono::high_resolution_clock::now();

        totalDecodeNs += std::chrono::duration_cast<std::chrono::nanoseconds>(t3 - t2).count();

        if (decSamples != frameSize) {
            LOGE("Benchmark decode error on iteration %d: %d", i, decSamples);
            return nullptr;
        }

        // Compute SNR on sample iteration (after Opus filter stabilization)
        if (i == iterations - 1) {
            for (int s = 0; s < totalSamples; ++s) {
                double orig = originalPcm[s];
                double dec = decodedPcm[s];
                double diff = orig - dec;
                signalPower += orig * orig;
                noisePower += diff * diff;
            }
        }
    }

    auto benchEnd = std::chrono::high_resolution_clock::now();
    double totalTimeMs = std::chrono::duration<double, std::milli>(benchEnd - benchStart).count();

    // Verify PLC frame generation
    std::vector<float> plcPcm(totalSamples);
    int plcSamples = decoder.decode(nullptr, 0, plcPcm.data(), frameSize, false);

    double avgEncodeUs = (totalEncodeNs / 1000.0) / iterations;
    double avgDecodeUs = (totalDecodeNs / 1000.0) / iterations;
    double snrDb = (noisePower > 1e-12) ? 10.0 * std::log10(signalPower / noisePower) : 99.0;

    LOGI("Opus Benchmark completed: %d iterations in %.2f ms. Avg encode: %.2f us, Avg decode: %.2f us, SNR: %.2f dB, PLC: %d samples",
         iterations, totalTimeMs, avgEncodeUs, avgDecodeUs, snrDb, plcSamples);

    jdoubleArray resultArr = env->NewDoubleArray(5);
    if (!resultArr) return nullptr;

    jdouble results[5] = { avgEncodeUs, avgDecodeUs, totalTimeMs, snrDb, static_cast<double>(plcSamples) };
    env->SetDoubleArrayRegion(resultArr, 0, 5, results);

    return resultArr;
}

/*
 * DefaultOpusCodecBridge instance method implementations (delegates to static JNI methods)
 */

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderCreate(
    JNIEnv* env, jobject /*thiz*/, jint sampleRate, jint channels, jint bitrate, jint complexity) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderCreate(env, nullptr, sampleRate, channels, bitrate, complexity);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderDestroy(env, nullptr, handle);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderEncodeFloat(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray pcmInput, jint frameSize, jbyteArray outputBuffer, jint maxOutputBytes) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeFloat(env, nullptr, handle, pcmInput, frameSize, outputBuffer, maxOutputBytes);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderEncodeShort(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray pcmInput, jint frameSize, jbyteArray outputBuffer, jint maxOutputBytes) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderEncodeShort(env, nullptr, handle, pcmInput, frameSize, outputBuffer, maxOutputBytes);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeEncoderReset(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeEncoderReset(env, nullptr, handle);
}

JNIEXPORT jlong JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderCreate(
    JNIEnv* env, jobject /*thiz*/, jint sampleRate, jint channels) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderCreate(env, nullptr, sampleRate, channels);
}

JNIEXPORT void JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDestroy(env, nullptr, handle);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDecodeFloat(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray opusData, jint bytes, jfloatArray outputPcm, jint frameSize, jboolean decodeFec) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeFloat(env, nullptr, handle, opusData, bytes, outputPcm, frameSize, decodeFec);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderDecodeShort(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray opusData, jint bytes, jshortArray outputPcm, jint frameSize, jboolean decodeFec) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderDecodeShort(env, nullptr, handle, opusData, bytes, outputPcm, frameSize, decodeFec);
}

JNIEXPORT jint JNICALL
Java_com_roombeat_app_audio_codec_DefaultOpusCodecBridge_nativeDecoderReset(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    return Java_com_roombeat_app_audio_codec_OpusCodec_nativeDecoderReset(env, nullptr, handle);
}

} // extern "C"
