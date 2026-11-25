#pragma once

#include <atomic>
#include <cstdint>

namespace musico {

// Metrônomo com precisão de amostra. As configurações podem ser alteradas de
// qualquer thread; render() roda só na thread de áudio.
class Metronome {
public:
    static constexpr double kMinBpm = 30.0;
    static constexpr double kMaxBpm = 300.0;

    void setSampleRate(int sampleRate);
    void setBpm(double bpm);
    void setBeatsPerBar(int beats);
    void setSubdivision(int subdivision);
    void setAccent(bool enabled);
    void setVolume(float volume);

    double bpm() const { return bpm_.load(std::memory_order_relaxed); }
    int beatsPerBar() const { return beatsPerBar_.load(std::memory_order_relaxed); }
    int subdivision() const { return subdivision_.load(std::memory_order_relaxed); }

    void start();
    // Começa alinhado à grade de tempos de uma linha do tempo que está em `timelineFrame`.
    void startAt(int64_t timelineFrame);
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_relaxed); }

    // Tempo atual do compasso (0 = primeiro), ou -1 parado.
    int currentBeat() const { return currentBeat_.load(std::memory_order_relaxed); }

    // Total de cliques gerados desde o início (útil em testes).
    int64_t tickCount() const { return tickCount_.load(std::memory_order_relaxed); }

    // Soma o clique em `out` (intercalado, `channels` canais).
    void render(float* out, int numFrames, int channels);

private:
    void triggerTick();
    double framesPerTick() const;

    std::atomic<double> bpm_{120.0};
    std::atomic<int> beatsPerBar_{4};
    std::atomic<int> subdivision_{1};
    std::atomic<bool> accent_{true};
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> running_{false};
    std::atomic<int> currentBeat_{-1};
    std::atomic<int64_t> tickCount_{0};
    std::atomic<int> sampleRate_{48000};
    std::atomic<int64_t> pendingAlign_{-1};

    // Estado da thread de áudio.
    bool wasRunning_ = false;
    double framesUntilNextTick_ = 0.0;
    int tickInBar_ = 0;
    int clickPos_ = 0;
    int clickLen_ = 0;
    double clickPhase_ = 0.0;
    double clickPhaseInc_ = 0.0;
    float clickAmp_ = 0.0f;
    double clickDecay_ = 0.0;
    double clickEnv_ = 0.0;
};

}  // namespace musico
