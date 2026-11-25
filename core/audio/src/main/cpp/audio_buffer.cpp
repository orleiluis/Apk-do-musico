#include "audio_buffer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace musico {

namespace {
uint32_t le32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
}  // namespace

bool readWav(const std::string& path, WavData& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size < 44) {
        std::fclose(f);
        return false;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    const size_t got = std::fread(bytes.data(), 1, bytes.size(), f);
    std::fclose(f);
    if (got != bytes.size() || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
        std::memcmp(bytes.data() + 8, "WAVE", 4) != 0)
        return false;

    int format = 0, channels = 0, rate = 0, bits = 0;
    const uint8_t* data = nullptr;
    size_t dataLen = 0;
    size_t pos = 12;
    while (pos + 8 <= bytes.size()) {
        const uint8_t* chunk = bytes.data() + pos;
        size_t len = le32(chunk + 4);
        const size_t body = pos + 8;
        if (std::memcmp(chunk, "fmt ", 4) == 0 && body + 16 <= bytes.size()) {
            format = le16(bytes.data() + body);
            channels = le16(bytes.data() + body + 2);
            rate = static_cast<int>(le32(bytes.data() + body + 4));
            bits = le16(bytes.data() + body + 14);
            if (format == 0xFFFE && len >= 26 && body + 26 <= bytes.size())
                format = le16(bytes.data() + body + 24);  // WAVE_FORMAT_EXTENSIBLE
        } else if (std::memcmp(chunk, "data", 4) == 0) {
            data = bytes.data() + body;
            // Gravações interrompidas podem ter tamanho 0 ou maior que o arquivo.
            if (len == 0 || body + len > bytes.size()) len = bytes.size() - body;
            dataLen = len;
            break;
        }
        pos = body + len + (len & 1);
    }
    if (!data || channels < 1 || rate <= 0) return false;
    const bool isFloat = format == 3 && bits == 32;
    const bool isPcm = format == 1 && (bits == 16 || bits == 24 || bits == 32);
    if (!isFloat && !isPcm) return false;

    const size_t bytesPerSample = bits / 8;
    const size_t count = dataLen / bytesPerSample;
    out.sampleRate = rate;
    out.channels = channels;
    out.samples.resize(count - count % channels);
    for (size_t i = 0; i < out.samples.size(); ++i) {
        const uint8_t* p = data + i * bytesPerSample;
        float v;
        if (isFloat) {
            std::memcpy(&v, p, 4);
        } else if (bits == 16) {
            v = static_cast<int16_t>(le16(p)) / 32768.0f;
        } else if (bits == 24) {
            int32_t s = p[0] | (p[1] << 8) | (p[2] << 16);
            if (s & 0x800000) s |= ~0xFFFFFF;
            v = s / 8388608.0f;
        } else {
            v = static_cast<int32_t>(le32(p)) / 2147483648.0f;
        }
        out.samples[i] = v;
    }
    return true;
}

std::shared_ptr<AudioBuffer> makeBuffer(const WavData& wav, int targetRate) {
    auto buf = std::make_shared<AudioBuffer>();
    if (wav.channels < 1 || wav.sampleRate <= 0) return buf;
    const int inCh = wav.channels;
    const int outCh = inCh >= 2 ? 2 : 1;
    const int64_t inFrames = static_cast<int64_t>(wav.samples.size()) / inCh;
    const double ratio = static_cast<double>(wav.sampleRate) / targetRate;
    const int64_t outFrames = static_cast<int64_t>(std::floor(inFrames / ratio));
    buf->channels = outCh;
    buf->frames = outFrames;
    buf->data.resize(static_cast<size_t>(outFrames * outCh));

    auto at = [&](int64_t frame, int c) -> float {
        frame = std::clamp<int64_t>(frame, 0, inFrames - 1);
        return wav.samples[static_cast<size_t>(frame * inCh + c)];
    };
    for (int64_t i = 0; i < outFrames; ++i) {
        const double srcPos = i * ratio;
        const int64_t i1 = static_cast<int64_t>(srcPos);
        const float t = static_cast<float>(srcPos - i1);
        for (int c = 0; c < outCh; ++c) {
            float v;
            if (wav.sampleRate == targetRate) {
                v = at(i1, c);
            } else {
                // Hermite cúbica (4 pontos).
                const float y0 = at(i1 - 1, c), y1 = at(i1, c), y2 = at(i1 + 1, c), y3 = at(i1 + 2, c);
                const float c1 = 0.5f * (y2 - y0);
                const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
                const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
                v = ((c3 * t + c2) * t + c1) * t + y1;
            }
            v = std::clamp(v, -1.0f, 1.0f);
            buf->data[static_cast<size_t>(i * outCh + c)] = static_cast<int16_t>(std::lrint(v * 32767.0f));
        }
    }
    return buf;
}

std::vector<float> computePeaks(const AudioBuffer& buffer, int buckets) {
    std::vector<float> peaks(static_cast<size_t>(std::max(buckets, 0)), 0.0f);
    if (buckets <= 0 || buffer.frames == 0) return peaks;
    for (int b = 0; b < buckets; ++b) {
        const int64_t from = buffer.frames * b / buckets;
        const int64_t to = std::max(from + 1, buffer.frames * (b + 1) / buckets);
        int peak = 0;
        for (int64_t i = from * buffer.channels; i < to * buffer.channels && i < (int64_t)buffer.data.size(); ++i)
            peak = std::max(peak, std::abs(static_cast<int>(buffer.data[static_cast<size_t>(i)])));
        peaks[static_cast<size_t>(b)] = peak / 32768.0f;
    }
    return peaks;
}

}  // namespace musico
