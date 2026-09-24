#ifndef ROOMBEAT_OPUS_DECODER_WRAPPER_H
#define ROOMBEAT_OPUS_DECODER_WRAPPER_H

#include <cstdint>
#include <cstddef>
#include "opus.h"

namespace roombeat {
namespace audio {

/**
 * RAII C++ wrapper around libopus OpusDecoder.
 * Configured for 48kHz, stereo, 20ms frame size (960 samples/channel = 1920 interleaved samples).
 * Supports Packet Loss Concealment (PLC) when opusData is nullptr or bytes is 0.
 */
class OpusDecoderWrapper {
public:
    static constexpr int DEFAULT_SAMPLE_RATE = 48000;
    static constexpr int DEFAULT_CHANNELS = 2;
    static constexpr int DEFAULT_FRAME_SIZE = 960;           // 20ms at 48kHz (per channel)
    static constexpr int DEFAULT_INTERLEAVED_SAMPLES = 1920; // 960 * 2

    explicit OpusDecoderWrapper(int sampleRate = DEFAULT_SAMPLE_RATE,
                                int channels = DEFAULT_CHANNELS);
    ~OpusDecoderWrapper();

    // Disable copy semantics to prevent double destruction
    OpusDecoderWrapper(const OpusDecoderWrapper&) = delete;
    OpusDecoderWrapper& operator=(const OpusDecoderWrapper&) = delete;

    // Enable move semantics
    OpusDecoderWrapper(OpusDecoderWrapper&& other) noexcept;
    OpusDecoderWrapper& operator=(OpusDecoderWrapper&& other) noexcept;

    bool isValid() const noexcept { return decoder_ != nullptr; }

    /**
     * Decode Opus packet to interleaved Float32 PCM audio frames.
     * Supports Packet Loss Concealment (PLC) if opusData == nullptr or bytes == 0.
     * @param opusData Compressed Opus payload, or nullptr to trigger PLC
     * @param bytes Payload size in bytes, or 0 to trigger PLC
     * @param outputPcm Destination buffer (minimum capacity: frameSize * channels floats)
     * @param frameSize Number of samples per channel of available space (e.g. 960 for 20ms)
     * @param decodeFec True to request in-band Forward Error Correction decoding
     * @return Number of decoded samples per channel (e.g. 960), or negative Opus error code
     */
    int decode(const uint8_t* opusData, int bytes, float* outputPcm, int frameSize = DEFAULT_FRAME_SIZE, bool decodeFec = false);

    /**
     * Decode Opus packet to interleaved PCM16 (int16_t) audio frames.
     * Supports Packet Loss Concealment (PLC) if opusData == nullptr or bytes == 0.
     * @param opusData Compressed Opus payload, or nullptr to trigger PLC
     * @param bytes Payload size in bytes, or 0 to trigger PLC
     * @param outputPcm Destination buffer (minimum capacity: frameSize * channels int16_t)
     * @param frameSize Number of samples per channel of available space (e.g. 960 for 20ms)
     * @param decodeFec True to request in-band Forward Error Correction decoding
     * @return Number of decoded samples per channel (e.g. 960), or negative Opus error code
     */
    int decode(const uint8_t* opusData, int bytes, int16_t* outputPcm, int frameSize = DEFAULT_FRAME_SIZE, bool decodeFec = false);

    int resetState();

    int getSampleRate() const noexcept { return sampleRate_; }
    int getChannels() const noexcept { return channels_; }

private:
    OpusDecoder* decoder_ = nullptr;
    int sampleRate_ = DEFAULT_SAMPLE_RATE;
    int channels_ = DEFAULT_CHANNELS;
};

} // namespace audio
} // namespace roombeat

#endif // ROOMBEAT_OPUS_DECODER_WRAPPER_H
