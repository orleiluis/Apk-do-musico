#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "audio_buffer.h"
#include "metronome.h"
#include "mixer.h"
#include "playback.h"
#include "ring_buffer.h"
#include "wav_writer.h"

using namespace musico;

static int failures = 0;
#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            std::printf("FALHOU %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                \
        }                                                              \
    } while (0)

// Renderiza `seconds` em blocos de `block` quadros; devolve os índices dos quadros onde cada clique começou.
static std::vector<long> clickStarts(Metronome& m, int sampleRate, double seconds, int block) {
    std::vector<long> starts;
    std::vector<float> buf(block * 2);
    long frame = 0;
    const long total = static_cast<long>(seconds * sampleRate);
    int64_t lastCount = m.tickCount();
    while (frame < total) {
        // Renderiza quadro a quadro dentro do bloco para achar o início exato.
        for (int i = 0; i < block && frame + i < total; ++i) {
            float one[2] = {0, 0};
            m.render(one, 1, 2);
            if (m.tickCount() != lastCount) {
                starts.push_back(frame + i);
                lastCount = m.tickCount();
            }
        }
        frame += block;
    }
    return starts;
}

static void testTempoIsExact() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(120);
    m.start();
    auto starts = clickStarts(m, 48000, 60.0, 192);
    CHECK(starts.size() == 120);
    // A 120 BPM, um clique a cada 24000 quadros, sem deriva depois de 1 minuto.
    for (size_t i = 0; i < starts.size(); ++i) CHECK(starts[i] == static_cast<long>(i) * 24000);
}

static void testFractionalTempoDoesNotDrift() {
    Metronome m;
    m.setSampleRate(44100);
    m.setBpm(97);  // 27278,35... quadros por clique
    m.start();
    auto starts = clickStarts(m, 44100, 600.0, 256);  // 10 minutos
    const double period = 44100.0 * 60.0 / 97.0;
    CHECK(starts.size() == 970);
    double maxErr = 0;
    for (size_t i = 0; i < starts.size(); ++i)
        maxErr = std::fmax(maxErr, std::fabs(starts[i] - i * period));
    CHECK(maxErr <= 1.0);  // no máximo 1 quadro (0,02 ms) de erro em 10 minutos
}

static void testBeatsAndSubdivision() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(60);
    m.setBeatsPerBar(3);
    m.setSubdivision(2);
    m.start();
    std::vector<int> beats;
    float one[2];
    int lastBeat = -2;
    for (long i = 0; i < 48000L * 6; ++i) {
        one[0] = one[1] = 0;
        m.render(one, 1, 2);
        if (m.currentBeat() != lastBeat) {
            beats.push_back(m.currentBeat());
            lastBeat = m.currentBeat();
        }
    }
    // 6 segundos a 60 BPM em 3/4 = dois compassos: 0 1 2 0 1 2
    const std::vector<int> expected = {0, 1, 2, 0, 1, 2};
    CHECK(beats == expected);
    CHECK(m.tickCount() == 12);  // com colcheias, o dobro de cliques
    m.stop();
    CHECK(m.currentBeat() == -1);
}

static void testClampsAndSilenceWhenStopped() {
    Metronome m;
    m.setBpm(5);
    CHECK(m.bpm() == Metronome::kMinBpm);
    m.setBpm(1000);
    CHECK(m.bpm() == Metronome::kMaxBpm);
    m.setSubdivision(9);
    CHECK(m.subdivision() == 8);
    std::vector<float> buf(960, 0.0f);
    m.render(buf.data(), 480, 2);
    for (float s : buf) CHECK(s == 0.0f);
}

static void testAccentIsLouder() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(120);
    m.start();
    std::vector<float> buf(48000 * 2, 0.0f);  // 2 cliques: tempo 1 (acento) e tempo 2
    m.render(buf.data(), 48000, 1);
    float peakA = 0, peakB = 0;
    for (int i = 0; i < 2400; ++i) peakA = std::fmax(peakA, std::fabs(buf[i]));
    for (int i = 24000; i < 26400; ++i) peakB = std::fmax(peakB, std::fabs(buf[i]));
    CHECK(peakA > peakB);
    CHECK(peakA <= 1.0f);
}

static void testRingBuffer() {
    RingBuffer rb(8);
    float in[10] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    CHECK(rb.push(in, 10) == 8);  // só cabem 8
    float out[8];
    CHECK(rb.pop(out, 3) == 3);
    CHECK(out[0] == 1 && out[2] == 3);
    CHECK(rb.push(in, 3) == 3);  // dá a volta no buffer
    CHECK(rb.available() == 8);
    CHECK(rb.pop(out, 8) == 8);
    CHECK(out[4] == 8 && out[5] == 1 && out[7] == 3);
}

static void testWavWriter() {
    const std::string path = "test_out.wav";
    {
        WavWriter w;
        CHECK(w.open(path, 48000, 1));
        std::vector<float> s(48000);
        for (int i = 0; i < 48000; ++i) s[i] = (i % 2) ? 0.5f : -2.0f;  // -2 deve saturar
        w.write(s.data(), s.size());
        CHECK(w.framesWritten() == 48000);
        w.close();
    }
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    if (!f) return;
    std::vector<unsigned char> d(44 + 96000);
    CHECK(std::fread(d.data(), 1, d.size(), f) == d.size());
    std::fclose(f);
    auto u32 = [&](int o) { return d[o] | (d[o + 1] << 8) | (d[o + 2] << 16) | (unsigned(d[o + 3]) << 24); };
    CHECK(std::memcmp(d.data(), "RIFF", 4) == 0);
    CHECK(std::memcmp(d.data() + 8, "WAVE", 4) == 0);
    CHECK(u32(4) == 36 + 96000);
    CHECK(u32(24) == 48000);
    CHECK(u32(40) == 96000);
    const int16_t first = static_cast<int16_t>(d[44] | (d[45] << 8));
    const int16_t second = static_cast<int16_t>(d[46] | (d[47] << 8));
    CHECK(first == -32767);
    CHECK(second == 16384);
    std::remove(path.c_str());
}


static void testMetronomeStartAtAlignsToGrid() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(120);  // 24000 quadros por tempo
    m.startAt(30000);  // entre o tempo 1 (24000) e o tempo 2 (48000)
    float one[2];
    long firstTick = -1;
    for (long i = 0; i < 48000 && firstTick < 0; ++i) {
        one[0] = one[1] = 0;
        m.render(one, 1, 2);
        if (m.tickCount() == 1) firstTick = i;
    }
    CHECK(firstTick == 18000);  // 48000 - 30000
    CHECK(m.currentBeat() == 2);  // tempo 3 do compasso (índice 2)
}

static std::shared_ptr<AudioBuffer> constBuffer(int64_t frames, float value, int channels = 1) {
    WavData w;
    w.sampleRate = 48000;
    w.channels = channels;
    w.samples.assign(static_cast<size_t>(frames * channels), value);
    return makeBuffer(w, 48000);
}

static void testWavRoundTripAndResample() {
    const std::string path = "test_rt.wav";
    {
        WavWriter w;
        w.open(path, 44100, 2);
        std::vector<float> s(44100 * 2);
        for (int i = 0; i < 44100; ++i) {
            s[i * 2] = 0.25f;
            s[i * 2 + 1] = -0.5f;
        }
        w.write(s.data(), s.size());
    }
    WavData wav;
    CHECK(readWav(path, wav));
    CHECK(wav.sampleRate == 44100 && wav.channels == 2);
    CHECK(wav.samples.size() == 88200);
    auto buf = makeBuffer(wav, 48000);
    CHECK(buf->channels == 2);
    CHECK(buf->frames == 48000);  // 1 segundo continua 1 segundo
    CHECK(std::fabs(buf->sample(1000, 0) - 0.25f) < 0.001f);
    CHECK(std::fabs(buf->sample(1000, 1) + 0.5f) < 0.001f);
    WavData bad;
    CHECK(!readWav("nao_existe.wav", bad));
    std::remove(path.c_str());
}

static void testMixerPanMuteSoloFades() {
    ProjectData p;
    TrackData t;
    ClipData c;
    c.buffer = constBuffer(1000, 0.5f);
    c.start = 100;
    c.length = 1000;
    t.clips.push_back(c);
    p.tracks.push_back(t);
    CHECK(p.endFrame() == 1100);

    std::vector<float> out(2000 * 2, 0.0f);
    mixProject(p, 0, out.data(), 2000);
    CHECK(out[0] == 0.0f);                              // antes do clipe
    CHECK(std::fabs(out[500 * 2] - 0.5f) < 0.001f);     // pan no centro = ganho 1
    CHECK(std::fabs(out[500 * 2 + 1] - 0.5f) < 0.001f);
    CHECK(out[1200 * 2] == 0.0f);                       // depois do clipe

    p.tracks[0].pan = -1.0f;
    std::fill(out.begin(), out.end(), 0.0f);
    mixProject(p, 0, out.data(), 2000);
    CHECK(out[500 * 2 + 1] < 1e-4f);  // tudo à esquerda
    CHECK(out[500 * 2] > 0.6f);

    p.tracks[0].pan = 0.0f;
    p.tracks[0].clips[0].fadeIn = 100;
    p.tracks[0].clips[0].fadeOut = 100;
    std::fill(out.begin(), out.end(), 0.0f);
    mixProject(p, 0, out.data(), 2000);
    CHECK(std::fabs(out[150 * 2] - 0.25f) < 0.01f);   // metade do fade de entrada
    CHECK(std::fabs(out[1050 * 2] - 0.25f) < 0.01f);  // metade do fade de saída

    // Segunda pista em solo: a primeira some.
    TrackData t2;
    ClipData c2;
    c2.buffer = constBuffer(1000, 0.1f);
    c2.length = 1000;
    t2.clips.push_back(c2);
    t2.solo = true;
    p.tracks.push_back(t2);
    std::fill(out.begin(), out.end(), 0.0f);
    mixProject(p, 0, out.data(), 2000);
    CHECK(std::fabs(out[500 * 2] - 0.1f) < 0.001f);

    p.tracks[1].mute = true;
    std::fill(out.begin(), out.end(), 0.0f);
    mixProject(p, 0, out.data(), 2000);
    CHECK(out[500 * 2] == 0.0f);  // solo mudo e a outra fora do solo
}

static void testMixerOffsetAndBlockBoundaries() {
    WavData w;
    w.sampleRate = 48000;
    w.channels = 1;
    for (int i = 0; i < 1000; ++i) w.samples.push_back(i / 1000.0f);
    ProjectData p;
    TrackData t;
    ClipData c;
    c.buffer = makeBuffer(w, 48000);
    c.start = 0;
    c.offset = 500;  // começa no meio do áudio
    c.length = 400;
    t.clips.push_back(c);
    p.tracks.push_back(t);
    std::vector<float> a(64 * 2, 0.0f);
    mixProject(p, 10, a.data(), 64);  // bloco que não começa no zero
    CHECK(std::fabs(a[0] - 0.510f) < 0.001f);
}

static void testPeaks() {
    auto buf = constBuffer(1000, 0.5f);
    auto pk = computePeaks(*buf, 10);
    CHECK(pk.size() == 10);
    CHECK(std::fabs(pk[3] - 0.5f) < 0.001f);
}

static void testSubdivisionTicksAreAudibleAndCounted() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(60);
    m.setBeatsPerBar(2);
    m.setSubdivision(4);  // 4 cliques por tempo, 12000 quadros entre eles
    m.start();
    std::vector<float> buf(48000, 0.0f);
    std::vector<int> ticks;
    int last = -2;
    for (int i = 0; i < 48000; ++i) {
        m.render(&buf[i], 1, 1);
        if (m.currentTick() != last) {
            last = m.currentTick();
            ticks.push_back(last);
        }
    }
    const std::vector<int> expected = {0, 1, 2, 3};  // índice do clique dentro do compasso
    CHECK(ticks == expected);
    float subPeak = 0;
    for (int i = 12000; i < 14400; ++i) subPeak = std::fmax(subPeak, std::fabs(buf[i]));
    CHECK(subPeak > 0.3f);  // subdivisão bem audível
    m.setSubdivisionVolume(0.0f);
    std::vector<float> buf2(48000, 0.0f);
    m.render(buf2.data(), 48000, 1);  // segundo tempo: 1 clique de tempo + 3 subdivisões mudas
    float lateSub = 0;
    for (int i = 12000; i < 14400; ++i) lateSub = std::fmax(lateSub, std::fabs(buf2[i]));
    CHECK(lateSub < 1e-6f);
}

static ProjectData projectFrom(const WavData& w) {
    ProjectData p;
    TrackData t;
    ClipData c;
    c.buffer = makeBuffer(w, 48000);
    c.length = c.buffer->frames;
    t.clips.push_back(c);
    p.tracks.push_back(t);
    return p;
}

// Rampa: a amostra i vale i/65536, para descobrir de qual quadro veio cada saída.
static ProjectData rampProject(int frames) {
    WavData w;
    w.sampleRate = 48000;
    w.channels = 1;
    w.samples.resize(frames);
    for (int i = 0; i < frames; ++i) w.samples[i] = i / 65536.0f;
    return projectFrom(w);
}

static ProjectData sineProject(double freq, int frames) {
    WavData w;
    w.sampleRate = 48000;
    w.channels = 1;
    w.samples.resize(frames);
    for (int i = 0; i < frames; ++i) w.samples[i] = 0.5f * static_cast<float>(std::sin(6.283185307179586 * freq * i / 48000.0));
    return projectFrom(w);
}

static void testLoopMath() {
    LoopRegion loop{true, 1000, 2000};
    CHECK(loopAdvance(int64_t{500}, int64_t{400}, loop) == 900);
    CHECK(loopAdvance(int64_t{1900}, int64_t{150}, loop) == 1050);   // passou de B: volta para A
    CHECK(loopAdvance(int64_t{1900}, int64_t{2150}, loop) == 1050);  // várias voltas
    CHECK(loopAdvance(int64_t{2500}, int64_t{100}, loop) == 2600);   // depois de B não repete
    CHECK(framesUntilWrap(1990, loop) == 10);
    CHECK(framesUntilWrap(2500, loop) == -1);
    LoopRegion off{false, 1000, 2000};
    CHECK(loopAdvance(int64_t{1900}, int64_t{150}, off) == 2050);
    LoopRegion bad{true, 2000, 1000};  // B antes de A: ignora
    CHECK(!bad.active());
}

static void testReadTimelineRepeatsAB() {
    ProjectData p = rampProject(50000);
    LoopRegion loop{true, 10000, 20000};
    std::vector<float> out(35000 * 2);
    readTimeline(p, 15000, out.data(), 35000, loop);
    auto at = [&](int i) { return static_cast<int>(std::lround(out[i * 2] * 65536.0f)); };
    CHECK(std::abs(at(0) - 15000) <= 1);
    CHECK(std::abs(at(4000) - 19000) <= 1);
    CHECK(std::abs(at(6000) - 11000) <= 1);   // 5000 até B, depois A + 1000
    CHECK(std::abs(at(20000) - 15000) <= 1);  // segunda volta
    CHECK(std::abs(at(30000) - 15000) <= 1);  // terceira volta
    // Na emenda o volume cai a zero (sem estalo).
    CHECK(std::fabs(out[5000 * 2]) < 0.01f);
}

static int zeroCrossings(const std::vector<float>& st, int from, int to) {
    int n = 0;
    for (int i = from + 1; i < to; ++i) {
        if ((st[(i - 1) * 2] < 0.0f) != (st[i * 2] < 0.0f)) ++n;
    }
    return n;
}

static void testStretchKeepsPitchAndTracksPosition() {
    ProjectData p = sineProject(440.0, 48000 * 12);
    LoopRegion none;
    for (double speed : {0.5, 0.75, 1.25}) {
        TimeStretcher ts;
        ts.reset(p, 48000, none);
        std::vector<float> out(48000 * 2);
        // Blocos de tamanhos variados, como os da placa de som.
        int done = 0;
        int block = 192;
        while (done < 48000) {
            const int n = std::min(block, 48000 - done);
            ts.render(p, none, speed, out.data() + done * 2, n);
            done += n;
            block = block == 192 ? 960 : 192;
        }
        // Tom: 440 Hz = 880 passagens por zero por segundo, qualquer que seja a velocidade.
        const int zc = zeroCrossings(out, 4800, 48000);
        const double hz = zc / 2.0 / (43200.0 / 48000.0);
        CHECK(std::fabs(hz - 440.0) < 8.0);
        // Posição: 1 s de saída anda `speed` segundos na música.
        const double moved = static_cast<double>(ts.position(none) - 48000) / 48000.0;
        CHECK(std::fabs(moved - speed) < 0.03);
        // Sem buracos: o nível fica perto do da senoide (0,5 de pico).
        float peak = 0.0f, low = 1.0f;
        for (int w = 4800; w + 960 <= 48000; w += 960) {
            float pk = 0.0f;
            for (int i = w; i < w + 960; ++i) pk = std::fmax(pk, std::fabs(out[i * 2]));
            peak = std::fmax(peak, pk);
            low = std::fmin(low, pk);
        }
        CHECK(peak < 0.6f);
        CHECK(low > 0.35f);
    }
}

static void testStretchWithLoopStaysInsideAB() {
    ProjectData p = rampProject(60000);
    LoopRegion loop{true, 20000, 30000};
    TimeStretcher ts;
    ts.reset(p, 25000, loop);
    std::vector<float> out(512 * 2);
    int wraps = 0;
    for (int b = 0; b < 200; ++b) {
        if (ts.render(p, loop, 0.5, out.data(), 512) >= 0) ++wraps;
        const int64_t pos = ts.position(loop);
        CHECK(pos >= 20000 && pos < 30000);
    }
    // 102400 quadros a 0,5x = 51200 quadros de música: começa a 5000 de B, depois voltas de 10000.
    CHECK(wraps == 5);
}

static void testMetronomeRateFollowsSpeed() {
    Metronome m;
    m.setSampleRate(48000);
    m.setBpm(120.0);
    m.setRate(0.5);
    m.start();
    const auto starts = clickStarts(m, 48000, 4.0, 256);
    CHECK(starts.size() == 4);  // 120 BPM pela metade = 1 clique por segundo
    if (starts.size() >= 2) CHECK(std::abs((starts[1] - starts[0]) - 48000) <= 1);
}

int main() {
    testTempoIsExact();
    testFractionalTempoDoesNotDrift();
    testBeatsAndSubdivision();
    testClampsAndSilenceWhenStopped();
    testAccentIsLouder();
    testRingBuffer();
    testWavWriter();
    testMetronomeStartAtAlignsToGrid();
    testWavRoundTripAndResample();
    testMixerPanMuteSoloFades();
    testMixerOffsetAndBlockBoundaries();
    testPeaks();
    testSubdivisionTicksAreAudibleAndCounted();
    testLoopMath();
    testReadTimelineRepeatsAB();
    testStretchKeepsPitchAndTracksPosition();
    testStretchWithLoopStaysInsideAB();
    testMetronomeRateFollowsSpeed();
    if (failures == 0) std::printf("Todos os testes do motor passaram.\n");
    return failures == 0 ? 0 : 1;
}
