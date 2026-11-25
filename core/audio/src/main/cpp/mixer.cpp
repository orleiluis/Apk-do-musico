#include "mixer.h"

#include <algorithm>
#include <cmath>

namespace musico {

int64_t ProjectData::endFrame() const {
    int64_t end = 0;
    for (const auto& t : tracks)
        for (const auto& c : t.clips) end = std::max(end, c.start + c.length);
    return end;
}

bool ProjectData::anySolo() const {
    for (const auto& t : tracks)
        if (t.solo) return true;
    return false;
}

void mixProject(const ProjectData& project, int64_t position, float* out, int numFrames) {
    const bool solo = project.anySolo();
    const int64_t blockEnd = position + numFrames;
    for (const auto& track : project.tracks) {
        if (track.mute || (solo && !track.solo)) continue;
        // Pan de potência constante.
        const float angle = (std::clamp(track.pan, -1.0f, 1.0f) + 1.0f) * 0.78539816f;
        const float gl = track.volume * std::cos(angle) * 1.41421356f;
        const float gr = track.volume * std::sin(angle) * 1.41421356f;
        for (const auto& clip : track.clips) {
            if (!clip.buffer) continue;
            const int64_t clipEnd = clip.start + clip.length;
            const int64_t from = std::max(position, clip.start);
            const int64_t to = std::min(blockEnd, clipEnd);
            if (from >= to) continue;
            const AudioBuffer& buf = *clip.buffer;
            const bool stereo = buf.channels == 2;
            for (int64_t t = from; t < to; ++t) {
                const int64_t rel = t - clip.start;
                const int64_t src = clip.offset + rel;
                if (src < 0 || src >= buf.frames) continue;
                float env = clip.gain;
                if (clip.fadeIn > 0 && rel < clip.fadeIn) env *= static_cast<float>(rel) / clip.fadeIn;
                const int64_t left = clip.length - rel;
                if (clip.fadeOut > 0 && left < clip.fadeOut) env *= static_cast<float>(left) / clip.fadeOut;
                const float l = buf.sample(src, 0) * env;
                const float r = stereo ? buf.sample(src, 1) * env : l;
                const size_t i = static_cast<size_t>(t - position) * 2;
                out[i] += l * gl;
                out[i + 1] += r * gr;
            }
        }
    }
}

}  // namespace musico
