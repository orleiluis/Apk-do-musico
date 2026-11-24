#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "metronome.h"
#include "ring_buffer.h"
#include "wav_writer.h"

namespace musico {

// Motor de áudio: saída (metrônomo) e entrada (gravador), via Oboe.
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    Metronome& metronome() { return metronome_; }

    bool startMetronome();
    void stopMetronome();

    bool startRecording(const std::string& path);
    // Retorna o número de quadros gravados.
    int64_t stopRecording();
    bool isRecording() const { return recording_.load(); }
    double recordedSeconds() const;
    int recordingSampleRate() const { return inputSampleRate_.load(); }
    float takeInputPeak() { return inputPeak_.exchange(0.0f); }
    int64_t droppedSamples() const { return dropped_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openOutputLocked();
    void closeOutputLocked();
    bool openInputLocked();
    void closeInputLocked();
    void writerLoop();

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    Metronome metronome_;

    RingBuffer ring_;
    WavWriter wav_;
    std::thread writer_;
    std::atomic<bool> recording_{false};
    std::atomic<bool> writerRunning_{false};
    std::atomic<int> inputSampleRate_{48000};
    std::atomic<int64_t> framesCaptured_{0};
    std::atomic<float> inputPeak_{0.0f};
    std::atomic<int64_t> dropped_{0};
};

}  // namespace musico
