#include "wav_writer.h"

#include <algorithm>
#include <cmath>

namespace musico {

namespace {
void put16(FILE* f, uint16_t v) {
    const uint8_t b[2] = {static_cast<uint8_t>(v & 0xff), static_cast<uint8_t>(v >> 8)};
    fwrite(b, 1, 2, f);
}
void put32(FILE* f, uint32_t v) {
    const uint8_t b[4] = {static_cast<uint8_t>(v & 0xff), static_cast<uint8_t>((v >> 8) & 0xff),
                          static_cast<uint8_t>((v >> 16) & 0xff), static_cast<uint8_t>(v >> 24)};
    fwrite(b, 1, 4, f);
}
}  // namespace

bool WavWriter::open(const std::string& path, int sampleRate, int channels) {
    close();
    file_ = std::fopen(path.c_str(), "wb");
    if (!file_) return false;
    sampleRate_ = sampleRate;
    channels_ = channels;
    samplesWritten_ = 0;
    writeHeader(0);
    return true;
}

void WavWriter::writeHeader(uint32_t dataBytes) {
    const uint16_t bits = 16;
    const uint16_t blockAlign = static_cast<uint16_t>(channels_ * bits / 8);
    std::fwrite("RIFF", 1, 4, file_);
    put32(file_, 36 + dataBytes);
    std::fwrite("WAVEfmt ", 1, 8, file_);
    put32(file_, 16);
    put16(file_, 1);  // PCM
    put16(file_, static_cast<uint16_t>(channels_));
    put32(file_, static_cast<uint32_t>(sampleRate_));
    put32(file_, static_cast<uint32_t>(sampleRate_) * blockAlign);
    put16(file_, blockAlign);
    put16(file_, bits);
    std::fwrite("data", 1, 4, file_);
    put32(file_, dataBytes);
}

void WavWriter::write(const float* samples, size_t count) {
    if (!file_) return;
    int16_t chunk[1024];
    size_t done = 0;
    while (done < count) {
        const size_t n = std::min(count - done, sizeof(chunk) / sizeof(chunk[0]));
        for (size_t i = 0; i < n; ++i) {
            const float s = std::clamp(samples[done + i], -1.0f, 1.0f);
            chunk[i] = static_cast<int16_t>(std::lrint(s * 32767.0f));
        }
        // WAV é little-endian, como ARM e x86 no Android.
        std::fwrite(chunk, sizeof(int16_t), n, file_);
        done += n;
    }
    samplesWritten_ += static_cast<int64_t>(count);
}

void WavWriter::close() {
    if (!file_) return;
    const uint64_t bytes = static_cast<uint64_t>(samplesWritten_) * 2;
    std::fseek(file_, 0, SEEK_SET);
    writeHeader(static_cast<uint32_t>(std::min<uint64_t>(bytes, 0xFFFFFFFFu - 36)));
    std::fclose(file_);
    file_ = nullptr;
}

}  // namespace musico
