#include "audio_engine.h"

#include <android/log.h>

#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "MusicoAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace musico {

namespace {
constexpr int kSampleRate = 48000;
constexpr size_t kRingCapacity = 1 << 18;  // ~5,4 s de áudio mono a 48 kHz
}  // namespace

AudioEngine::AudioEngine() : ring_(kRingCapacity) {}

AudioEngine::~AudioEngine() {
    stopRecording();
    std::lock_guard<std::mutex> lock(mutex_);
    closeOutputLocked();
}

bool AudioEngine::openOutputLocked() {
    if (output_) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(kSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result result = builder.openStream(output_);
    if (result != oboe::Result::OK) {
        LOGE("Falha ao abrir saída: %s", oboe::convertToText(result));
        output_.reset();
        return false;
    }
    metronome_.setSampleRate(output_->getSampleRate());
    result = output_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Falha ao iniciar saída: %s", oboe::convertToText(result));
        closeOutputLocked();
        return false;
    }
    LOGI("Saída aberta: %d Hz, buffer %d quadros", output_->getSampleRate(),
         output_->getBufferSizeInFrames());
    return true;
}

void AudioEngine::closeOutputLocked() {
    if (!output_) return;
    output_->stop();
    output_->close();
    output_.reset();
}

bool AudioEngine::openInputLocked() {
    if (input_) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(kSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result result = builder.openStream(input_);
    if (result != oboe::Result::OK) {
        LOGE("Falha ao abrir microfone: %s", oboe::convertToText(result));
        input_.reset();
        return false;
    }
    inputSampleRate_.store(input_->getSampleRate());
    return true;
}

void AudioEngine::closeInputLocked() {
    if (!input_) return;
    input_->stop();
    input_->close();
    input_.reset();
}

bool AudioEngine::startMetronome() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!openOutputLocked()) return false;
    metronome_.start();
    return true;
}

void AudioEngine::stopMetronome() {
    std::lock_guard<std::mutex> lock(mutex_);
    metronome_.stop();
    closeOutputLocked();
}

bool AudioEngine::startRecording(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (recording_.load()) return false;
    if (!openInputLocked()) return false;

    if (!wav_.open(path, inputSampleRate_.load(), 1)) {
        LOGE("Não foi possível criar %s", path.c_str());
        closeInputLocked();
        return false;
    }
    ring_.clear();
    framesCaptured_.store(0);
    dropped_.store(0);
    inputPeak_.store(0.0f);

    writerRunning_.store(true);
    writer_ = std::thread(&AudioEngine::writerLoop, this);
    recording_.store(true);

    if (input_->requestStart() != oboe::Result::OK) {
        recording_.store(false);
        writerRunning_.store(false);
        writer_.join();
        wav_.close();
        closeInputLocked();
        return false;
    }
    return true;
}

int64_t AudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!recording_.load()) return 0;
    recording_.store(false);
    closeInputLocked();
    writerRunning_.store(false);
    if (writer_.joinable()) writer_.join();
    const int64_t frames = wav_.framesWritten();
    wav_.close();
    if (dropped_.load() > 0) LOGE("Gravação perdeu %lld amostras", (long long)dropped_.load());
    return frames;
}

double AudioEngine::recordedSeconds() const {
    return static_cast<double>(framesCaptured_.load()) / inputSampleRate_.load();
}

void AudioEngine::writerLoop() {
    float chunk[4096];
    while (true) {
        const size_t n = ring_.pop(chunk, 4096);
        if (n > 0) {
            wav_.write(chunk, n);
            continue;
        }
        if (!writerRunning_.load()) break;  // parou e a fila esvaziou
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    if (stream->getDirection() == oboe::Direction::Output) {
        const int channels = stream->getChannelCount();
        auto* out = static_cast<float*>(audioData);
        std::memset(out, 0, sizeof(float) * numFrames * channels);
        metronome_.render(out, numFrames, channels);
        return oboe::DataCallbackResult::Continue;
    }

    // Entrada (mono).
    if (!recording_.load(std::memory_order_relaxed)) return oboe::DataCallbackResult::Continue;
    const auto* in = static_cast<const float*>(audioData);
    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) peak = std::fmax(peak, std::fabs(in[i]));
    float prev = inputPeak_.load(std::memory_order_relaxed);
    while (peak > prev && !inputPeak_.compare_exchange_weak(prev, peak)) {
    }
    const size_t pushed = ring_.push(in, static_cast<size_t>(numFrames));
    if (pushed < static_cast<size_t>(numFrames)) {
        dropped_.fetch_add(static_cast<int64_t>(numFrames - pushed), std::memory_order_relaxed);
    }
    framesCaptured_.fetch_add(numFrames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Stream fechado (%s), tentando reabrir", oboe::convertToText(error));
    // Ex.: fone conectado ou desconectado. Reabre o que estava em uso.
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream->getDirection() == oboe::Direction::Output) {
        output_.reset();
        if (metronome_.isRunning()) openOutputLocked();
    } else {
        input_.reset();
        if (recording_.load() && openInputLocked()) input_->requestStart();
    }
}

}  // namespace musico
