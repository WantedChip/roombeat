#ifndef ROOMBEAT_RING_BUFFER_H
#define ROOMBEAT_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>
#include <utility>
#include <algorithm>
#include <cstring>
#include <type_traits>

namespace roombeat {
namespace buffer {

/**
 * Cache-line size constant to prevent false sharing between producer and consumer threads.
 */
constexpr size_t kCacheLineSize = 64;

/**
 * High-performance, lock-free, wait-free Single-Producer Single-Consumer (SPSC)
 * ring buffer template.
 *
 * Sized to a power-of-2 to enable fast bitwise masking without expensive division.
 * Employs acquire/release memory semantics for safe cross-thread synchronization
 * with zero dynamic memory allocation on the audio hot path.
 */
template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t initialCapacity = 1024)
        : capacity_(roundUpPowerOfTwo(std::max<size_t>(initialCapacity, 2))),
          mask_(capacity_ - 1),
          buffer_(capacity_) {
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
    }

    ~RingBuffer() = default;

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    RingBuffer(RingBuffer&& other) noexcept
        : capacity_(other.capacity_),
          mask_(other.mask_),
          buffer_(std::move(other.buffer_)) {
        writeIndex_.store(other.writeIndex_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        readIndex_.store(other.readIndex_.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }

    RingBuffer& operator=(RingBuffer&& other) noexcept {
        if (this != &other) {
            capacity_ = other.capacity_;
            mask_ = other.mask_;
            buffer_ = std::move(other.buffer_);
            writeIndex_.store(other.writeIndex_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            readIndex_.store(other.readIndex_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        }
        return *this;
    }

    /**
     * Pushes a single item into the ring buffer.
     * Lock-free and wait-free for Single-Producer thread.
     * Returns true if pushed, false if buffer is full.
     */
    bool push(const T& item) {
        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        const size_t currentRead = readIndex_.load(std::memory_order_acquire);

        if ((currentWrite - currentRead) >= capacity_) {
            return false; // Buffer full
        }

        buffer_[currentWrite & mask_] = item;
        writeIndex_.store(currentWrite + 1, std::memory_order_release);
        return true;
    }

    /**
     * Move-pushes a single item into the ring buffer.
     * Lock-free and wait-free for Single-Producer thread.
     */
    bool push(T&& item) {
        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        const size_t currentRead = readIndex_.load(std::memory_order_acquire);

        if ((currentWrite - currentRead) >= capacity_) {
            return false; // Buffer full
        }

        buffer_[currentWrite & mask_] = std::move(item);
        writeIndex_.store(currentWrite + 1, std::memory_order_release);
        return true;
    }

    template <typename... Args>
    bool emplace(Args&&... args) {
        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        const size_t currentRead = readIndex_.load(std::memory_order_acquire);

        if ((currentWrite - currentRead) >= capacity_) {
            return false;
        }

        buffer_[currentWrite & mask_] = T(std::forward<Args>(args)...);
        writeIndex_.store(currentWrite + 1, std::memory_order_release);
        return true;
    }

    /**
     * Pops a single item from the ring buffer into destination.
     * Lock-free and wait-free for Single-Consumer thread.
     * Returns true if popped, false if buffer is empty.
     */
    bool pop(T& item) {
        const size_t currentRead = readIndex_.load(std::memory_order_relaxed);
        const size_t currentWrite = writeIndex_.load(std::memory_order_acquire);

        if (currentRead == currentWrite) {
            return false; // Buffer empty
        }

        item = std::move(buffer_[currentRead & mask_]);
        readIndex_.store(currentRead + 1, std::memory_order_release);
        return true;
    }

    /**
     * Peeks at the item at the head of the buffer without removing it.
     * Returns pointer to item, or nullptr if empty.
     */
    T* peek() {
        const size_t currentRead = readIndex_.load(std::memory_order_relaxed);
        const size_t currentWrite = writeIndex_.load(std::memory_order_acquire);

        if (currentRead == currentWrite) {
            return nullptr;
        }
        return &buffer_[currentRead & mask_];
    }

    const T* peek() const {
        const size_t currentRead = readIndex_.load(std::memory_order_relaxed);
        const size_t currentWrite = writeIndex_.load(std::memory_order_acquire);

        if (currentRead == currentWrite) {
            return nullptr;
        }
        return &buffer_[currentRead & mask_];
    }

    /**
     * Bulk write for contiguous arrays of items (e.g. PCM samples).
     * Returns number of items actually written.
     */
    size_t write(const T* source, size_t count) {
        if (!source || count == 0) return 0;

        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        const size_t currentRead = readIndex_.load(std::memory_order_acquire);

        const size_t availableSpace = capacity_ - (currentWrite - currentRead);
        const size_t toWrite = std::min(count, availableSpace);
        if (toWrite == 0) return 0;

        const size_t writePos = currentWrite & mask_;
        const size_t firstPart = std::min(toWrite, capacity_ - writePos);
        const size_t secondPart = toWrite - firstPart;

        if constexpr (std::is_trivially_copyable_v<T>) {
            std::memcpy(&buffer_[writePos], source, firstPart * sizeof(T));
            if (secondPart > 0) {
                std::memcpy(&buffer_[0], source + firstPart, secondPart * sizeof(T));
            }
        } else {
            for (size_t i = 0; i < firstPart; ++i) {
                buffer_[writePos + i] = source[i];
            }
            for (size_t i = 0; i < secondPart; ++i) {
                buffer_[i] = source[firstPart + i];
            }
        }

        writeIndex_.store(currentWrite + toWrite, std::memory_order_release);
        return toWrite;
    }

    /**
     * Bulk read for contiguous arrays of items (e.g. PCM samples).
     * Returns number of items actually read.
     */
    size_t read(T* destination, size_t count) {
        if (!destination || count == 0) return 0;

        const size_t currentRead = readIndex_.load(std::memory_order_relaxed);
        const size_t currentWrite = writeIndex_.load(std::memory_order_acquire);

        const size_t availableItems = currentWrite - currentRead;
        const size_t toRead = std::min(count, availableItems);
        if (toRead == 0) return 0;

        const size_t readPos = currentRead & mask_;
        const size_t firstPart = std::min(toRead, capacity_ - readPos);
        const size_t secondPart = toRead - firstPart;

        if constexpr (std::is_trivially_copyable_v<T>) {
            std::memcpy(destination, &buffer_[readPos], firstPart * sizeof(T));
            if (secondPart > 0) {
                std::memcpy(destination + firstPart, &buffer_[0], secondPart * sizeof(T));
            }
        } else {
            for (size_t i = 0; i < firstPart; ++i) {
                destination[i] = std::move(buffer_[readPos + i]);
            }
            for (size_t i = 0; i < secondPart; ++i) {
                destination[firstPart + i] = std::move(buffer_[i]);
            }
        }

        readIndex_.store(currentRead + toRead, std::memory_order_release);
        return toRead;
    }

    size_t size() const noexcept {
        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        const size_t currentRead = readIndex_.load(std::memory_order_relaxed);
        return (currentWrite >= currentRead) ? (currentWrite - currentRead) : 0;
    }

    size_t capacity() const noexcept { return capacity_; }
    bool isEmpty() const noexcept { return size() == 0; }
    bool isFull() const noexcept { return size() >= capacity_; }

    void clear() noexcept {
        const size_t currentWrite = writeIndex_.load(std::memory_order_relaxed);
        readIndex_.store(currentWrite, std::memory_order_release);
    }

private:
    static size_t roundUpPowerOfTwo(size_t v) noexcept {
        if (v == 0) return 1;
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        return v + 1;
    }

    size_t capacity_;
    size_t mask_;
    std::vector<T> buffer_;

    alignas(kCacheLineSize) std::atomic<size_t> writeIndex_{0};
    alignas(kCacheLineSize) std::atomic<size_t> readIndex_{0};
};

} // namespace buffer
} // namespace roombeat

#endif // ROOMBEAT_RING_BUFFER_H
