#ifndef ROOMBEAT_OPUS_ENCODER_WRAPPER_H
#define ROOMBEAT_OPUS_ENCODER_WRAPPER_H

#include <cstdint>
#include <cstddef>
#include "opus.h"

namespace roombeat {
namespace audio {

/**
 * RAII C++ wrapper around libopus OpusEncoder.
 * Configured for 48kHz, stereo, 20ms frame size (960 samples/channel = 1920 interleaved samples).
 * Default bitrate: 128 kbps, VBR enabled, Music signal tuning, Complexity 6 (balanced for mobile).
 */
class OpusEncoderWrapper {
public:
    static constexpr int DEFAULT_SAMPLE_RATE = 48000;
    static constexpr int DEFAULT_CHANNELS = 2;
    static constexpr int DEFAULT_FRAME_SIZE = 960;           // 20ms at 48kHz (per channel)
    static constexpr int DEFAULT_INTERLEAVED_SAMPLES = 1920; // 960 * 2
    static constexpr int DEFAULT_BITRATE = 128000;          // 128 kbps
    static constexpr int DEFAULT_COMPLEXITY = 6;            // Mobile balanced (5-8)
    static constexpr int MAX_PACKET_SIZE = 4000;            // Recommended Opus buffer capacity

    explicit OpusEncoderWrapper(int sampleRate = DEFAULT_SAMPLE_RATE,
                                int channels = DEFAULT_CHANNELS,
                                int bitrate = DEFAULT_BITRATE,
                                int complexity = DEFAULT_COMPLEXITY);
    ~OpusEncoderWrapper();

    // Disable copy semantics to prevent double destruction
    OpusEncoderWrapper(const OpusEncoderWrapper&) = delete;
    OpusEncoderWrapper& operator=(const OpusEncoderWrapper&) = delete;

    // Enable move semantics
    OpusEncoderWrapper(OpusEncoderWrapper&& other) noexcept;
    OpusEncoderWrapper& operator=(OpusEncoderWrapper&& other) noexcept;

    bool isValid() const noexcept { return encoder_ != nullptr; }

    /**
     * Encode interleaved Float32 PCM audio frames to Opus packet.
     * @param pcmInput Interleaved float PCM input (frameSize * channels elements)
     * @param frameSize Number of samples per channel (e.g. 960 for 20ms)
     * @param outputBuffer Destination buffer for compressed Opus packet
     * @param maxOutputBytes Capacity of output buffer
     * @return Number of compressed bytes written to outputBuffer, or negative Opus error code
     */
    int encode(const float* pcmInput, int frameSize, uint8_t* outputBuffer, int maxOutputBytes);

    /**
     * Encode interleaved PCM16 (int16_t) audio frames to Opus packet.
     * @param pcmInput Interleaved int16_t PCM input (frameSize * channels elements)
     * @param frameSize Number of samples per channel (e.g. 960 for 20ms)
     * @param outputBuffer Destination buffer for compressed Opus packet
     * @param maxOutputBytes Capacity of output buffer
     * @return Number of compressed bytes written to outputBuffer, or negative Opus error code
     */
    int encode(const int16_t* pcmInput, int frameSize, uint8_t* outputBuffer, int maxOutputBytes);

    int resetState();
    int setBitrate(int bitrate);
    int getBitrate() const;
    int setComplexity(int complexity);
    int getComplexity() const;
    int setVbr(bool enabled);
    int setSignalType(int signal);
    int setInbandFec(bool enabled);
    int setPacketLossPercentage(int percentage);

    int getSampleRate() const noexcept { return sampleRate_; }
    int getChannels() const noexcept { return channels_; }

private:
    OpusEncoder* encoder_ = nullptr;
    int sampleRate_ = DEFAULT_SAMPLE_RATE;
    int channels_ = DEFAULT_CHANNELS;
};

} // namespace audio
} // namespace roombeat

#endif // ROOMBEAT_OPUS_ENCODER_WRAPPER_H
