#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "audio_buffer.h"
#include "metronome.h"
#include "mixer.h"
#include "playback.h"
#include "ring_buffer.h"
#include "wav_writer.h"

namespace musico {

// Motor de áudio: saída (multipista + metrônomo) e entrada (gravação), via Oboe.
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kSampleRate = 48000;

    AudioEngine();
    ~AudioEngine() override;

    Metronome& metronome() { return metronome_; }

    // Metrônomo sozinho (tela do metrônomo).
    bool startMetronome();
    void stopMetronome();

    // Projeto multipista: monta um rascunho e publica com commitProject().
    void beginProject();
    void addTrack(float volume, float pan, bool mute, bool solo);
    // Retorna false se o áudio não puder ser carregado.
    bool addClip(int track, const std::string& path, int64_t start, int64_t offset, int64_t length,
                 int64_t fadeIn, int64_t fadeOut, float gain);
    void commitProject();

    // Carrega (ou pega do cache) um WAV; devolve o tamanho em quadros, ou -1.
    int64_t loadSource(const std::string& path);
    std::vector<float> peaks(const std::string& path, int buckets);
    void forgetSource(const std::string& path);

    bool play(int64_t fromFrame, bool withClick);
    void stopPlayback();
    bool isPlaying() const { return playing_.load(); }
    int64_t position() const { return playhead_.load(); }

    // Repetição do trecho A/B (quadros da linha do tempo).
    void setLoop(bool enabled, int64_t a, int64_t b);
    // Velocidade da reprodução sem mudar o tom (0,25 a 1,5). Gravando, toca sempre em 1.
    void setSpeed(double speed);
    double speed() const { return speed_.load(); }

    // Renderiza o projeto publicado num WAV estéreo, sem o metrônomo.
    bool exportMix(const std::string& path, int64_t totalFrames);

    bool startRecording(const std::string& path);
    int64_t stopRecording();  // quadros gravados
    bool isRecording() const { return recording_.load(); }
    double recordedSeconds() const;
    int recordingSampleRate() const { return inputSampleRate_.load(); }
    float takeInputPeak() { return inputPeak_.exchange(0.0f); }
    // Posição da linha do tempo quando chegou o primeiro áudio do microfone (-1 sem reprodução).
    int64_t recordStartPosition() const { return recordStartPos_.load(); }
    // Latência estimada de ida e volta (saída + entrada) da última gravação, em quadros.
    int64_t lastRoundTripLatency() const { return roundTripLatency_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openOutputLocked();
    void closeOutputLocked();
    void closeOutputIfIdleLocked();
    bool openInputLocked();
    void closeInputLocked();
    void writerLoop();
    std::shared_ptr<AudioBuffer> sourceLocked(const std::string& path);

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    Metronome metronome_;
    bool standaloneMetronome_ = false;

    std::map<std::string, std::shared_ptr<AudioBuffer>> sources_;
    std::shared_ptr<ProjectData> draft_;
    std::mutex projectMutex_;
    std::shared_ptr<const ProjectData> project_;
    std::shared_ptr<const ProjectData> audioProject_;  // cópia usada pela thread de áudio
    std::vector<float> mixScratch_;

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> playhead_{0};
    std::atomic<bool> transportClick_{false};
    std::atomic<bool> loopEnabled_{false};
    std::atomic<int64_t> loopA_{0};
    std::atomic<int64_t> loopB_{0};
    std::atomic<double> speed_{1.0};
    std::atomic<bool> restartStretch_{true};
    TimeStretcher stretcher_;
    bool stretching_ = false;  // thread de áudio

    RingBuffer ring_;
    WavWriter wav_;
    std::thread writer_;
    std::atomic<bool> recording_{false};
    std::atomic<bool> writerRunning_{false};
    std::atomic<bool> firstInput_{false};
    std::atomic<int> inputSampleRate_{kSampleRate};
    std::atomic<int64_t> framesCaptured_{0};
    std::atomic<float> inputPeak_{0.0f};
    std::atomic<int64_t> dropped_{0};
    std::atomic<int64_t> recordStartPos_{-1};
    std::atomic<int64_t> roundTripLatency_{0};
};

}  // namespace musico
