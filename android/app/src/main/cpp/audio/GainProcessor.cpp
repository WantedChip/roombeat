#include "GainProcessor.h"
#include <cstring>

namespace roombeat {
namespace audio {

GainProcessor::GainProcessor(
    int32_t sampleRate,
    float rampDurationMs,
    RampCurve curve
)   : sampleRate_(sampleRate > 0 ? sampleRate : kDefaultSampleRate),
      rampDurationMs_(rampDurationMs > 0.0f ? rampDurationMs : kDefaultRampDurationMs),
      rampCurve_(curve) {
    updateRampFramesTotal();
    rampFrameCount_ = rampFramesTotal_;
}

float GainProcessor::dbToLinear(float db) {
    if (std::isnan(db) || db <= kMinVolumeDb) {
        return 0.0f;
    }
    if (std::abs(db) < 0.0001f) {
        return 1.0f;
    }
    if (db > kMaxVolumeDb) {
        db = kMaxVolumeDb;
    }
    return std::pow(10.0f, db / 20.0f);
}

float GainProcessor::linearToDb(float linear) {
    if (std::isnan(linear) || linear <= kMuteThresholdLinear) {
        return -std::numeric_limits<float>::infinity();
    }
    if (std::abs(linear - 1.0f) < 0.0001f) {
        return 0.0f;
    }
    return 20.0f * std::log10(linear);
}

void GainProcessor::setChannelVolumeDb(float volumeDb) {
    setChannelGain(dbToLinear(volumeDb));
}

void GainProcessor::setChannelGain(float linearGain) {
    if (std::isnan(linearGain) || linearGain <= kMuteThresholdLinear) {
        linearGain = 0.0f;
    } else if (linearGain > kMaxGainLinear) {
        linearGain = kMaxGainLinear;
    }
    targetChannelGain_.store(linearGain, std::memory_order_relaxed);
}

float GainProcessor::getChannelGain() const {
    return targetChannelGain_.load(std::memory_order_relaxed);
}

float GainProcessor::getChannelVolumeDb() const {
    return linearToDb(getChannelGain());
}

void GainProcessor::setMasterVolumeDb(float volumeDb) {
    setMasterGain(dbToLinear(volumeDb));
}

void GainProcessor::setMasterGain(float linearGain) {
    if (std::isnan(linearGain) || linearGain <= kMuteThresholdLinear) {
        linearGain = 0.0f;
    } else if (linearGain > kMaxGainLinear) {
        linearGain = kMaxGainLinear;
    }
    targetMasterGain_.store(linearGain, std::memory_order_relaxed);
}

float GainProcessor::getMasterGain() const {
    return targetMasterGain_.load(std::memory_order_relaxed);
}

float GainProcessor::getMasterVolumeDb() const {
    return linearToDb(getMasterGain());
}

void GainProcessor::setMuted(bool isMuted) {
    isMuted_.store(isMuted, std::memory_order_relaxed);
}

bool GainProcessor::isMuted() const {
    return isMuted_.load(std::memory_order_relaxed);
}

float GainProcessor::getTargetEffectiveGain() const {
    if (isMuted_.load(std::memory_order_relaxed)) {
        return 0.0f;
    }
    const float ch = targetChannelGain_.load(std::memory_order_relaxed);
    const float m = targetMasterGain_.load(std::memory_order_relaxed);
    return ch * m;
}

float GainProcessor::getCurrentGain() const {
    return currentGain_;
}

bool GainProcessor::isRamping() const {
    return rampFrameCount_ < rampFramesTotal_;
}

void GainProcessor::setSampleRate(int32_t sampleRate) {
    if (sampleRate > 0) {
        sampleRate_ = sampleRate;
        updateRampFramesTotal();
    }
}

void GainProcessor::setRampDurationMs(float rampDurationMs) {
    if (rampDurationMs > 0.0f) {
        rampDurationMs_ = rampDurationMs;
        updateRampFramesTotal();
    }
}

void GainProcessor::setRampCurve(RampCurve curve) {
    rampCurve_ = curve;
}

void GainProcessor::updateRampFramesTotal() {
    rampDurationFrames_ = std::max(1, static_cast<int32_t>(sampleRate_ * (rampDurationMs_ / 1000.0f)));
    rampFramesTotal_ = rampDurationFrames_;
}

void GainProcessor::resetGain(float channelGain, float masterGain, bool muted) {
    targetChannelGain_.store(channelGain, std::memory_order_relaxed);
    targetMasterGain_.store(masterGain, std::memory_order_relaxed);
    isMuted_.store(muted, std::memory_order_relaxed);

    const float target = muted ? 0.0f : (channelGain * masterGain);
    activeTargetGain_ = target;
    currentGain_ = target;
    startGain_ = target;
    rampFrameCount_ = rampFramesTotal_;
}

void GainProcessor::process(float* audioBuffer, int32_t numFrames, int32_t channelCount) {
    if (audioBuffer == nullptr || numFrames <= 0 || channelCount <= 0) {
        return;
    }

    const float newTarget = getTargetEffectiveGain();

    // Trigger ramp if target effective gain has changed
    if (std::abs(newTarget - activeTargetGain_) > 1e-6f) {
        activeTargetGain_ = newTarget;
        startGain_ = currentGain_;
        rampFramesTotal_ = (rampDurationFrames_ > 0) ? rampDurationFrames_ : 1;
        rampFrameCount_ = 0;
    }

    if (rampFrameCount_ < rampFramesTotal_) {
        const float totalFrames = static_cast<float>(rampFramesTotal_);
        const float gainDiff = activeTargetGain_ - startGain_;

        for (int32_t f = 0; f < numFrames; ++f) {
            if (rampFrameCount_ < rampFramesTotal_) {
                ++rampFrameCount_;
                const float progress = static_cast<float>(rampFrameCount_) / totalFrames;
                if (rampCurve_ == RampCurve::Cosine) {
                    constexpr float kPi = 3.14159265358979323846f;
                    const float t = 0.5f * (1.0f - std::cos(progress * kPi));
                    currentGain_ = startGain_ + (gainDiff * t);
                } else {
                    currentGain_ = startGain_ + (gainDiff * progress);
                }

                if (rampFrameCount_ >= rampFramesTotal_) {
                    currentGain_ = activeTargetGain_;
                }
            }

            const int32_t baseIdx = f * channelCount;
            for (int32_t c = 0; c < channelCount; ++c) {
                audioBuffer[baseIdx + c] *= currentGain_;
            }
        }
    } else {
        // Steady state (no ramping in progress)
        if (currentGain_ <= 1e-5f) {
            std::memset(audioBuffer, 0, numFrames * channelCount * sizeof(float));
        } else if (std::abs(currentGain_ - 1.0f) > 1e-5f) {
            const int32_t totalSamples = numFrames * channelCount;
            for (int32_t i = 0; i < totalSamples; ++i) {
                audioBuffer[i] *= currentGain_;
            }
        }
        // If currentGain_ is unity (1.0f), samples are unmodified
    }
}

} // namespace audio
} // namespace roombeat
