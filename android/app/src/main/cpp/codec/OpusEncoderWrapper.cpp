#include "OpusEncoderWrapper.h"
#include <android/log.h>
#include <utility>

#define LOG_TAG "OpusEncoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace roombeat {
namespace audio {

OpusEncoderWrapper::OpusEncoderWrapper(int sampleRate,
                                       int channels,
                                       int bitrate,
                                       int complexity)
    : sampleRate_(sampleRate), channels_(channels) {
    int err = OPUS_OK;
    encoder_ = opus_encoder_create(sampleRate_, channels_, OPUS_APPLICATION_AUDIO, &err);
    if (err != OPUS_OK || !encoder_) {
        LOGE("Failed to create OpusEncoder: error %d (%s)", err, opus_strerror(err));
        encoder_ = nullptr;
        return;
    }

    // Configure audio parameters
    opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder_, OPUS_SET_VBR(1));
    opus_encoder_ctl(encoder_, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(encoder_, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(encoder_, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder_, OPUS_SET_PACKET_LOSS_PERC(5));

    LOGI("OpusEncoder created: %d Hz, %d channels, %d bps, complexity %d",
         sampleRate_, channels_, bitrate, complexity);
}

OpusEncoderWrapper::~OpusEncoderWrapper() {
    if (encoder_) {
        opus_encoder_destroy(encoder_);
        encoder_ = nullptr;
    }
}

OpusEncoderWrapper::OpusEncoderWrapper(OpusEncoderWrapper&& other) noexcept
    : encoder_(other.encoder_),
      sampleRate_(other.sampleRate_),
      channels_(other.channels_) {
    other.encoder_ = nullptr;
}

OpusEncoderWrapper& OpusEncoderWrapper::operator=(OpusEncoderWrapper&& other) noexcept {
    if (this != &other) {
        if (encoder_) {
            opus_encoder_destroy(encoder_);
        }
        encoder_ = other.encoder_;
        sampleRate_ = other.sampleRate_;
        channels_ = other.channels_;
        other.encoder_ = nullptr;
    }
    return *this;
}

int OpusEncoderWrapper::encode(const float* pcmInput, int frameSize, uint8_t* outputBuffer, int maxOutputBytes) {
    if (!encoder_) {
        LOGE("OpusEncoderWrapper::encode(float): encoder is not initialized");
        return OPUS_INVALID_STATE;
    }
    if (!pcmInput || !outputBuffer || frameSize <= 0 || maxOutputBytes <= 0) {
        LOGE("OpusEncoderWrapper::encode(float): invalid arguments");
        return OPUS_BAD_ARG;
    }

    const int encodedBytes = opus_encode_float(encoder_, pcmInput, frameSize, outputBuffer, maxOutputBytes);
    if (encodedBytes < 0) {
        LOGE("Opus encode float failed: %d (%s)", encodedBytes, opus_strerror(encodedBytes));
    }
    return encodedBytes;
}

int OpusEncoderWrapper::encode(const int16_t* pcmInput, int frameSize, uint8_t* outputBuffer, int maxOutputBytes) {
    if (!encoder_) {
        LOGE("OpusEncoderWrapper::encode(int16): encoder is not initialized");
        return OPUS_INVALID_STATE;
    }
    if (!pcmInput || !outputBuffer || frameSize <= 0 || maxOutputBytes <= 0) {
        LOGE("OpusEncoderWrapper::encode(int16): invalid arguments");
        return OPUS_BAD_ARG;
    }

    const int encodedBytes = opus_encode(encoder_, pcmInput, frameSize, outputBuffer, maxOutputBytes);
    if (encodedBytes < 0) {
        LOGE("Opus encode int16 failed: %d (%s)", encodedBytes, opus_strerror(encodedBytes));
    }
    return encodedBytes;
}

int OpusEncoderWrapper::resetState() {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_RESET_STATE);
}

int OpusEncoderWrapper::setBitrate(int bitrate) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_BITRATE(bitrate));
}

int OpusEncoderWrapper::getBitrate() const {
    if (!encoder_) return OPUS_INVALID_STATE;
    opus_int32 bitrate = 0;
    int res = opus_encoder_ctl(encoder_, OPUS_GET_BITRATE(&bitrate));
    return (res == OPUS_OK) ? static_cast<int>(bitrate) : res;
}

int OpusEncoderWrapper::setComplexity(int complexity) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_COMPLEXITY(complexity));
}

int OpusEncoderWrapper::getComplexity() const {
    if (!encoder_) return OPUS_INVALID_STATE;
    opus_int32 comp = 0;
    int res = opus_encoder_ctl(encoder_, OPUS_GET_COMPLEXITY(&comp));
    return (res == OPUS_OK) ? static_cast<int>(comp) : res;
}

int OpusEncoderWrapper::setVbr(bool enabled) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_VBR(enabled ? 1 : 0));
}

int OpusEncoderWrapper::setSignalType(int signal) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_SIGNAL(signal));
}

int OpusEncoderWrapper::setInbandFec(bool enabled) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_INBAND_FEC(enabled ? 1 : 0));
}

int OpusEncoderWrapper::setPacketLossPercentage(int percentage) {
    if (!encoder_) return OPUS_INVALID_STATE;
    return opus_encoder_ctl(encoder_, OPUS_SET_PACKET_LOSS_PERC(percentage));
}

} // namespace audio
} // namespace roombeat
