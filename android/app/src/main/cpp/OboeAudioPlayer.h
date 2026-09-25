#ifndef ROOMBEAT_OBOE_AUDIO_PLAYER_H
#define ROOMBEAT_OBOE_AUDIO_PLAYER_H

#include <oboe/Oboe.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "audio/GainProcessor.h"

namespace roombeat {

constexpr int32_t kDefaultSampleRate = 48000;
constexpr int32_t kDefaultChannelCount = 2; // Stereo
constexpr size_t kDefaultRingBufferCapacity = 65536; // ~1.365s at 48kHz stereo

/**
 * Interface for audio source providers (e.g., jitter buffer, test generators)
 * to render stereo Float32 frames on demand in real time.
 */
class AudioSource {
public:
    virtual ~AudioSource() = default;

    /**
     * Renders up to numFrames of stereo interleaved Float32 audio into output.
     * Returns the actual number of frames rendered.
     */
    virtual int32_t renderAudio(float* output, int32_t numFrames) = 0;
};

/**
 * High-performance lock-free Single-Producer Single-Consumer (SPSC) Ring Buffer
 * for stereo Float32 audio frames.
 *
 * Real-time audio thread safety:
 * - Consumer reads are wait-free, lock-free, and perform zero dynamic allocations.
 * - Producer writes are mutex-protected to allow thread-safe writes from multiple sources.
 */
class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacityFrames = kDefaultRingBufferCapacity);
    ~AudioRingBuffer() = default;

    AudioRingBuffer(const AudioRingBuffer&) = delete;
    AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

    /**
     * Writes interleaved stereo Float32 frames to the buffer.
     * Returns the number of frames actually written.
     */
    int32_t write(const float* source, int32_t numFrames);

    /**
     * Writes interleaved stereo PCM16 frames to the buffer, converting to Float32.
     * Returns the number of frames actually written.
     */
    int32_t write(const int16_t* source, int32_t numFrames);

    /**
     * Reads interleaved stereo Float32 frames from the buffer into destination.
     * Real-time safe: wait-free, lock-free, zero allocation.
     * Returns the number of frames actually read.
     */
    int32_t read(float* destination, int32_t numFrames);

    /**
     * Returns the number of frames currently queued in the ring buffer.
     */
    int32_t getAvailableFrames() const;

    /**
     * Returns the total capacity of the ring buffer in frames.
     */
    int32_t getCapacity() const { return static_cast<int32_t>(capacity_); }

    /**
     * Discards all queued frames in the buffer.
     */
    void clear();

    /**
     * Records an underrun of the specified number of frames.
     */
    void recordUnderrun(int32_t numFrames) {
        underrunFrames_.fetch_add(numFrames, std::memory_order_relaxed);
    }

    int64_t getTotalFramesWritten() const {
        return totalWritten_.load(std::memory_order_relaxed);
    }

    int64_t getTotalFramesRead() const {
        return totalRead_.load(std::memory_order_relaxed);
    }

    int64_t getUnderrunFrames() const {
        return underrunFrames_.load(std::memory_order_relaxed);
    }

private:
    size_t capacity_;
    size_t mask_;
    std::vector<float> buffer_;

    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};

    std::atomic<int64_t> totalWritten_{0};
    std::atomic<int64_t> totalRead_{0};
    std::atomic<int64_t> underrunFrames_{0};

    std::mutex writeMutex_;
};

/**
 * Low-latency native audio playback stream manager using Google Oboe
 * (AAudio primary, OpenSL ES fallback), configured for 48kHz stereo output
 * with real-time audio callback processing.
 *
 * Implements:
 * - RAII lifecycle management (open, start, pause, stop, close).
 * - Automatic stream recovery in onErrorAfterClose upon hardware routing changes
 *   (e.g., headset disconnect, Bluetooth state change).
 * - Latency calculation via stream calculateLatencyMillis() and fallback estimation.
 * - Thread-safe ring buffer and AudioSource provider interface.
 */
class OboeAudioPlayer : public oboe::AudioStreamDataCallback,
                       public oboe::AudioStreamErrorCallback {
public:
    OboeAudioPlayer();
    ~OboeAudioPlayer() override;

    OboeAudioPlayer(const OboeAudioPlayer&) = delete;
    OboeAudioPlayer& operator=(const OboeAudioPlayer&) = delete;

    // Stream lifecycle
    oboe::Result open();
    oboe::Result start();
    oboe::Result pause();
    oboe::Result stop();
    oboe::Result close();
    oboe::Result restart();

    // Latency & stream telemetry
    double getLatencyMillis();
    int32_t getSampleRate() const;
    int32_t getChannelCount() const;
    oboe::AudioFormat getAudioFormat() const;
    oboe::AudioApi getAudioApi() const;
    oboe::StreamState getStreamState() const;
    int32_t getBufferSizeInFrames() const;
    int32_t getBufferCapacityInFrames() const;
    int32_t getFramesPerBurst() const;
    bool isPlaying() const;

    // Audio feeder & ring buffer interface
    int32_t write(const float* buffer, int32_t numFrames);
    int32_t write(const int16_t* buffer, int32_t numFrames);
    int32_t getAvailableFrames() const;
    int32_t getBufferCapacityFrames() const;
    void clearBuffer();
    int64_t getUnderrunCount() const;
    int64_t getFramesWritten() const;
    int64_t getFramesRead() const;

    void setAudioSource(std::shared_ptr<AudioSource> source);
    void setAutoReconnect(bool autoReconnect);

    // Volume & Digital Gain Control
    void setChannelVolume(float volumeDb);
    void setMasterVolume(float volumeDb);
    void setMuted(bool isMuted);
    void setChannelGain(float linearGain);
    void setMasterGain(float linearGain);
    float getChannelVolume() const;
    float getMasterVolume() const;
    bool isMuted() const;
    float getEffectiveGain() const;
    audio::GainProcessor& getGainProcessor() { return gainProcessor_; }

    // Audio Level Telemetry (RMS and Peak per 20ms frame)
    void getAudioLevels(float* out4) const;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames
    ) override;

    // oboe::AudioStreamErrorCallback
    void onErrorBeforeClose(
        oboe::AudioStream* stream,
        oboe::Result error
    ) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error
    ) override;

private:
    oboe::Result openInternal();
    void closeInternal();

    mutable std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> autoReconnect_{true};

    AudioRingBuffer ringBuffer_;
    std::shared_ptr<AudioSource> audioSource_;
    audio::GainProcessor gainProcessor_;

    void updateAudioLevels(const float* stereoSamples, int32_t numFrames);
    void resetAudioLevels();

    struct AudioLevels {
        std::atomic<float> leftRms{0.0f};
        std::atomic<float> rightRms{0.0f};
        std::atomic<float> leftPeak{0.0f};
        std::atomic<float> rightPeak{0.0f};
    };

    AudioLevels audioLevels_;
    int32_t levelAccumulatedFrames_{0};
    float levelSumSqLeft_{0.0f};
    float levelSumSqRight_{0.0f};
    float levelPeakLeft_{0.0f};
    float levelPeakRight_{0.0f};
};

} // namespace roombeat

#endif // ROOMBEAT_OBOE_AUDIO_PLAYER_H
