#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "audio_buffer.h"

namespace musico {

struct ClipData {
    std::shared_ptr<const AudioBuffer> buffer;
    int64_t start = 0;   // posição na linha do tempo (quadros)
    int64_t offset = 0;  // início dentro do áudio de origem
    int64_t length = 0;
    int64_t fadeIn = 0;
    int64_t fadeOut = 0;
    float gain = 1.0f;
};

struct TrackData {
    std::vector<ClipData> clips;
    float volume = 1.0f;  // 0..2
    float pan = 0.0f;     // -1 (esquerda) a 1 (direita)
    bool mute = false;
    bool solo = false;
};

struct ProjectData {
    std::vector<TrackData> tracks;
    int64_t endFrame() const;
    bool anySolo() const;
};

// Soma o projeto em `out` (estéreo intercalado) a partir de `position`.
void mixProject(const ProjectData& project, int64_t position, float* out, int numFrames);

}  // namespace musico
