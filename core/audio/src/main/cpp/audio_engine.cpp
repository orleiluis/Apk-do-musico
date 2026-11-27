#include "audio_engine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "MusicoAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace musico {

namespace {
constexpr size_t kRingCapacity = 1 << 18;  // ~5,4 s de áudio mono a 48 kHz
constexpr int kMaxCallbackFrames = 8192;
}  // namespace

AudioEngine::AudioEngine() : project_(std::make_shared<ProjectData>()), ring_(kRingCapacity) {
    mixScratch_.resize(kMaxCallbackFrames * 2);
}

AudioEngine::~AudioEngine() {
    stopRecording();
    std::lock_guard<std::mutex> lock(mutex_);
    closeOutputLocked();
}

// ---------- Saída ----------

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

void AudioEngine::closeOutputIfIdleLocked() {
    if (!playing_.load() && !standaloneMetronome_) closeOutputLocked();
}

bool AudioEngine::startMetronome() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!openOutputLocked()) return false;
    standaloneMetronome_ = true;
    metronome_.start();
    return true;
}

void AudioEngine::stopMetronome() {
    std::lock_guard<std::mutex> lock(mutex_);
    standaloneMetronome_ = false;
    if (!(playing_.load() && transportClick_.load())) metronome_.stop();
    closeOutputIfIdleLocked();
}

// ---------- Projeto ----------

std::shared_ptr<AudioBuffer> AudioEngine::sourceLocked(const std::string& path) {
    auto it = sources_.find(path);
    if (it != sources_.end()) return it->second;
    WavData wav;
    if (!readWav(path, wav)) {
        LOGE("Não foi possível ler %s", path.c_str());
        return nullptr;
    }
    auto buf = makeBuffer(wav, kSampleRate);
    sources_[path] = buf;
    return buf;
}

int64_t AudioEngine::loadSource(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto buf = sourceLocked(path);
    return buf ? buf->frames : -1;
}

std::vector<float> AudioEngine::peaks(const std::string& path, int buckets) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto buf = sourceLocked(path);
    if (!buf) return {};
    return computePeaks(*buf, buckets);
}

void AudioEngine::forgetSource(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    sources_.erase(path);
}

void AudioEngine::beginProject() {
    std::lock_guard<std::mutex> lock(mutex_);
    draft_ = std::make_shared<ProjectData>();
}

void AudioEngine::addTrack(float volume, float pan, bool mute, bool solo) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!draft_) draft_ = std::make_shared<ProjectData>();
    TrackData t;
    t.volume = volume;
    t.pan = pan;
    t.mute = mute;
    t.solo = solo;
    draft_->tracks.push_back(std::move(t));
}

bool AudioEngine::addClip(int track, const std::string& path, int64_t start, int64_t offset,
                          int64_t length, int64_t fadeIn, int64_t fadeOut, float gain) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!draft_ || track < 0 || track >= static_cast<int>(draft_->tracks.size())) return false;
    auto buf = sourceLocked(path);
    if (!buf) return false;
    ClipData c;
    c.buffer = buf;
    c.start = start;
    c.offset = offset;
    c.length = length;
    c.fadeIn = fadeIn;
    c.fadeOut = fadeOut;
    c.gain = gain;
    draft_->tracks[static_cast<size_t>(track)].clips.push_back(std::move(c));
    return true;
}

void AudioEngine::commitProject() {
    std::shared_ptr<const ProjectData> next;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        next = draft_ ? draft_ : std::make_shared<ProjectData>();
        draft_.reset();
    }
    std::lock_guard<std::mutex> lock(projectMutex_);
    project_ = next;
}

void AudioEngine::setLoop(bool enabled, int64_t a, int64_t b) {
    loopA_.store(std::max<int64_t>(0, a));
    loopB_.store(std::max<int64_t>(0, b));
    loopEnabled_.store(enabled);
    restartStretch_.store(true);
}

void AudioEngine::setSpeed(double speed) {
    speed_.store(std::clamp(speed, 0.25, 1.5));
}

bool AudioEngine::play(int64_t fromFrame, bool withClick) {
    std::lock_guard<std::mutex> lock(mutex_);
    int64_t from = std::max<int64_t>(0, fromFrame);
    // Com o loop ligado e a agulha depois de B, começa em A.
    if (loopEnabled_.load() && loopB_.load() > loopA_.load() && from >= loopB_.load()) from = loopA_.load();
    playhead_.store(from);
    restartStretch_.store(true);
    transportClick_.store(withClick);
    if (!openOutputLocked()) return false;
    if (withClick) metronome_.startAt(playhead_.load());
    playing_.store(true);
    return true;
}

void AudioEngine::stopPlayback() {
    std::lock_guard<std::mutex> lock(mutex_);
    playing_.store(false);
    if (transportClick_.exchange(false) && !standaloneMetronome_) metronome_.stop();
    closeOutputIfIdleLocked();
}

bool AudioEngine::exportMix(const std::string& path, int64_t totalFrames) {
    std::shared_ptr<const ProjectData> project;
    {
        std::lock_guard<std::mutex> lock(projectMutex_);
        project = project_;
    }
    WavWriter writer;
    if (!writer.open(path, kSampleRate, 2)) return false;
    std::vector<float> block(4096 * 2);
    for (int64_t pos = 0; pos < totalFrames; pos += 4096) {
        const int n = static_cast<int>(std::min<int64_t>(4096, totalFrames - pos));
        std::fill(block.begin(), block.end(), 0.0f);
        mixProject(*project, pos, block.data(), n);
        writer.write(block.data(), static_cast<size_t>(n) * 2);
    }
    writer.close();
    return true;
}

// ---------- Entrada ----------

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
    recordStartPos_.store(-1);
    firstInput_.store(true);

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

    double latencyMs = 0.0;
    if (input_) {
        auto in = input_->calculateLatencyMillis();
        if (in) latencyMs += in.value();
    }
    if (output_) {
        auto out = output_->calculateLatencyMillis();
        if (out) latencyMs += out.value();
    }
    roundTripLatency_.store(static_cast<int64_t>(latencyMs * kSampleRate / 1000.0));

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

// ---------- Callbacks ----------

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    if (stream->getDirection() == oboe::Direction::Output) {
        const int channels = stream->getChannelCount();
        auto* out = static_cast<float*>(audioData);
        std::memset(out, 0, sizeof(float) * numFrames * channels);

        if (playing_.load(std::memory_order_relaxed)) {
            // Pega o projeto mais novo sem bloquear; se estiver ocupado, usa o anterior.
            if (projectMutex_.try_lock()) {
                audioProject_ = project_;
                projectMutex_.unlock();
            }
            int64_t pos = playhead_.load(std::memory_order_relaxed);
            LoopRegion loop;
            loop.enabled = loopEnabled_.load(std::memory_order_relaxed);
            loop.a = loopA_.load(std::memory_order_relaxed);
            loop.b = loopB_.load(std::memory_order_relaxed);
            // Gravando, sem loop e sem mudar a velocidade: a gravação precisa da linha do tempo real.
            const bool rec = recording_.load(std::memory_order_relaxed);
            if (rec) loop.enabled = false;
            const double speed = rec ? 1.0 : speed_.load(std::memory_order_relaxed);
            const bool click = transportClick_.load(std::memory_order_relaxed);
            metronome_.setRate(click ? speed : 1.0);
            if (loop.active() && pos >= loop.b) {
                pos = loop.a;
                if (click) metronome_.startAt(pos);
                restartStretch_.store(true, std::memory_order_relaxed);
            }
            int wrapAt = -1;
            if (audioProject_ && numFrames <= kMaxCallbackFrames) {
                float* mix = mixScratch_.data();
                const bool stretch = std::fabs(speed - 1.0) > 1e-3;
                if (stretch) {
                    if (!stretching_ || restartStretch_.exchange(false, std::memory_order_relaxed)) {
                        stretcher_.reset(*audioProject_, pos, loop);
                    }
                    stretching_ = true;
                    wrapAt = stretcher_.render(*audioProject_, loop, speed, mix, numFrames);
                    pos = stretcher_.position(loop);
                } else {
                    stretching_ = false;
                    restartStretch_.store(false, std::memory_order_relaxed);
                    readTimeline(*audioProject_, pos, mix, numFrames, loop);
                    const int64_t until = framesUntilWrap(pos, loop);
                    if (until >= 0 && until < numFrames) wrapAt = static_cast<int>(until);
                    pos = loopAdvance(pos, static_cast<int64_t>(numFrames), loop);
                }
                for (int i = 0; i < numFrames; ++i) {
                    if (channels == 1) {
                        out[i] = 0.5f * (mix[i * 2] + mix[i * 2 + 1]);
                    } else {
                        out[i * channels] = mix[i * 2];
                        out[i * channels + 1] = mix[i * 2 + 1];
                    }
                }
            } else {
                pos = loopAdvance(pos, static_cast<int64_t>(numFrames), loop);
            }
            playhead_.store(pos, std::memory_order_relaxed);
            if (wrapAt > 0 && wrapAt < numFrames) {
                // O clique volta junto com a música: realinha em A no quadro da volta.
                metronome_.render(out, wrapAt, channels);
                if (click) metronome_.startAt(loop.a);
                metronome_.render(out + wrapAt * channels, numFrames - wrapAt, channels);
            } else {
                if (wrapAt == 0 && click) metronome_.startAt(loop.a);
                metronome_.render(out, numFrames, channels);
            }
        } else {
            metronome_.setRate(1.0);
            metronome_.render(out, numFrames, channels);
        }
        for (int i = 0; i < numFrames * channels; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // Entrada (mono).
    if (!recording_.load(std::memory_order_relaxed)) return oboe::DataCallbackResult::Continue;
    if (firstInput_.exchange(false)) {
        recordStartPos_.store(playing_.load() ? playhead_.load() : -1);
    }
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
        if (playing_.load() || standaloneMetronome_) openOutputLocked();
    } else {
        input_.reset();
        if (recording_.load() && openInputLocked()) input_->requestStart();
    }
}

}  // namespace musico
