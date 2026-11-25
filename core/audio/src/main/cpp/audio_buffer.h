#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace musico {

// Áudio carregado na memória, já convertido para a taxa do motor.
// Guardado em 16 bits para ocupar metade da memória de float.
struct AudioBuffer {
    int channels = 1;  // 1 ou 2
    int64_t frames = 0;
    std::vector<int16_t> data;  // intercalado

    float sample(int64_t frame, int channel) const {
        return data[static_cast<size_t>(frame * channels + (channel < channels ? channel : 0))] *
               (1.0f / 32768.0f);
    }
};

struct WavData {
    int sampleRate = 0;
    int channels = 0;
    std::vector<float> samples;  // intercalado
};

// Lê WAV PCM 16/24/32 bits ou float 32 bits. Retorna false se o formato não for suportado.
bool readWav(const std::string& path, WavData& out);

// Converte para `targetRate` (interpolação cúbica) e limita a 2 canais.
std::shared_ptr<AudioBuffer> makeBuffer(const WavData& wav, int targetRate);

// Pico por bloco, para desenhar a forma de onda (0..1).
std::vector<float> computePeaks(const AudioBuffer& buffer, int buckets);

}  // namespace musico
