#ifndef ROOMBEAT_AUDIO_JITTER_BUFFER_H
#define ROOMBEAT_AUDIO_JITTER_BUFFER_H

#include <atomic>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <vector>
#include <functional>
#include <mutex>
#include "RingBuffer.h"
#include "OboeAudioPlayer.h"
#include "codec/OpusDecoderWrapper.h"

namespace roombeat {
namespace buffer {

constexpr int32_t kJitterSampleRate = 48000;
constexpr int32_t kJitterChannelCount = 2; // Stereo
constexpr int32_t kFrameDurationMs = 20;
constexpr int32_t kSamplesPerChannel = 960; // 48000 * 0.02
constexpr int32_t kInterleavedSamples = kSamplesPerChannel * kJitterChannelCount; // 1920 floats
constexpr size_t kMaxOpusPayloadBytes = 1500;
constexpr size_t kDefaultCapacityFrames = 64; // ~1280ms
constexpr int32_t kDefaultTargetDepthMs = 120; // 6 frames of 20ms
constexpr int32_t kMaxPlcConsecutiveFrames = 3; // PLC tolerance before fade-out
constexpr int64_t kDefaultLateToleranceUs = 40000; // 40ms late tolerance
constexpr int64_t kEarlyToleranceUs = 5000; // 5ms early lead tolerance

/**
 * State of 20ms crossfade transitions.
 */
enum class FadeState {
    IDLE_PLAYING, // Full gain (1.0)
    MUTED         // Silence (0.0), next frame will fade in
};

/**
 * High-level state of the jitter buffer.
 */
enum class BufferState {
    BUFFERING, // Accumulating frames to absorb initial network arrival jitter
    PLAYING    // Actively rendering scheduled / ordered audio frames
};

/**
 * Telemetry and diagnostics counters for the jitter buffer.
 */
struct JitterBufferStats {
    int64_t totalPacketsReceived = 0;
    int64_t packetsPlayed = 0;
    int64_t plcCount = 0;
    int64_t latePacketsDropped = 0;
    int64_t duplicatePacketsDropped = 0;
    int64_t underrunCount = 0;
    int32_t currentQueuedFrames = 0;
    int32_t targetDepthMs = 0;
    int32_t consecutiveLostFrames = 0;
};

/**
 * Internal slot representation holding one 20ms audio frame.
 */
struct FrameSlot {
    std::atomic<bool> occupied{false};
    uint64_t sequenceNumber{0};
    int64_t presentationTimeUs{0};
    size_t opusDataSize{0};
    uint8_t opusData[kMaxOpusPayloadBytes];
    bool isPreDecoded{false};
    float decodedPcm[kInterleavedSamples];
};

/**
 * Thread-safe, lockless circular jitter buffer for RoomBeat.
 *
 * Implements:
 * - Out-of-order packet reordering by sequence number.
 * - Presentation clock scheduling (compares timestamps against monotonic playback clock).
 * - Jitter absorption up to configurable target depth (default 120ms = 6 frames).
 * - Automatic Opus Packet Loss Concealment (PLC) for missing sequence numbers.
 * - Raised cosine 20ms smooth fade-out when packet drop exceeds PLC tolerance.
 * - Raised cosine 20ms smooth fade-in when packets resume after underflow or muting.
 * - AudioSource provider interface to feed directly into Oboe stream.
 */
class AudioJitterBuffer : public AudioSource {
public:
    using ClockFunction = std::function<int64_t()>;

    explicit AudioJitterBuffer(
        int32_t targetDepthMs = kDefaultTargetDepthMs,
        size_t capacityFrames = kDefaultCapacityFrames,
        std::shared_ptr<roombeat::audio::OpusDecoderWrapper> decoder = nullptr
    );

    ~AudioJitterBuffer() override = default;

    AudioJitterBuffer(const AudioJitterBuffer&) = delete;
    AudioJitterBuffer& operator=(const AudioJitterBuffer&) = delete;

    /**
     * Ingest an Opus-compressed packet from the network thread.
     * Returns true if queued, false if dropped (duplicate, late, or buffer full).
     */
    bool pushPacket(
        uint64_t sequenceNumber,
        int64_t presentationTimeUs,
        const uint8_t* payload,
        size_t size
    );

    /**
     * Ingest pre-decoded stereo Float32 audio samples (1920 floats = 960 frames).
     */
    bool pushDecodedFrame(
        uint64_t sequenceNumber,
        int64_t presentationTimeUs,
        const float* pcmData,
        int32_t numFrames
    );

    /**
     * Renders up to numFrames of interleaved stereo Float32 audio into output.
     * Implements AudioSource interface for Oboe stream feeding.
     */
    int32_t renderAudio(float* output, int32_t numFrames) override;

    /**
     * Convenience method to pull audio frames directly.
     */
    int32_t pullFrames(float* output, int32_t numFrames) {
        return renderAudio(output, numFrames);
    }

    /**
     * Jitter buffer configuration and telemetry.
     */
    void setTargetDepthMs(int32_t depthMs);
    int32_t getTargetDepthMs() const;
    int32_t getQueuedFrames() const;
    JitterBufferStats getStats() const;
    void reset();

    /**
     * Set a custom clock function for deterministic testing.
     */
    void setClockFunction(ClockFunction clockFunc);

    /**
     * Get current monotonic time in microseconds.
     */
    int64_t getCurrentTimeUs() const;

    /**
     * Raised cosine 20ms fade-out and fade-in helpers.
     */
    static void applyFadeOut(float* frame, int32_t numFrames);
    static void applyFadeIn(float* frame, int32_t numFrames);

private:
    bool fetchNext20msFrame(float* outFrame);
    uint64_t findLowestBufferedSeq() const;

    int32_t targetDepthMs_;
    size_t capacityFrames_;
    std::shared_ptr<roombeat::audio::OpusDecoderWrapper> decoder_;
    bool ownsDecoder_{false};

    ClockFunction clockFunc_;

    std::vector<FrameSlot> slots_;
    mutable std::mutex bufferMutex_;

    std::atomic<int32_t> queuedFrames_{0};
    std::atomic<bool> playbackStarted_{false};
    BufferState state_{BufferState::BUFFERING};
    FadeState fadeState_{FadeState::MUTED};

    uint64_t nextPlaySeq_{0};
    int32_t consecutiveLostFrames_{0};

    // Active 20ms frame buffer for sub-frame renders (e.g. AAudio burst size of 192 frames)
    std::vector<float> activeFrameBuffer_;
    int32_t activeFrameOffset_{0};
    int32_t activeFrameRemaining_{0};

    // Telemetry counters
    std::atomic<int64_t> totalPacketsReceived_{0};
    std::atomic<int64_t> packetsPlayed_{0};
    std::atomic<int64_t> plcCount_{0};
    std::atomic<int64_t> latePacketsDropped_{0};
    std::atomic<int64_t> duplicatePacketsDropped_{0};
    std::atomic<int64_t> underrunCount_{0};
};

} // namespace buffer
} // namespace roombeat

#endif // ROOMBEAT_AUDIO_JITTER_BUFFER_H
