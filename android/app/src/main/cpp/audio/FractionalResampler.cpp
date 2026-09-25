#include "FractionalResampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

namespace roombeat {
namespace audio {

namespace {

inline float interpolateCatmullRom(float y_prev, float y0, float y1, float y_next, float alpha) {
    const float c0 = y0;
    const float c1 = 0.5f * (y1 - y_prev);
    const float c2 = y_prev - 2.5f * y0 + 2.0f * y1 - 0.5f * y_next;
    const float c3 = -0.5f * y_prev + 1.5f * y0 - 1.5f * y1 + 0.5f * y_next;
    return ((c3 * alpha + c2) * alpha + c1) * alpha + c0;
}

inline float interpolateLinear(float y0, float y1, float alpha) {
    return y0 + alpha * (y1 - y0);
}

} // anonymous namespace

FractionalResampler::FractionalResampler(
    int32_t channels,
    int32_t sampleRate,
    InterpolationMethod method,
    size_t fifoCapacityFrames
) : channels_(std::clamp(channels, 1, kMaxChannels)),
    sampleRate_(sampleRate > 0 ? sampleRate : kDefaultSampleRate),
    method_(method),
    rampDurationMs_(kDefaultRampDurationMs),
    history_(channels_, 0.0f),
    fifoCapacityFrames_(fifoCapacityFrames > 0 ? fifoCapacityFrames : kDefaultFifoCapacityFrames),
    fifo_(fifoCapacityFrames_ * channels_, 0.0f),
    providerScratch_(1024 * channels_, 0.0f) {
    updateRampFramesTotal();
    reset();
}

void FractionalResampler::updateRampFramesTotal() {
    const int32_t sr = sampleRate_.load(std::memory_order_relaxed);
    rampFramesTotal_ = std::max(1, static_cast<int32_t>(sr * (rampDurationMs_ / 1000.0f)));
}

void FractionalResampler::reset() {
    targetPpm_.store(0, std::memory_order_relaxed);
    targetRatio_.store(1.0, std::memory_order_relaxed);
    isRamping_.store(false, std::memory_order_relaxed);

    currentRatio_ = 1.0;
    activeTargetRatio_ = 1.0;
    rampStep_ = 0.0;
    rampFramesRemaining_ = 0;
    fractionalPhase_ = 0.0;

    std::fill(history_.begin(), history_.end(), 0.0f);
    hasHistory_ = false;

    fifoHead_ = 0;
    fifoSize_ = 0;
    std::fill(fifo_.begin(), fifo_.end(), 0.0f);
}

void FractionalResampler::setSpeedPpm(int32_t ppm) {
    const int32_t clampedPpm = std::clamp(ppm, kMinPpm, kMaxPpm);
    targetPpm_.store(clampedPpm, std::memory_order_relaxed);
    targetRatio_.store(ppmToRatio(clampedPpm), std::memory_order_relaxed);
}

int32_t FractionalResampler::getSpeedPpm() const {
    return targetPpm_.load(std::memory_order_relaxed);
}

void FractionalResampler::setSpeedRatio(double ratio) {
    const int32_t ppm = ratioToPpm(ratio);
    setSpeedPpm(ppm);
}

double FractionalResampler::getSpeedRatio() const {
    return targetRatio_.load(std::memory_order_relaxed);
}

double FractionalResampler::getEffectiveSpeedRatio() const {
    return currentRatio_;
}

bool FractionalResampler::isRamping() const {
    return isRamping_.load(std::memory_order_relaxed);
}

void FractionalResampler::setSampleRate(int32_t sampleRate) {
    if (sampleRate > 0) {
        sampleRate_.store(sampleRate, std::memory_order_relaxed);
        updateRampFramesTotal();
    }
}

int32_t FractionalResampler::getSampleRate() const {
    return sampleRate_.load(std::memory_order_relaxed);
}

int32_t FractionalResampler::getChannels() const {
    return channels_;
}

void FractionalResampler::setInterpolationMethod(InterpolationMethod method) {
    method_ = method;
}

InterpolationMethod FractionalResampler::getInterpolationMethod() const {
    return method_;
}

void FractionalResampler::setRampDurationMs(float rampDurationMs) {
    rampDurationMs_ = std::max(1.0f, rampDurationMs);
    updateRampFramesTotal();
}

float FractionalResampler::getRampDurationMs() const {
    return rampDurationMs_;
}

int32_t FractionalResampler::getQueuedInputFrames() const {
    return static_cast<int32_t>(fifoSize_);
}

float FractionalResampler::getSample(size_t index, int32_t channel) const {
    const size_t framePos = (fifoHead_ + index) % fifoCapacityFrames_;
    return fifo_[framePos * channels_ + channel];
}

int32_t FractionalResampler::pushInput(const float* inBuffer, int32_t numFrames) {
    if (!inBuffer || numFrames <= 0) return 0;

    const size_t freeFrames = fifoCapacityFrames_ - fifoSize_;
    const size_t toWrite = std::min(static_cast<size_t>(numFrames), freeFrames);
    if (toWrite == 0) return 0;

    const size_t writeHead = (fifoHead_ + fifoSize_) % fifoCapacityFrames_;
    const size_t firstChunk = std::min(toWrite, fifoCapacityFrames_ - writeHead);
    const size_t secondChunk = toWrite - firstChunk;

    std::memcpy(
        fifo_.data() + (writeHead * channels_),
        inBuffer,
        firstChunk * channels_ * sizeof(float)
    );

    if (secondChunk > 0) {
        std::memcpy(
            fifo_.data(),
            inBuffer + (firstChunk * channels_),
            secondChunk * channels_ * sizeof(float)
        );
    }

    fifoSize_ += toWrite;
    return static_cast<int32_t>(toWrite);
}

int32_t FractionalResampler::pullOutput(float* outBuffer, int32_t numFrames) {
    if (!outBuffer || numFrames <= 0) return 0;

    // Check for target speed updates
    const double targetRatio = targetRatio_.load(std::memory_order_relaxed);
    if (std::abs(targetRatio - activeTargetRatio_) > 1e-9) {
        activeTargetRatio_ = targetRatio;
        rampFramesRemaining_ = rampFramesTotal_;
        rampStep_ = (activeTargetRatio_ - currentRatio_) / static_cast<double>(rampFramesTotal_);
        isRamping_.store(true, std::memory_order_relaxed);
    }

    int32_t outFramesRendered = 0;
    size_t currIndex = 0;

    while (outFramesRendered < numFrames) {
        // Need at least currIndex + 2 frames in FIFO for linear, currIndex + 3 for cubic
        const size_t neededFrames = (method_ == InterpolationMethod::CubicCatmullRom) ? 3 : 2;
        if (fifoSize_ < currIndex + neededFrames) {
            // Not enough input frames in FIFO to interpolate next sample
            break;
        }

        // Apply smooth parameter ramp per sample
        if (rampFramesRemaining_ > 0) {
            currentRatio_ += rampStep_;
            rampFramesRemaining_--;
            if (rampFramesRemaining_ == 0) {
                currentRatio_ = activeTargetRatio_;
                isRamping_.store(false, std::memory_order_relaxed);
            }
        }

        const float alpha = static_cast<float>(fractionalPhase_);

        if (method_ == InterpolationMethod::CubicCatmullRom) {
            for (int32_t c = 0; c < channels_; ++c) {
                const float yPrev = (currIndex == 0)
                    ? (hasHistory_ ? history_[c] : getSample(0, c))
                    : getSample(currIndex - 1, c);
                const float y0 = getSample(currIndex, c);
                const float y1 = getSample(currIndex + 1, c);
                const float yNext = getSample(currIndex + 2, c);

                outBuffer[outFramesRendered * channels_ + c] =
                    interpolateCatmullRom(yPrev, y0, y1, yNext, alpha);
            }
        } else {
            for (int32_t c = 0; c < channels_; ++c) {
                const float y0 = getSample(currIndex, c);
                const float y1 = getSample(currIndex + 1, c);
                outBuffer[outFramesRendered * channels_ + c] =
                    interpolateLinear(y0, y1, alpha);
            }
        }

        outFramesRendered++;

        // Advance phase by effective speed ratio
        fractionalPhase_ += currentRatio_;
        const int32_t step = static_cast<int32_t>(fractionalPhase_);
        fractionalPhase_ -= step;
        currIndex += step;
    }

    // Discard consumed input frames from the FIFO and record history
    if (currIndex > 0) {
        for (int32_t c = 0; c < channels_; ++c) {
            history_[c] = getSample(currIndex - 1, c);
        }
        hasHistory_ = true;
        fifoHead_ = (fifoHead_ + currIndex) % fifoCapacityFrames_;
        fifoSize_ -= currIndex;
    }

    return outFramesRendered;
}

int32_t FractionalResampler::renderAudio(
    float* outBuffer,
    int32_t numFrames,
    const InputProvider& inputProvider
) {
    if (!outBuffer || numFrames <= 0) return 0;

    int32_t framesRendered = 0;

    while (framesRendered < numFrames) {
        // First try pulling from what is already in the FIFO
        const int32_t pulled = pullOutput(
            outBuffer + (framesRendered * channels_),
            numFrames - framesRendered
        );
        framesRendered += pulled;

        if (framesRendered >= numFrames) {
            break;
        }

        // Need more input frames from provider
        if (!inputProvider) {
            // No provider -> pad silence for remainder
            const int32_t silenceFrames = numFrames - framesRendered;
            std::memset(
                outBuffer + (framesRendered * channels_),
                0,
                silenceFrames * channels_ * sizeof(float)
            );
            framesRendered += silenceFrames;
            break;
        }

        const size_t freeSpace = fifoCapacityFrames_ - fifoSize_;
        if (freeSpace == 0) {
            // FIFO is full but we still need frames? Pull failed to produce (e.g. invalid state)
            break;
        }

        const int32_t requested = static_cast<int32_t>(
            std::min(freeSpace, providerScratch_.size() / channels_)
        );
        const int32_t provided = inputProvider(providerScratch_.data(), requested);
        if (provided <= 0) {
            // Provider is starved or underflowed -> fill remainder with silence
            const int32_t silenceFrames = numFrames - framesRendered;
            std::memset(
                outBuffer + (framesRendered * channels_),
                0,
                silenceFrames * channels_ * sizeof(float)
            );
            framesRendered += silenceFrames;
            break;
        }

        pushInput(providerScratch_.data(), provided);
    }

    return framesRendered;
}

int32_t FractionalResampler::process(
    const float* inBuffer,
    int32_t inFrames,
    int32_t& inFramesConsumed,
    float* outBuffer,
    int32_t outFrames
) {
    if (!inBuffer || inFrames <= 0 || !outBuffer || outFrames <= 0) {
        inFramesConsumed = 0;
        return 0;
    }

    inFramesConsumed = 0;
    int32_t outGenerated = 0;

    while (outGenerated < outFrames) {
        // Feed FIFO from inBuffer as needed
        const size_t neededFrames = (method_ == InterpolationMethod::CubicCatmullRom) ? 4 : 2;
        if (fifoSize_ < neededFrames && inFramesConsumed < inFrames) {
            const int32_t remainingIn = inFrames - inFramesConsumed;
            const int32_t pushed = pushInput(inBuffer + (inFramesConsumed * channels_), remainingIn);
            if (pushed > 0) {
                inFramesConsumed += pushed;
            } else {
                break;
            }
        }

        const int32_t pulled = pullOutput(
            outBuffer + (outGenerated * channels_),
            outFrames - outGenerated
        );

        if (pulled == 0) {
            // Cannot produce more without more input
            if (inFramesConsumed < inFrames) {
                const int32_t pushed = pushInput(
                    inBuffer + (inFramesConsumed * channels_),
                    inFrames - inFramesConsumed
                );
                if (pushed > 0) {
                    inFramesConsumed += pushed;
                    continue;
                }
            }
            break;
        }

        outGenerated += pulled;
    }

    return outGenerated;
}

} // namespace audio
} // namespace roombeat
