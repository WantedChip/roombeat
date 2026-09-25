#include "AudioJitterBuffer.h"
#include <cmath>
#include <cstring>
#include <ctime>
#include <algorithm>

namespace roombeat {
namespace buffer {

namespace {

constexpr double kPi = 3.14159265358979323846;

struct FadeTables {
    float fadeOut[kSamplesPerChannel];
    float fadeIn[kSamplesPerChannel];

    FadeTables() {
        for (int i = 0; i < kSamplesPerChannel; ++i) {
            const double t = static_cast<double>(i) / static_cast<double>(kSamplesPerChannel - 1);
            fadeOut[i] = static_cast<float>(0.5 * (1.0 + std::cos(kPi * t)));
            fadeIn[i] = static_cast<float>(0.5 * (1.0 - std::cos(kPi * t)));
        }
    }
};

const FadeTables& getFadeTables() {
    static const FadeTables tables;
    return tables;
}

int64_t defaultMonotonicClockUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000LL + (ts.tv_nsec / 1000LL);
}

} // anonymous namespace

void AudioJitterBuffer::applyFadeOut(float* frame, int32_t numFrames) {
    if (!frame || numFrames <= 0) return;

    if (numFrames == kSamplesPerChannel) {
        const auto& tables = getFadeTables();
        for (int32_t i = 0; i < numFrames; ++i) {
            const float w = tables.fadeOut[i];
            frame[i * kJitterChannelCount] *= w;
            frame[i * kJitterChannelCount + 1] *= w;
        }
    } else {
        const double denom = static_cast<double>(numFrames - 1);
        for (int32_t i = 0; i < numFrames; ++i) {
            const double t = (denom > 0.0) ? (static_cast<double>(i) / denom) : 1.0;
            const float w = static_cast<float>(0.5 * (1.0 + std::cos(kPi * t)));
            frame[i * kJitterChannelCount] *= w;
            frame[i * kJitterChannelCount + 1] *= w;
        }
    }
}

void AudioJitterBuffer::applyFadeIn(float* frame, int32_t numFrames) {
    if (!frame || numFrames <= 0) return;

    if (numFrames == kSamplesPerChannel) {
        const auto& tables = getFadeTables();
        for (int32_t i = 0; i < numFrames; ++i) {
            const float w = tables.fadeIn[i];
            frame[i * kJitterChannelCount] *= w;
            frame[i * kJitterChannelCount + 1] *= w;
        }
    } else {
        const double denom = static_cast<double>(numFrames - 1);
        for (int32_t i = 0; i < numFrames; ++i) {
            const double t = (denom > 0.0) ? (static_cast<double>(i) / denom) : 1.0;
            const float w = static_cast<float>(0.5 * (1.0 - std::cos(kPi * t)));
            frame[i * kJitterChannelCount] *= w;
            frame[i * kJitterChannelCount + 1] *= w;
        }
    }
}

AudioJitterBuffer::AudioJitterBuffer(
    int32_t targetDepthMs,
    size_t capacityFrames,
    std::shared_ptr<roombeat::audio::OpusDecoderWrapper> decoder
) : targetDepthMs_(std::max(20, targetDepthMs)),
    capacityFrames_(capacityFrames > 0 ? capacityFrames : kDefaultCapacityFrames),
    decoder_(std::move(decoder)),
    clockFunc_(defaultMonotonicClockUs),
    slots_(capacityFrames_),
    activeFrameBuffer_(kInterleavedSamples, 0.0f) {
    if (!decoder_) {
        decoder_ = std::make_shared<roombeat::audio::OpusDecoderWrapper>(kJitterSampleRate, kJitterChannelCount);
        ownsDecoder_ = true;
    }
    reset();
}

void AudioJitterBuffer::reset() {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    for (auto& slot : slots_) {
        slot.occupied.store(false, std::memory_order_relaxed);
        slot.sequenceNumber = 0;
        slot.presentationTimeUs = 0;
        slot.opusDataSize = 0;
        slot.isPreDecoded = false;
    }
    queuedFrames_.store(0, std::memory_order_release);
    playbackStarted_.store(false, std::memory_order_release);
    state_ = BufferState::BUFFERING;
    fadeState_ = FadeState::MUTED;
    nextPlaySeq_ = 0;
    consecutiveLostFrames_ = 0;
    activeFrameOffset_ = 0;
    activeFrameRemaining_ = 0;
    targetStartTimeUs_ = 0;
    std::fill(activeFrameBuffer_.begin(), activeFrameBuffer_.end(), 0.0f);
    if (decoder_) {
        decoder_->resetState();
    }
}

void AudioJitterBuffer::flushAndSeek(uint64_t newInitialSeq, int64_t newTargetStartTimeUs) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    for (auto& slot : slots_) {
        slot.occupied.store(false, std::memory_order_relaxed);
        slot.sequenceNumber = 0;
        slot.presentationTimeUs = 0;
        slot.opusDataSize = 0;
        slot.isPreDecoded = false;
    }
    queuedFrames_.store(0, std::memory_order_release);
    playbackStarted_.store(false, std::memory_order_release);
    state_ = BufferState::BUFFERING;
    fadeState_ = FadeState::MUTED;
    nextPlaySeq_ = newInitialSeq;
    consecutiveLostFrames_ = 0;
    activeFrameOffset_ = 0;
    activeFrameRemaining_ = 0;
    targetStartTimeUs_ = newTargetStartTimeUs;
    std::fill(activeFrameBuffer_.begin(), activeFrameBuffer_.end(), 0.0f);
    if (decoder_) {
        decoder_->resetState();
    }
}

bool AudioJitterBuffer::pushPacket(
    uint64_t sequenceNumber,
    int64_t presentationTimeUs,
    const uint8_t* payload,
    size_t size
) {
    if (!payload || size == 0 || size > kMaxOpusPayloadBytes) {
        return false;
    }

    std::lock_guard<std::mutex> lock(bufferMutex_);

    // 1. Late packet check: if playback has started and packet sequence is already past
    if (playbackStarted_.load(std::memory_order_relaxed) && sequenceNumber < nextPlaySeq_) {
        latePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    // 2. Deadline check: if packet presentation timestamp has already elapsed
    if (presentationTimeUs > 0) {
        const int64_t nowUs = clockFunc_();
        if (playbackStarted_.load(std::memory_order_relaxed) && (nowUs > (presentationTimeUs + kDefaultLateToleranceUs))) {
            latePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
    }

    // 3. Overflow / excessive jump check
    if (playbackStarted_.load(std::memory_order_relaxed) && (sequenceNumber >= nextPlaySeq_ + capacityFrames_)) {
        return false;
    }

    // 4. Slot index and duplicate check
    const size_t slotIdx = sequenceNumber % capacityFrames_;
    FrameSlot& slot = slots_[slotIdx];

    if (slot.occupied.load(std::memory_order_relaxed)) {
        if (slot.sequenceNumber == sequenceNumber) {
            duplicatePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        return false;
    }

    // 5. Populate slot
    std::memcpy(slot.opusData, payload, size);
    slot.opusDataSize = size;
    slot.sequenceNumber = sequenceNumber;
    slot.presentationTimeUs = presentationTimeUs;
    slot.isPreDecoded = false;
    slot.occupied.store(true, std::memory_order_release);

    queuedFrames_.fetch_add(1, std::memory_order_relaxed);
    totalPacketsReceived_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool AudioJitterBuffer::pushDecodedFrame(
    uint64_t sequenceNumber,
    int64_t presentationTimeUs,
    const float* pcmData,
    int32_t numFrames
) {
    if (!pcmData || numFrames <= 0 || numFrames > kSamplesPerChannel) {
        return false;
    }

    std::lock_guard<std::mutex> lock(bufferMutex_);

    if (playbackStarted_.load(std::memory_order_relaxed) && sequenceNumber < nextPlaySeq_) {
        latePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    if (presentationTimeUs > 0) {
        const int64_t nowUs = clockFunc_();
        if (playbackStarted_.load(std::memory_order_relaxed) && (nowUs > (presentationTimeUs + kDefaultLateToleranceUs))) {
            latePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
    }

    if (playbackStarted_.load(std::memory_order_relaxed) && (sequenceNumber >= nextPlaySeq_ + capacityFrames_)) {
        return false;
    }

    const size_t slotIdx = sequenceNumber % capacityFrames_;
    FrameSlot& slot = slots_[slotIdx];

    if (slot.occupied.load(std::memory_order_relaxed)) {
        if (slot.sequenceNumber == sequenceNumber) {
            duplicatePacketsDropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        return false;
    }

    std::memcpy(slot.decodedPcm, pcmData, numFrames * kJitterChannelCount * sizeof(float));
    slot.opusDataSize = 0;
    slot.sequenceNumber = sequenceNumber;
    slot.presentationTimeUs = presentationTimeUs;
    slot.isPreDecoded = true;
    slot.occupied.store(true, std::memory_order_release);

    queuedFrames_.fetch_add(1, std::memory_order_relaxed);
    totalPacketsReceived_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

uint64_t AudioJitterBuffer::findLowestBufferedSeq() const {
    uint64_t minSeq = UINT64_MAX;
    bool found = false;
    for (const auto& slot : slots_) {
        if (slot.occupied.load(std::memory_order_relaxed)) {
            if (!found || slot.sequenceNumber < minSeq) {
                minSeq = slot.sequenceNumber;
                found = true;
            }
        }
    }
    return found ? minSeq : 0;
}

bool AudioJitterBuffer::fetchNext20msFrame(float* outFrame) {
    std::lock_guard<std::mutex> lock(bufferMutex_);

    // 1. Buffering state check
    if (state_ == BufferState::BUFFERING) {
        const int32_t targetFrames = std::max(1, targetDepthMs_ / kFrameDurationMs);
        if (queuedFrames_.load(std::memory_order_acquire) < targetFrames) {
            // Buffer is accumulating frames to absorb jitter
            return false;
        }

        // Target presentation start timestamp / release lock check
        if (targetStartTimeUs_ > 0) {
            const int64_t nowUs = clockFunc_();
            if (nowUs < (targetStartTimeUs_ - kEarlyToleranceUs)) {
                // Scheduled presentation start time is in future -> wait (output silence)
                return false;
            }
        }

        // Target depth reached and targetStartTime reached! Transition to PLAYING
        state_ = BufferState::PLAYING;
        playbackStarted_.store(true, std::memory_order_release);
        nextPlaySeq_ = findLowestBufferedSeq();
    }

    // 2. In PLAYING state, query expected sequence number
    const uint64_t targetSeq = nextPlaySeq_;
    const size_t slotIdx = targetSeq % capacityFrames_;
    FrameSlot& slot = slots_[slotIdx];

    if (slot.occupied.load(std::memory_order_acquire) && slot.sequenceNumber == targetSeq) {
        // --- Packet is present ---
        if (slot.presentationTimeUs > 0) {
            const int64_t nowUs = clockFunc_();
            if (nowUs < (slot.presentationTimeUs - kEarlyToleranceUs)) {
                // Scheduled presentation time is in future -> wait
                return false;
            }
        }

        consecutiveLostFrames_ = 0;

        if (slot.isPreDecoded) {
            std::memcpy(outFrame, slot.decodedPcm, kInterleavedSamples * sizeof(float));
        } else {
            int decoded = decoder_->decode(slot.opusData, static_cast<int>(slot.opusDataSize),
                                          outFrame, kSamplesPerChannel);
            if (decoded <= 0) {
                std::memset(outFrame, 0, kInterleavedSamples * sizeof(float));
            }
        }

        slot.occupied.store(false, std::memory_order_release);
        queuedFrames_.fetch_sub(1, std::memory_order_relaxed);
        nextPlaySeq_++;
        packetsPlayed_.fetch_add(1, std::memory_order_relaxed);

        // If resuming from MUTED, apply 20ms fade-in
        if (fadeState_ == FadeState::MUTED) {
            applyFadeIn(outFrame, kSamplesPerChannel);
            fadeState_ = FadeState::IDLE_PLAYING;
        }

        return true;
    }

    // --- Packet is MISSING (Sequence Gap or Underflow) ---
    consecutiveLostFrames_++;

    if (consecutiveLostFrames_ <= kMaxPlcConsecutiveFrames) {
        // Within PLC tolerance: invoke Opus Packet Loss Concealment
        plcCount_.fetch_add(1, std::memory_order_relaxed);

        int plcSamples = decoder_->decode(nullptr, 0, outFrame, kSamplesPerChannel);
        if (plcSamples <= 0) {
            std::memset(outFrame, 0, kInterleavedSamples * sizeof(float));
        }

        nextPlaySeq_++;
        packetsPlayed_.fetch_add(1, std::memory_order_relaxed);

        // If this is the last allowed PLC frame, apply smooth 20ms fade-out
        if (consecutiveLostFrames_ == kMaxPlcConsecutiveFrames) {
            applyFadeOut(outFrame, kSamplesPerChannel);
            fadeState_ = FadeState::MUTED;
        }

        return true;
    }

    // Exceeded PLC tolerance (> 3 frames):
    underrunCount_.fetch_add(1, std::memory_order_relaxed);
    fadeState_ = FadeState::MUTED;

    // Check if there are future queued packets in the buffer
    if (queuedFrames_.load(std::memory_order_acquire) > 0) {
        uint64_t nextAvailable = findLowestBufferedSeq();
        if (nextAvailable > nextPlaySeq_) {
            nextPlaySeq_ = nextAvailable;
        } else {
            nextPlaySeq_++;
        }
    } else {
        // Buffer completely drained -> re-enter BUFFERING state
        state_ = BufferState::BUFFERING;
    }

    return false;
}

int32_t AudioJitterBuffer::renderAudio(float* output, int32_t numFrames) {
    if (!output || numFrames <= 0) return 0;

    int32_t framesRendered = 0;
    while (framesRendered < numFrames) {
        if (activeFrameRemaining_ > 0) {
            const int32_t toCopy = std::min(numFrames - framesRendered, activeFrameRemaining_);
            const float* src = activeFrameBuffer_.data() + (activeFrameOffset_ * kJitterChannelCount);
            float* dst = output + (framesRendered * kJitterChannelCount);
            std::memcpy(dst, src, toCopy * kJitterChannelCount * sizeof(float));

            activeFrameOffset_ += toCopy;
            activeFrameRemaining_ -= toCopy;
            framesRendered += toCopy;
        } else {
            bool hasFrame = fetchNext20msFrame(activeFrameBuffer_.data());
            if (hasFrame) {
                activeFrameOffset_ = 0;
                activeFrameRemaining_ = kSamplesPerChannel; // 960
            } else {
                // Buffer starvation / underflow / buffering: output silence
                const int32_t silenceFrames = numFrames - framesRendered;
                float* dst = output + (framesRendered * kJitterChannelCount);
                std::memset(dst, 0, silenceFrames * kJitterChannelCount * sizeof(float));
                framesRendered += silenceFrames;
                break;
            }
        }
    }
    return framesRendered;
}

void AudioJitterBuffer::setTargetDepthMs(int32_t depthMs) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    targetDepthMs_ = std::max(20, depthMs);
}

int32_t AudioJitterBuffer::getTargetDepthMs() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return targetDepthMs_;
}

int32_t AudioJitterBuffer::getQueuedFrames() const {
    return queuedFrames_.load(std::memory_order_relaxed);
}

JitterBufferStats AudioJitterBuffer::getStats() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    JitterBufferStats s;
    s.totalPacketsReceived = totalPacketsReceived_.load(std::memory_order_relaxed);
    s.packetsPlayed = packetsPlayed_.load(std::memory_order_relaxed);
    s.plcCount = plcCount_.load(std::memory_order_relaxed);
    s.latePacketsDropped = latePacketsDropped_.load(std::memory_order_relaxed);
    s.duplicatePacketsDropped = duplicatePacketsDropped_.load(std::memory_order_relaxed);
    s.underrunCount = underrunCount_.load(std::memory_order_relaxed);
    s.currentQueuedFrames = queuedFrames_.load(std::memory_order_relaxed);
    s.targetDepthMs = targetDepthMs_;
    s.consecutiveLostFrames = consecutiveLostFrames_;
    return s;
}

void AudioJitterBuffer::setClockFunction(ClockFunction clockFunc) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    clockFunc_ = clockFunc ? std::move(clockFunc) : defaultMonotonicClockUs;
}

int64_t AudioJitterBuffer::getCurrentTimeUs() const {
    return clockFunc_();
}

void AudioJitterBuffer::setTargetStartTimeUs(int64_t targetTimeUs) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    targetStartTimeUs_ = targetTimeUs;
}

int64_t AudioJitterBuffer::getTargetStartTimeUs() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return targetStartTimeUs_;
}

} // namespace buffer
} // namespace roombeat
