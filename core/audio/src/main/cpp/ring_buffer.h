#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

namespace musico {

// Fila sem trava para um produtor (thread de áudio) e um consumidor (gravação em disco).
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityPowerOfTwo)
        : buffer_(capacityPowerOfTwo), mask_(capacityPowerOfTwo - 1) {}

    size_t capacity() const { return buffer_.size(); }

    size_t available() const {
        return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire);
    }

    // Retorna quantas amostras couberam.
    size_t push(const float* data, size_t count) {
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        const size_t space = buffer_.size() - (w - r);
        const size_t n = count < space ? count : space;
        for (size_t i = 0; i < n; ++i) buffer_[(w + i) & mask_] = data[i];
        write_.store(w + n, std::memory_order_release);
        return n;
    }

    size_t pop(float* out, size_t maxCount) {
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t avail = w - r;
        const size_t n = maxCount < avail ? maxCount : avail;
        for (size_t i = 0; i < n; ++i) out[i] = buffer_[(r + i) & mask_];
        read_.store(r + n, std::memory_order_release);
        return n;
    }

    void clear() {
        read_.store(0, std::memory_order_relaxed);
        write_.store(0, std::memory_order_relaxed);
    }

private:
    std::vector<float> buffer_;
    size_t mask_;
    std::atomic<size_t> write_{0};
    std::atomic<size_t> read_{0};
};

}  // namespace musico
