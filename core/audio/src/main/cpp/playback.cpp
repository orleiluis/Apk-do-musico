#include "playback.h"

#include <algorithm>
#include <cmath>

namespace musico {

namespace {
constexpr int kDeclick = 96;  // quadros de descida de volume na emenda do loop
constexpr double kPi = 3.14159265358979323846;
}  // namespace

int64_t loopAdvance(int64_t pos, int64_t delta, const LoopRegion& loop) {
    int64_t next = pos + delta;
    if (!loop.active() || pos >= loop.b || next < loop.b) return std::max<int64_t>(0, next);
    const int64_t len = loop.b - loop.a;
    return loop.a + (next - loop.b) % len;
}

double loopAdvance(double pos, double delta, const LoopRegion& loop) {
    double next = pos + delta;
    if (!loop.active() || pos >= static_cast<double>(loop.b) || next < static_cast<double>(loop.b)) {
        return std::max(0.0, next);
    }
    const double len = static_cast<double>(loop.b - loop.a);
    return static_cast<double>(loop.a) + std::fmod(next - static_cast<double>(loop.b), len);
}

int64_t framesUntilWrap(int64_t pos, const LoopRegion& loop) {
    if (!loop.active() || pos >= loop.b) return -1;
    return loop.b - pos;
}

void readTimeline(const ProjectData& project, int64_t start, float* out, int numFrames, const LoopRegion& loop) {
    std::fill(out, out + numFrames * 2, 0.0f);
    int64_t pos = start;
    int done = 0;
    while (done < numFrames) {
        int n = numFrames - done;
        const int64_t untilWrap = framesUntilWrap(pos, loop);
        if (untilWrap >= 0 && untilWrap < n) n = static_cast<int>(untilWrap);
        if (n > 0) {
            mixProject(project, pos, out + done * 2, n);
            if (loop.active()) {
                // Descida de volume curta antes de B e subida depois de A.
                for (int i = 0; i < n; ++i) {
                    const int64_t p = pos + i;
                    float g = 1.0f;
                    if (p >= loop.b - kDeclick && p < loop.b) {
                        g = static_cast<float>(0.5 - 0.5 * std::cos(kPi * static_cast<double>(loop.b - p) / kDeclick));
                    } else if (p >= loop.a && p < loop.a + kDeclick) {
                        g = static_cast<float>(0.5 - 0.5 * std::cos(kPi * static_cast<double>(p - loop.a) / kDeclick));
                    }
                    out[(done + i) * 2] *= g;
                    out[(done + i) * 2 + 1] *= g;
                }
            }
            done += n;
            pos += n;
        }
        if (untilWrap >= 0 && pos >= loop.b) pos = loop.a;
    }
}

TimeStretcher::TimeStretcher()
    : window_(kWindow),
      accum_(kWindow * 2, 0.0f),
      grain_(kWindow * 2, 0.0f),
      ref_(kHop * 2, 0.0f),
      cand_((kHop + 2 * kSearch) * 2, 0.0f),
      refMono_(kHop / 2, 0.0f),
      candMono_((kHop + 2 * kSearch) / 2, 0.0f),
      fifo_(kHop * 2, 0.0f) {
    // Hann periódica: com sobreposição de 50% a soma das janelas é exatamente 1.
    for (int i = 0; i < kWindow; ++i) {
        window_[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * i / kWindow));
    }
}

void TimeStretcher::reset(const ProjectData& project, int64_t position, const LoopRegion& loop) {
    std::fill(accum_.begin(), accum_.end(), 0.0f);
    // Pedaço anterior ao início, para a primeira saída já ter a sobreposição completa.
    const int64_t prime = std::max<int64_t>(0, position - kHop);
    readTimeline(project, prime, grain_.data(), kWindow, loop);
    for (int i = 0; i < kWindow; ++i) {
        accum_[i * 2] = grain_[i * 2] * window_[i];
        accum_[i * 2 + 1] = grain_[i * 2 + 1] * window_[i];
    }
    std::copy(accum_.begin() + kHop * 2, accum_.end(), accum_.begin());
    std::fill(accum_.begin() + kHop * 2, accum_.end(), 0.0f);
    prevActual_ = prime;
    nominal_ = static_cast<double>(position);
    fifoRead_ = kHop;
    chunkNominal_ = nominal_;
    chunkSpeed_ = 1.0;
    first_ = true;
}

void TimeStretcher::step(const ProjectData& project, const LoopRegion& loop, double speed) {
    if (first_) {
        first_ = false;
    } else {
        nominal_ = loopAdvance(nominal_, kHop * speed, loop);
    }
    const int64_t nominal = static_cast<int64_t>(std::llround(nominal_));

    // Continuação natural do pedaço anterior: é com ela que o novo pedaço deve se parecer.
    const int64_t natural = loopAdvance(prevActual_, static_cast<int64_t>(kHop), loop);
    readTimeline(project, natural, ref_.data(), kHop, loop);
    const int64_t candStart = std::max<int64_t>(0, nominal - kSearch);
    const int candLen = kHop + 2 * kSearch;
    readTimeline(project, candStart, cand_.data(), candLen, loop);

    // Procura em mono, com metade da taxa (barato), o deslocamento mais parecido.
    const int refN = kHop / 2;
    const int candN = candLen / 2;
    for (int i = 0; i < refN; ++i) refMono_[i] = ref_[i * 4] + ref_[i * 4 + 1];
    for (int i = 0; i < candN; ++i) candMono_[i] = cand_[i * 4] + cand_[i * 4 + 1];
    int bestLag = static_cast<int>(nominal - candStart) / 2;
    double best = -1e30;
    const int maxLag = candN - refN;
    double energy = 0.0;
    for (int i = 0; i < refN; ++i) energy += candMono_[i] * candMono_[i];
    for (int lag = 0; lag <= maxLag; ++lag) {
        double dot = 0.0;
        const float* c = candMono_.data() + lag;
        for (int i = 0; i < refN; ++i) dot += refMono_[i] * c[i];
        const double score = dot / std::sqrt(energy + 1e-9);
        if (score > best) {
            best = score;
            bestLag = lag;
        }
        if (lag < maxLag) {
            energy += candMono_[lag + refN] * candMono_[lag + refN] - candMono_[lag] * candMono_[lag];
            if (energy < 0.0) energy = 0.0;
        }
    }
    const int64_t actual = candStart + static_cast<int64_t>(bestLag) * 2;

    readTimeline(project, actual, grain_.data(), kWindow, loop);
    for (int i = 0; i < kWindow; ++i) {
        accum_[i * 2] += grain_[i * 2] * window_[i];
        accum_[i * 2 + 1] += grain_[i * 2 + 1] * window_[i];
    }
    std::copy(accum_.begin(), accum_.begin() + kHop * 2, fifo_.begin());
    std::copy(accum_.begin() + kHop * 2, accum_.end(), accum_.begin());
    std::fill(accum_.begin() + kHop * 2, accum_.end(), 0.0f);
    prevActual_ = actual;
    chunkNominal_ = nominal_;
    chunkSpeed_ = speed;
    fifoRead_ = 0;
}

int TimeStretcher::render(const ProjectData& project, const LoopRegion& loop, double speed, float* out, int numFrames) {
    int wrapAt = -1;
    int done = 0;
    while (done < numFrames) {
        if (fifoRead_ >= kHop) step(project, loop, speed);
        const int n = std::min(numFrames - done, kHop - fifoRead_);
        if (wrapAt < 0 && loop.active() && chunkNominal_ < static_cast<double>(loop.b)) {
            // Quadro em que a posição musical passa de B.
            const double end = chunkNominal_ + (fifoRead_ + n) * chunkSpeed_;
            if (end >= static_cast<double>(loop.b)) {
                const int idx = static_cast<int>(std::ceil((static_cast<double>(loop.b) - chunkNominal_) / chunkSpeed_));
                if (idx >= fifoRead_ && idx < fifoRead_ + n) wrapAt = done + (idx - fifoRead_);
            }
        }
        std::copy(fifo_.begin() + fifoRead_ * 2, fifo_.begin() + (fifoRead_ + n) * 2, out + done * 2);
        fifoRead_ += n;
        done += n;
    }
    return wrapAt;
}

int64_t TimeStretcher::position(const LoopRegion& loop) const {
    const double pos = loopAdvance(chunkNominal_, fifoRead_ * chunkSpeed_, loop);
    return static_cast<int64_t>(pos);
}

}  // namespace musico
