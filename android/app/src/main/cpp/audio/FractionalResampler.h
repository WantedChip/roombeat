#ifndef ROOMBEAT_FRACTIONAL_RESAMPLER_H
#define ROOMBEAT_FRACTIONAL_RESAMPLER_H

#include <atomic>
#include <cstdint>
#include <cstddef>
#include <functional>
#include <vector>
#include <algorithm>

namespace roombeat {
namespace audio {

/**
 * Supported fractional interpolation algorithms.
 */
enum class InterpolationMethod {
    Linear,
    CubicCatmullRom
};

/**
 * Pitch-neutral fractional sample resampler supporting micro-speed modulation
 * in parts-per-million (PPM) for sub-millisecond multi-device clock drift correction.
 *
 * Real-time audio safety:
 * - process() and renderAudio() are wait-free, lock-free, and perform zero heap allocations.
 * - Dynamic parameter ramping smoothly adjusts playback speed over a configurable window
 *   (default 50ms = 2400 frames at 48kHz) to eliminate audible clicks, pops, and phase glitches.
 * - Catmull-Rom cubic spline interpolation ensures C^1 continuity and negligible distortion
 *   (<0.01% CPU per core) for speed modulations between -2000 ppm and +2000 ppm (+/-0.2%).
 */
class FractionalResampler {
public:
    static constexpr int32_t kDefaultSampleRate = 48000;
    static constexpr int32_t kDefaultChannels = 2; // Stereo
    static constexpr int32_t kMaxChannels = 8;
    static constexpr int32_t kMinPpm = -2000; // -0.2% max adjustment
    static constexpr int32_t kMaxPpm = 2000;  // +0.2% max adjustment
    static constexpr float kDefaultRampDurationMs = 50.0f; // 50ms click-free ramp
    static constexpr size_t kDefaultFifoCapacityFrames = 8192; // ~170ms at 48kHz

    explicit FractionalResampler(
        int32_t channels = kDefaultChannels,
        int32_t sampleRate = kDefaultSampleRate,
        InterpolationMethod method = InterpolationMethod::CubicCatmullRom,
        size_t fifoCapacityFrames = kDefaultFifoCapacityFrames
    );

    ~FractionalResampler() = default;

    // Disallow copy/move to protect audio thread state
    FractionalResampler(const FractionalResampler&) = delete;
    FractionalResampler& operator=(const FractionalResampler&) = delete;

    /**
     * Set target micro-speed adjustment in parts-per-million (PPM).
     * Example: +500 ppm = +0.05% speed (ratio 1.0005), -500 ppm = -0.05% speed (ratio 0.9995).
     * Clamped to [kMinPpm, kMaxPpm].
     */
    void setSpeedPpm(int32_t ppm);
    int32_t getSpeedPpm() const;

    /**
     * Set target playback speed ratio directly.
     * Example: 1.0005 = +500 ppm, 0.9995 = -500 ppm.
     */
    void setSpeedRatio(double ratio);
    double getSpeedRatio() const;

    /**
     * Returns the current instantaneous playback speed ratio on the audio rendering thread
     * (reflects ramping progress).
     */
    double getEffectiveSpeedRatio() const;

    /**
     * Returns true if parameter ramping is currently in progress.
     */
    bool isRamping() const;

    /**
     * Immediately resets internal FIFO, history, phase accumulator, and speed ratio without ramping.
     */
    void reset();

    /**
     * Configuration parameters.
     */
    void setSampleRate(int32_t sampleRate);
    int32_t getSampleRate() const;

    int32_t getChannels() const;

    void setInterpolationMethod(InterpolationMethod method);
    InterpolationMethod getInterpolationMethod() const;

    void setRampDurationMs(float rampDurationMs);
    float getRampDurationMs() const;

    /**
     * Pushes raw interleaved Float32 input frames into the internal FIFO.
     * Returns the number of frames actually accepted.
     */
    int32_t pushInput(const float* inBuffer, int32_t numFrames);

    /**
     * Pulls resampled frames from the internal FIFO into outBuffer.
     * Returns the number of resampled output frames written.
     */
    int32_t pullOutput(float* outBuffer, int32_t numFrames);

    /**
     * Streaming audio pull interface.
     * Pulls numFrames of resampled audio, invoking inputProvider whenever more input is needed.
     * Returns the actual number of output frames rendered.
     */
    using InputProvider = std::function<int32_t(float* buffer, int32_t requestedFrames)>;
    int32_t renderAudio(
        float* outBuffer,
        int32_t numFrames,
        const InputProvider& inputProvider
    );

    /**
     * Direct block-level resampling.
     * Consumes frames from inBuffer and writes up to outFrames into outBuffer.
     * Updates inFramesConsumed with the actual number of input frames processed.
     * Returns the number of output frames generated.
     */
    int32_t process(
        const float* inBuffer,
        int32_t inFrames,
        int32_t& inFramesConsumed,
        float* outBuffer,
        int32_t outFrames
    );

    /**
     * Returns the number of unconsumed input frames currently queued in the FIFO.
     */
    int32_t getQueuedInputFrames() const;

    /**
     * Mathematical utility: converts PPM to playback speed ratio.
     */
    static constexpr double ppmToRatio(int32_t ppm) {
        return 1.0 + (static_cast<double>(ppm) / 1000000.0);
    }

    /**
     * Mathematical utility: converts playback speed ratio to PPM.
     */
    static constexpr int32_t ratioToPpm(double ratio) {
        return static_cast<int32_t>(std::round((ratio - 1.0) * 1000000.0));
    }

private:
    void updateRampFramesTotal();
    float getSample(size_t index, int32_t channel) const;

    int32_t channels_;
    std::atomic<int32_t> sampleRate_{kDefaultSampleRate};
    InterpolationMethod method_{InterpolationMethod::CubicCatmullRom};
    float rampDurationMs_{kDefaultRampDurationMs};
    int32_t rampFramesTotal_{2400}; // 50ms at 48kHz

    // Targets updated from control threads
    std::atomic<int32_t> targetPpm_{0};
    std::atomic<double> targetRatio_{1.0};
    std::atomic<bool> isRamping_{false};

    // State on the audio rendering thread
    double currentRatio_{1.0};
    double activeTargetRatio_{1.0};
    double rampStep_{0.0};
    int32_t rampFramesRemaining_{0};

    // Fractional phase accumulator: phi in [0.0, 1.0)
    double fractionalPhase_{0.0};

    // History of previous input frame for Catmull-Rom (y_{-1})
    std::vector<float> history_;
    bool hasHistory_{false};

    // Pre-allocated FIFO circular buffer
    size_t fifoCapacityFrames_;
    std::vector<float> fifo_;
    size_t fifoHead_{0};
    size_t fifoSize_{0};

    // Scratch buffer for InputProvider pulls
    std::vector<float> providerScratch_;
};

} // namespace audio
} // namespace roombeat

#endif // ROOMBEAT_FRACTIONAL_RESAMPLER_H
