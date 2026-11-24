#pragma once

#include <cstdint>
#include <cstdio>
#include <string>

namespace musico {

// Grava WAV PCM 16 bits. O cabeçalho é corrigido em close().
class WavWriter {
public:
    ~WavWriter() { close(); }

    bool open(const std::string& path, int sampleRate, int channels);
    void write(const float* samples, size_t count);
    void close();

    bool isOpen() const { return file_ != nullptr; }
    int64_t framesWritten() const { return channels_ > 0 ? samplesWritten_ / channels_ : 0; }

private:
    void writeHeader(uint32_t dataBytes);

    FILE* file_ = nullptr;
    int sampleRate_ = 0;
    int channels_ = 0;
    int64_t samplesWritten_ = 0;
};

}  // namespace musico
