#ifndef ROOMBEAT_GAIN_PROCESSOR_H
#define ROOMBEAT_GAIN_PROCESSOR_H

#include <atomic>
#include <cmath>
#include <cstdint>
#include <algorithm>
#include <limits>

namespace roombeat {
namespace audio {

/**
 * Interpolation curve used for smooth gain transitions.
 */
enum class RampCurve {
    Linear,
    Cosine
};

/**
 * Click-free digital gain processor supporting per-channel gain, master gain,
 * and mute control with smooth 50ms ramp interpolation (2400 frames at 48kHz).
 *
 * Real-time audio safety:
 * - process() is wait-free, lock-free, and performs zero heap allocations.
 * - Volume adjustments via setChannelVolumeDb / setMasterVolumeDb / setMuted
 *   update atomic targets and trigger seamless sample-by-sample amplitude ramps.
 */
class GainProcessor {
public:
    static constexpr float kMinVolumeDb = -60.0f;
    static constexpr float kMaxVolumeDb = 6.0206f; // +6 dB (~2.0 linear gain)
    static constexpr float kUnityGainLinear = 1.0f;
    static constexpr float kMuteGainLinear = 0.0f;
    static constexpr float kMaxGainLinear = 2.0f;
    static constexpr float kMuteThresholdLinear = 0.0001f;
    static constexpr float kDefaultRampDurationMs = 50.0f;
    static constexpr int32_t kDefaultSampleRate = 48000;

    explicit GainProcessor(
        int32_t sampleRate = kDefaultSampleRate,
        float rampDurationMs = kDefaultRampDurationMs,
        RampCurve curve = RampCurve::Linear
    );
    ~GainProcessor() = default;

    // Disallow copy/move to prevent multi-threaded state aliasing
    GainProcessor(const GainProcessor&) = delete;
    GainProcessor& operator=(const GainProcessor&) = delete;

    /**
     * Converts a decibel value to linear gain factor.
     * Values <= -60.0 dB snap to 0.0 (mute).
     * 0.0 dB returns 1.0 (unity).
     */
    static float dbToLinear(float db);

    /**
     * Converts a linear gain factor to decibels.
     * Values <= 0.0001f return -infinity.
     * 1.0 returns 0.0 dB.
     */
    static float linearToDb(float linear);

    // Channel volume / gain
    void setChannelVolumeDb(float volumeDb);
    void setChannelGain(float linearGain);
    float getChannelGain() const;
    float getChannelVolumeDb() const;

    // Master volume / gain
    void setMasterVolumeDb(float volumeDb);
    void setMasterGain(float linearGain);
    float getMasterGain() const;
    float getMasterVolumeDb() const;

    // Mute control
    void setMuted(bool isMuted);
    bool isMuted() const;

    // Query effective gains
    float getTargetEffectiveGain() const;
    float getCurrentGain() const;
    bool isRamping() const;

    // Configuration
    void setSampleRate(int32_t sampleRate);
    int32_t getSampleRate() const { return sampleRate_; }
    void setRampDurationMs(float rampDurationMs);
    float getRampDurationMs() const { return rampDurationMs_; }
    void setRampCurve(RampCurve curve);
    RampCurve getRampCurve() const { return rampCurve_; }
    int32_t getRampDurationFrames() const { return rampDurationFrames_; }

    /**
     * Resets gain state immediately without ramping (useful on stream start/reset).
     */
    void resetGain(float channelGain = 1.0f, float masterGain = 1.0f, bool muted = false);

    /**
     * In-place audio processing: multiplies samples by the interpolated effective gain.
     * Real-time safe: no locks, no allocations.
     *
     * @param audioBuffer Interleaved Float32 samples.
     * @param numFrames Number of frames in the buffer.
     * @param channelCount Number of interleaved channels per frame (default 2 for stereo).
     */
    void process(float* audioBuffer, int32_t numFrames, int32_t channelCount = 2);

private:
    void updateRampFramesTotal();

    // Atomic targets updated from control threads
    std::atomic<float> targetChannelGain_{1.0f};
    std::atomic<float> targetMasterGain_{1.0f};
    std::atomic<bool> isMuted_{false};

    // Configuration
    int32_t sampleRate_{kDefaultSampleRate};
    float rampDurationMs_{kDefaultRampDurationMs};
    int32_t rampDurationFrames_{2400}; // 50ms at 48kHz
    RampCurve rampCurve_{RampCurve::Linear};

    // State on the audio rendering thread
    float currentGain_{1.0f};
    float activeTargetGain_{1.0f};
    float startGain_{1.0f};
    int32_t rampFrameCount_{2400}; // starts completed
    int32_t rampFramesTotal_{2400};
};

} // namespace audio
} // namespace roombeat

#endif // ROOMBEAT_GAIN_PROCESSOR_H
