#include "OboeAudioPlayer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>

#define LOG_TAG "RoomBeatOboe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace roombeat {

namespace {

size_t roundUpToPowerOf2(size_t v) {
    if (v == 0) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
#if defined(__LP64__) || defined(_WIN64)
    v |= v >> 32;
#endif
    return v + 1;
}

} // namespace

// ============================================================================
// AudioRingBuffer Implementation
// ============================================================================

AudioRingBuffer::AudioRingBuffer(size_t capacityFrames)
    : capacity_(roundUpToPowerOf2(capacityFrames)),
      mask_(capacity_ - 1),
      buffer_(capacity_ * kDefaultChannelCount, 0.0f) {
}

int32_t AudioRingBuffer::write(const float* source, int32_t numFrames) {
    if (!source || numFrames <= 0) return 0;
    std::lock_guard<std::mutex> lock(writeMutex_);

    const size_t w = writeIndex_.load(std::memory_order_relaxed);
    const size_t r = readIndex_.load(std::memory_order_acquire);
    const size_t used = w - r;
    if (used >= capacity_) return 0; // Buffer full

    const size_t availableSpace = capacity_ - used;
    const size_t framesToWrite = std::min(static_cast<size_t>(numFrames), availableSpace);

    const size_t startFrame = w & mask_;
    const size_t seg1 = std::min(framesToWrite, capacity_ - startFrame);
    const size_t seg2 = framesToWrite - seg1;

    std::memcpy(&buffer_[startFrame * kDefaultChannelCount],
                source,
                seg1 * kDefaultChannelCount * sizeof(float));

    if (seg2 > 0) {
        std::memcpy(&buffer_[0],
                    source + (seg1 * kDefaultChannelCount),
                    seg2 * kDefaultChannelCount * sizeof(float));
    }

    writeIndex_.store(w + framesToWrite, std::memory_order_release);
    totalWritten_.fetch_add(framesToWrite, std::memory_order_relaxed);
    return static_cast<int32_t>(framesToWrite);
}

int32_t AudioRingBuffer::write(const int16_t* source, int32_t numFrames) {
    if (!source || numFrames <= 0) return 0;
    std::lock_guard<std::mutex> lock(writeMutex_);

    const size_t w = writeIndex_.load(std::memory_order_relaxed);
    const size_t r = readIndex_.load(std::memory_order_acquire);
    const size_t used = w - r;
    if (used >= capacity_) return 0;

    const size_t availableSpace = capacity_ - used;
    const size_t framesToWrite = std::min(static_cast<size_t>(numFrames), availableSpace);

    const size_t startFrame = w & mask_;
    const size_t seg1 = std::min(framesToWrite, capacity_ - startFrame);
    const size_t seg2 = framesToWrite - seg1;

    constexpr float kInvShortMax = 1.0f / 32768.0f;
    for (size_t i = 0; i < seg1 * kDefaultChannelCount; ++i) {
        buffer_[startFrame * kDefaultChannelCount + i] = static_cast<float>(source[i]) * kInvShortMax;
    }

    if (seg2 > 0) {
        const int16_t* src2 = source + (seg1 * kDefaultChannelCount);
        for (size_t i = 0; i < seg2 * kDefaultChannelCount; ++i) {
            buffer_[i] = static_cast<float>(src2[i]) * kInvShortMax;
        }
    }

    writeIndex_.store(w + framesToWrite, std::memory_order_release);
    totalWritten_.fetch_add(framesToWrite, std::memory_order_relaxed);
    return static_cast<int32_t>(framesToWrite);
}

int32_t AudioRingBuffer::read(float* destination, int32_t numFrames) {
    if (!destination || numFrames <= 0) return 0;

    const size_t r = readIndex_.load(std::memory_order_relaxed);
    const size_t w = writeIndex_.load(std::memory_order_acquire);
    if (w <= r) return 0;

    const size_t available = w - r;
    const size_t framesToRead = std::min(static_cast<size_t>(numFrames), available);

    const size_t startFrame = r & mask_;
    const size_t seg1 = std::min(framesToRead, capacity_ - startFrame);
    const size_t seg2 = framesToRead - seg1;

    std::memcpy(destination,
                &buffer_[startFrame * kDefaultChannelCount],
                seg1 * kDefaultChannelCount * sizeof(float));

    if (seg2 > 0) {
        std::memcpy(destination + (seg1 * kDefaultChannelCount),
                    &buffer_[0],
                    seg2 * kDefaultChannelCount * sizeof(float));
    }

    readIndex_.store(r + framesToRead, std::memory_order_release);
    totalRead_.fetch_add(framesToRead, std::memory_order_relaxed);
    return static_cast<int32_t>(framesToRead);
}

int32_t AudioRingBuffer::getAvailableFrames() const {
    const size_t r = readIndex_.load(std::memory_order_relaxed);
    const size_t w = writeIndex_.load(std::memory_order_relaxed);
    if (w <= r) return 0;
    const size_t available = w - r;
    return static_cast<int32_t>(std::min(available, capacity_));
}

void AudioRingBuffer::clear() {
    std::lock_guard<std::mutex> lock(writeMutex_);
    const size_t w = writeIndex_.load(std::memory_order_relaxed);
    readIndex_.store(w, std::memory_order_release);
}

// ============================================================================
// OboeAudioPlayer Implementation
// ============================================================================

OboeAudioPlayer::OboeAudioPlayer()
    : ringBuffer_(kDefaultRingBufferCapacity) {
    LOGI("OboeAudioPlayer instance created.");
}

OboeAudioPlayer::~OboeAudioPlayer() {
    LOGI("OboeAudioPlayer destroying, closing stream...");
    close();
}

oboe::Result OboeAudioPlayer::open() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_ && stream_->getState() != oboe::StreamState::Closed) {
        LOGI("AudioStream already open with state: %s", oboe::convertToText(stream_->getState()));
        return oboe::Result::OK;
    }
    return openInternal();
}

oboe::Result OboeAudioPlayer::openInternal() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setSampleRate(kDefaultSampleRate)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGW("Failed to open exclusive Float stream: %s. Retrying in shared mode...",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }

    if (result != oboe::Result::OK) {
        LOGW("Failed to open shared Float stream: %s. Retrying with I16 format...",
             oboe::convertToText(result));
        builder.setFormat(oboe::AudioFormat::I16);
        result = builder.openStream(stream_);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
        stream_.reset();
        return result;
    }

    // Set buffer size to 2 bursts for AAudio low latency optimization
    if (stream_->getAudioApi() == oboe::AudioApi::AAudio) {
        const int32_t burst = stream_->getFramesPerBurst();
        if (burst > 0) {
            auto setSizeResult = stream_->setBufferSizeInFrames(burst * 2);
            if (!setSizeResult) {
                LOGW("Failed to set buffer size: %s", oboe::convertToText(setSizeResult.error()));
            }
        }
    }

    LOGI("Oboe audio stream opened successfully: API=%s, format=%s, sampleRate=%d, channels=%d, burst=%d, bufferCapacity=%d",
         oboe::convertToText(stream_->getAudioApi()),
         oboe::convertToText(stream_->getFormat()),
         stream_->getSampleRate(),
         stream_->getChannelCount(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames());

    gainProcessor_.setSampleRate(stream_->getSampleRate());

    return oboe::Result::OK;
}

oboe::Result OboeAudioPlayer::start() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_ || stream_->getState() == oboe::StreamState::Closed) {
        const oboe::Result res = openInternal();
        if (res != oboe::Result::OK) {
            return res;
        }
    }

    if (stream_->getState() == oboe::StreamState::Started) {
        isPlaying_.store(true, std::memory_order_release);
        return oboe::Result::OK;
    }

    const oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
        isPlaying_.store(false, std::memory_order_release);
        return result;
    }

    isPlaying_.store(true, std::memory_order_release);
    LOGI("Oboe audio stream started.");
    return oboe::Result::OK;
}

oboe::Result OboeAudioPlayer::pause() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    isPlaying_.store(false, std::memory_order_release);
    resetAudioLevels();
    if (!stream_) return oboe::Result::OK;

    const oboe::Result result = stream_->requestPause();
    if (result != oboe::Result::OK) {
        LOGW("Failed to requestPause stream: %s", oboe::convertToText(result));
    }
    LOGI("Oboe audio stream paused.");
    return result;
}

oboe::Result OboeAudioPlayer::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    isPlaying_.store(false, std::memory_order_release);
    resetAudioLevels();
    if (!stream_) return oboe::Result::OK;

    const oboe::Result result = stream_->requestStop();
    if (result != oboe::Result::OK) {
        LOGW("Failed to requestStop stream: %s", oboe::convertToText(result));
    }
    LOGI("Oboe audio stream stopped.");
    return result;
}

oboe::Result OboeAudioPlayer::close() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    closeInternal();
    return oboe::Result::OK;
}

void OboeAudioPlayer::closeInternal() {
    isPlaying_.store(false, std::memory_order_release);
    resetAudioLevels();
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
        LOGI("Oboe audio stream stopped and closed.");
    }
}

oboe::Result OboeAudioPlayer::restart() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    closeInternal();
    oboe::Result res = openInternal();
    if (res == oboe::Result::OK && stream_) {
        res = stream_->requestStart();
        if (res == oboe::Result::OK) {
            isPlaying_.store(true, std::memory_order_release);
            LOGI("Oboe audio stream restarted successfully.");
        }
    }
    return res;
}

double OboeAudioPlayer::getLatencyMillis() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) return 0.0;

    auto latencyResult = stream_->calculateLatencyMillis();
    if (latencyResult) {
        const double ms = latencyResult.value();
        return (ms > 0.0) ? ms : 0.0;
    }

    // Fallback: estimate from buffer size and sample rate
    const int32_t bufferSize = stream_->getBufferSizeInFrames();
    const int32_t sampleRate = stream_->getSampleRate();
    if (sampleRate > 0 && bufferSize > 0) {
        return (static_cast<double>(bufferSize) / static_cast<double>(sampleRate)) * 1000.0;
    }

    return 0.0;
}

int32_t OboeAudioPlayer::getSampleRate() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getSampleRate() : kDefaultSampleRate;
}

int32_t OboeAudioPlayer::getChannelCount() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getChannelCount() : kDefaultChannelCount;
}

oboe::AudioFormat OboeAudioPlayer::getAudioFormat() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getFormat() : oboe::AudioFormat::Float;
}

oboe::AudioApi OboeAudioPlayer::getAudioApi() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getAudioApi() : oboe::AudioApi::Unspecified;
}

oboe::StreamState OboeAudioPlayer::getStreamState() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getState() : oboe::StreamState::Uninitialized;
}

int32_t OboeAudioPlayer::getBufferSizeInFrames() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getBufferSizeInFrames() : 0;
}

int32_t OboeAudioPlayer::getBufferCapacityInFrames() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getBufferCapacityInFrames() : 0;
}

int32_t OboeAudioPlayer::getFramesPerBurst() const {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_ ? stream_->getFramesPerBurst() : 0;
}

bool OboeAudioPlayer::isPlaying() const {
    return isPlaying_.load(std::memory_order_acquire);
}

int32_t OboeAudioPlayer::write(const float* buffer, int32_t numFrames) {
    return ringBuffer_.write(buffer, numFrames);
}

int32_t OboeAudioPlayer::write(const int16_t* buffer, int32_t numFrames) {
    return ringBuffer_.write(buffer, numFrames);
}

int32_t OboeAudioPlayer::getAvailableFrames() const {
    return ringBuffer_.getAvailableFrames();
}

int32_t OboeAudioPlayer::getBufferCapacityFrames() const {
    return ringBuffer_.getCapacity();
}

void OboeAudioPlayer::clearBuffer() {
    ringBuffer_.clear();
    resetAudioLevels();
}

int64_t OboeAudioPlayer::getUnderrunCount() const {
    return ringBuffer_.getUnderrunFrames();
}

int64_t OboeAudioPlayer::getFramesWritten() const {
    return ringBuffer_.getTotalFramesWritten();
}

int64_t OboeAudioPlayer::getFramesRead() const {
    return ringBuffer_.getTotalFramesRead();
}

void OboeAudioPlayer::setAudioSource(std::shared_ptr<AudioSource> source) {
    audioSource_ = std::move(source);
}

void OboeAudioPlayer::setAutoReconnect(bool autoReconnect) {
    autoReconnect_.store(autoReconnect, std::memory_order_release);
}

void OboeAudioPlayer::setChannelVolume(float volumeDb) {
    gainProcessor_.setChannelVolumeDb(volumeDb);
}

void OboeAudioPlayer::setMasterVolume(float volumeDb) {
    gainProcessor_.setMasterVolumeDb(volumeDb);
}

void OboeAudioPlayer::setMuted(bool isMuted) {
    gainProcessor_.setMuted(isMuted);
}

void OboeAudioPlayer::setChannelGain(float linearGain) {
    gainProcessor_.setChannelGain(linearGain);
}

void OboeAudioPlayer::setMasterGain(float linearGain) {
    gainProcessor_.setMasterGain(linearGain);
}

float OboeAudioPlayer::getChannelVolume() const {
    return gainProcessor_.getChannelVolumeDb();
}

float OboeAudioPlayer::getMasterVolume() const {
    return gainProcessor_.getMasterVolumeDb();
}

bool OboeAudioPlayer::isMuted() const {
    return gainProcessor_.isMuted();
}

float OboeAudioPlayer::getEffectiveGain() const {
    return gainProcessor_.getCurrentGain();
}

// ============================================================================
// Real-time Audio & Error Callbacks
// ============================================================================

oboe::DataCallbackResult OboeAudioPlayer::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames
) {
    if (!isPlaying_.load(std::memory_order_acquire)) {
        std::memset(audioData, 0, numFrames * stream->getBytesPerFrame());
        resetAudioLevels();
        return oboe::DataCallbackResult::Continue;
    }

    const oboe::AudioFormat format = stream->getFormat();
    if (format == oboe::AudioFormat::Float) {
        float* floatOut = static_cast<float*>(audioData);
        int32_t framesRendered = 0;

        // 1. Render from custom AudioSource provider if registered
        auto source = audioSource_;
        if (source) {
            framesRendered = source->renderAudio(floatOut, numFrames);
        }

        // 2. Fill remainder from ring buffer
        if (framesRendered < numFrames) {
            const int32_t remaining = numFrames - framesRendered;
            float* dest = floatOut + (framesRendered * kDefaultChannelCount);
            const int32_t readCount = ringBuffer_.read(dest, remaining);
            framesRendered += readCount;
        }

        // 3. Underflow fallback: output silence for any remaining frames
        if (framesRendered < numFrames) {
            const int32_t silenceFrames = numFrames - framesRendered;
            float* silenceDest = floatOut + (framesRendered * kDefaultChannelCount);
            std::memset(silenceDest, 0, silenceFrames * kDefaultChannelCount * sizeof(float));
            ringBuffer_.recordUnderrun(silenceFrames);
        }

        // 4. Apply click-free per-channel and master digital gain scaling
        gainProcessor_.process(floatOut, numFrames, kDefaultChannelCount);

        // 5. Update real-time RMS and Peak audio levels
        updateAudioLevels(floatOut, numFrames);
    } else if (format == oboe::AudioFormat::I16) {
        int16_t* i16Out = static_cast<int16_t*>(audioData);
        constexpr int32_t kStackFrames = 1024;
        float tempFloat[kStackFrames * kDefaultChannelCount];
        int32_t framesProcessed = 0;

        while (framesProcessed < numFrames) {
            const int32_t chunkFrames = std::min(numFrames - framesProcessed, kStackFrames);
            int32_t rendered = 0;

            auto source = audioSource_;
            if (source) {
                rendered = source->renderAudio(tempFloat, chunkFrames);
            }

            if (rendered < chunkFrames) {
                const int32_t remaining = chunkFrames - rendered;
                float* dest = tempFloat + (rendered * kDefaultChannelCount);
                const int32_t readCount = ringBuffer_.read(dest, remaining);
                rendered += readCount;
            }

            if (rendered < chunkFrames) {
                const int32_t silenceFrames = chunkFrames - rendered;
                float* silenceDest = tempFloat + (rendered * kDefaultChannelCount);
                std::memset(silenceDest, 0, silenceFrames * kDefaultChannelCount * sizeof(float));
                ringBuffer_.recordUnderrun(silenceFrames);
            }

            // Apply click-free digital gain scaling on the Float32 chunk
            gainProcessor_.process(tempFloat, chunkFrames, kDefaultChannelCount);

            // Update real-time RMS and Peak audio levels
            updateAudioLevels(tempFloat, chunkFrames);

            int16_t* chunkOut = i16Out + (framesProcessed * kDefaultChannelCount);
            for (int32_t s = 0; s < chunkFrames * kDefaultChannelCount; ++s) {
                const float sample = std::clamp(tempFloat[s], -1.0f, 1.0f);
                chunkOut[s] = static_cast<int16_t>(std::round(sample * 32767.0f));
            }

            framesProcessed += chunkFrames;
        }
    } else {
        std::memset(audioData, 0, numFrames * stream->getBytesPerFrame());
        resetAudioLevels();
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeAudioPlayer::onErrorBeforeClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGW("Oboe stream error before close: %s", oboe::convertToText(error));
}

void OboeAudioPlayer::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGW("Oboe stream error after close: %s", oboe::convertToText(error));

    std::lock_guard<std::mutex> lock(streamMutex_);
    // Old stream was closed by Oboe
    stream_.reset();

    if (!autoReconnect_.load(std::memory_order_acquire) || !isPlaying_.load(std::memory_order_acquire)) {
        return;
    }

    LOGI("Hardware routing changed. Attempting automatic recovery / reopening audio stream...");
    constexpr int kMaxRetries = 3;
    constexpr int kRetryDelayMs = 40;

    for (int attempt = 1; attempt <= kMaxRetries; ++attempt) {
        if (!isPlaying_.load(std::memory_order_acquire)) break;

        const oboe::Result openRes = openInternal();
        if (openRes == oboe::Result::OK && stream_) {
            const oboe::Result startRes = stream_->requestStart();
            if (startRes == oboe::Result::OK) {
                LOGI("Oboe audio stream successfully recovered and reopened on attempt %d", attempt);
                return;
            }
            stream_->close();
            stream_.reset();
        }

        if (attempt < kMaxRetries) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kRetryDelayMs));
        }
    }

    LOGE("Failed to recover Oboe audio stream after %d attempts", kMaxRetries);
    isPlaying_.store(false, std::memory_order_release);
}

void OboeAudioPlayer::updateAudioLevels(const float* stereoSamples, int32_t numFrames) {
    if (!stereoSamples || numFrames <= 0) return;

    for (int32_t i = 0; i < numFrames; ++i) {
        const float l = stereoSamples[i * 2];
        const float r = stereoSamples[i * 2 + 1];
        const float absL = std::abs(l);
        const float absR = std::abs(r);
        if (absL > levelPeakLeft_) levelPeakLeft_ = absL;
        if (absR > levelPeakRight_) levelPeakRight_ = absR;
        levelSumSqLeft_ += l * l;
        levelSumSqRight_ += r * r;
    }
    levelAccumulatedFrames_ += numFrames;

    // 20ms at 48kHz = 960 frames
    constexpr int32_t kFramesPer20Ms = 960;
    if (levelAccumulatedFrames_ >= kFramesPer20Ms) {
        const float invFrames = 1.0f / static_cast<float>(levelAccumulatedFrames_);
        const float rmsL = std::sqrt(levelSumSqLeft_ * invFrames);
        const float rmsR = std::sqrt(levelSumSqRight_ * invFrames);

        audioLevels_.leftRms.store(rmsL, std::memory_order_relaxed);
        audioLevels_.rightRms.store(rmsR, std::memory_order_relaxed);
        audioLevels_.leftPeak.store(levelPeakLeft_, std::memory_order_relaxed);
        audioLevels_.rightPeak.store(levelPeakRight_, std::memory_order_relaxed);

        levelAccumulatedFrames_ = 0;
        levelSumSqLeft_ = 0.0f;
        levelSumSqRight_ = 0.0f;
        levelPeakLeft_ = 0.0f;
        levelPeakRight_ = 0.0f;
    }
}

void OboeAudioPlayer::resetAudioLevels() {
    audioLevels_.leftRms.store(0.0f, std::memory_order_relaxed);
    audioLevels_.rightRms.store(0.0f, std::memory_order_relaxed);
    audioLevels_.leftPeak.store(0.0f, std::memory_order_relaxed);
    audioLevels_.rightPeak.store(0.0f, std::memory_order_relaxed);
    levelAccumulatedFrames_ = 0;
    levelSumSqLeft_ = 0.0f;
    levelSumSqRight_ = 0.0f;
    levelPeakLeft_ = 0.0f;
    levelPeakRight_ = 0.0f;
}

void OboeAudioPlayer::getAudioLevels(float* out4) const {
    if (!out4) return;
    out4[0] = audioLevels_.leftRms.load(std::memory_order_relaxed);
    out4[1] = audioLevels_.rightRms.load(std::memory_order_relaxed);
    out4[2] = audioLevels_.leftPeak.load(std::memory_order_relaxed);
    out4[3] = audioLevels_.rightPeak.load(std::memory_order_relaxed);
}

} // namespace roombeat
