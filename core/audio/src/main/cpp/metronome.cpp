#include "metronome.h"

#include <algorithm>
#include <cmath>

namespace musico {

namespace {
constexpr double kTwoPi = 6.283185307179586;
constexpr double kClickSeconds = 0.05;
constexpr double kDecaySeconds = 0.012;
}  // namespace

void Metronome::setSampleRate(int sampleRate) {
    if (sampleRate > 0) sampleRate_.store(sampleRate, std::memory_order_relaxed);
}

void Metronome::setBpm(double bpm) {
    bpm_.store(std::clamp(bpm, kMinBpm, kMaxBpm), std::memory_order_relaxed);
}

void Metronome::setBeatsPerBar(int beats) {
    beatsPerBar_.store(std::clamp(beats, 1, 16), std::memory_order_relaxed);
}

void Metronome::setSubdivision(int subdivision) {
    subdivision_.store(std::clamp(subdivision, 1, 4), std::memory_order_relaxed);
}

void Metronome::setAccent(bool enabled) { accent_.store(enabled, std::memory_order_relaxed); }

void Metronome::setVolume(float volume) {
    volume_.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
}

void Metronome::start() { running_.store(true, std::memory_order_relaxed); }

void Metronome::stop() {
    running_.store(false, std::memory_order_relaxed);
    currentBeat_.store(-1, std::memory_order_relaxed);
}

double Metronome::framesPerTick() const {
    const double sr = sampleRate_.load(std::memory_order_relaxed);
    return sr * 60.0 / (bpm() * subdivision());
}

void Metronome::triggerTick() {
    const int sub = subdivision();
    const int ticksPerBar = beatsPerBar() * sub;
    if (tickInBar_ >= ticksPerBar) tickInBar_ = 0;

    const int beat = tickInBar_ / sub;
    const bool onBeat = (tickInBar_ % sub) == 0;
    const bool accented = onBeat && beat == 0 && accent_.load(std::memory_order_relaxed);

    double freq;
    if (accented) {
        freq = 1760.0;
        clickAmp_ = 1.0f;
    } else if (onBeat) {
        freq = 1320.0;
        clickAmp_ = 0.7f;
    } else {
        freq = 990.0;
        clickAmp_ = 0.35f;
    }

    const double sr = sampleRate_.load(std::memory_order_relaxed);
    clickPhase_ = 0.0;
    clickPhaseInc_ = kTwoPi * freq / sr;
    clickLen_ = static_cast<int>(sr * kClickSeconds);
    clickPos_ = 0;
    clickEnv_ = 1.0;
    clickDecay_ = std::exp(-1.0 / (sr * kDecaySeconds));

    if (onBeat) currentBeat_.store(beat, std::memory_order_relaxed);
    tickCount_.fetch_add(1, std::memory_order_relaxed);
    ++tickInBar_;
}

void Metronome::render(float* out, int numFrames, int channels) {
    const bool running = isRunning();
    if (running && !wasRunning_) {
        framesUntilNextTick_ = 0.0;
        tickInBar_ = 0;
    }
    wasRunning_ = running;

    const float volume = volume_.load(std::memory_order_relaxed);
    const double step = running ? framesPerTick() : 0.0;

    for (int i = 0; i < numFrames; ++i) {
        if (running) {
            if (framesUntilNextTick_ <= 0.0) {
                triggerTick();
                framesUntilNextTick_ += step;
            }
            framesUntilNextTick_ -= 1.0;
        }

        if (clickPos_ < clickLen_) {
            const float s = static_cast<float>(clickAmp_ * clickEnv_ * std::sin(clickPhase_)) * volume;
            clickPhase_ += clickPhaseInc_;
            clickEnv_ *= clickDecay_;
            ++clickPos_;
            for (int c = 0; c < channels; ++c) out[i * channels + c] += s;
        }
    }
}

}  // namespace musico
