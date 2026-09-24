#include "OpusDecoderWrapper.h"
#include <android/log.h>
#include <utility>

#define LOG_TAG "OpusDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace roombeat {
namespace audio {

OpusDecoderWrapper::OpusDecoderWrapper(int sampleRate, int channels)
    : sampleRate_(sampleRate), channels_(channels) {
    int err = OPUS_OK;
    decoder_ = opus_decoder_create(sampleRate_, channels_, &err);
    if (err != OPUS_OK || !decoder_) {
        LOGE("Failed to create OpusDecoder: error %d (%s)", err, opus_strerror(err));
        decoder_ = nullptr;
        return;
    }
    LOGI("OpusDecoder created: %d Hz, %d channels", sampleRate_, channels_);
}

OpusDecoderWrapper::~OpusDecoderWrapper() {
    if (decoder_) {
        opus_decoder_destroy(decoder_);
        decoder_ = nullptr;
    }
}

OpusDecoderWrapper::OpusDecoderWrapper(OpusDecoderWrapper&& other) noexcept
    : decoder_(other.decoder_),
      sampleRate_(other.sampleRate_),
      channels_(other.channels_) {
    other.decoder_ = nullptr;
}

OpusDecoderWrapper& OpusDecoderWrapper::operator=(OpusDecoderWrapper&& other) noexcept {
    if (this != &other) {
        if (decoder_) {
            opus_decoder_destroy(decoder_);
        }
        decoder_ = other.decoder_;
        sampleRate_ = other.sampleRate_;
        channels_ = other.channels_;
        other.decoder_ = nullptr;
    }
    return *this;
}

int OpusDecoderWrapper::decode(const uint8_t* opusData, int bytes, float* outputPcm, int frameSize, bool decodeFec) {
    if (!decoder_) {
        LOGE("OpusDecoderWrapper::decode(float): decoder is not initialized");
        return OPUS_INVALID_STATE;
    }
    if (!outputPcm || frameSize <= 0) {
        LOGE("OpusDecoderWrapper::decode(float): invalid output buffer or frameSize");
        return OPUS_BAD_ARG;
    }

    // Packet Loss Concealment (PLC): pass nullptr and length 0
    const unsigned char* inputPayload = (opusData != nullptr && bytes > 0) ? opusData : nullptr;
    const opus_int32 payloadBytes = (inputPayload != nullptr) ? static_cast<opus_int32>(bytes) : 0;

    const int decodedSamples = opus_decode_float(
        decoder_,
        inputPayload,
        payloadBytes,
        outputPcm,
        frameSize,
        decodeFec ? 1 : 0
    );

    if (decodedSamples < 0) {
        LOGE("Opus decode float failed: %d (%s)", decodedSamples, opus_strerror(decodedSamples));
    }
    return decodedSamples;
}

int OpusDecoderWrapper::decode(const uint8_t* opusData, int bytes, int16_t* outputPcm, int frameSize, bool decodeFec) {
    if (!decoder_) {
        LOGE("OpusDecoderWrapper::decode(int16): decoder is not initialized");
        return OPUS_INVALID_STATE;
    }
    if (!outputPcm || frameSize <= 0) {
        LOGE("OpusDecoderWrapper::decode(int16): invalid output buffer or frameSize");
        return OPUS_BAD_ARG;
    }

    // Packet Loss Concealment (PLC): pass nullptr and length 0
    const unsigned char* inputPayload = (opusData != nullptr && bytes > 0) ? opusData : nullptr;
    const opus_int32 payloadBytes = (inputPayload != nullptr) ? static_cast<opus_int32>(bytes) : 0;

    const int decodedSamples = opus_decode(
        decoder_,
        inputPayload,
        payloadBytes,
        outputPcm,
        frameSize,
        decodeFec ? 1 : 0
    );

    if (decodedSamples < 0) {
        LOGE("Opus decode int16 failed: %d (%s)", decodedSamples, opus_strerror(decodedSamples));
    }
    return decodedSamples;
}

int OpusDecoderWrapper::resetState() {
    if (!decoder_) return OPUS_INVALID_STATE;
    return opus_decoder_ctl(decoder_, OPUS_RESET_STATE);
}

} // namespace audio
} // namespace roombeat
