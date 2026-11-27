#pragma once

#include <cstdint>
#include <vector>

#include "mixer.h"

namespace musico {

// Trecho A/B que se repete. Só vale quando enabled e b > a.
struct LoopRegion {
    bool enabled = false;
    int64_t a = 0;
    int64_t b = 0;
    bool active() const { return enabled && b > a; }
};

// Avança `pos` por `delta` quadros na linha do tempo, voltando para A ao passar de B.
int64_t loopAdvance(int64_t pos, int64_t delta, const LoopRegion& loop);
double loopAdvance(double pos, double delta, const LoopRegion& loop);

// Quadros até a próxima volta para A (ou -1 se não vai voltar).
int64_t framesUntilWrap(int64_t pos, const LoopRegion& loop);

// Lê `numFrames` quadros estéreo da mixagem a partir de `start`, repetindo o trecho A/B.
// Nas emendas faz uma descida curta de volume (~2 ms) para não estalar.
// `out` é sobrescrito.
void readTimeline(const ProjectData& project, int64_t start, float* out, int numFrames, const LoopRegion& loop);

// Muda a velocidade sem mudar o tom (WSOLA: pedaços do áudio original, encaixados pelo
// trecho mais parecido e sobrepostos com janela). Tudo é alocado na criação: render() não
// aloca memória e pode rodar na thread de áudio.
class TimeStretcher {
public:
    static constexpr int kWindow = 2048;  // ~43 ms a 48 kHz
    static constexpr int kHop = kWindow / 2;
    static constexpr int kSearch = 384;   // procura do encaixe: ±8 ms

    TimeStretcher();

    // Recomeça a partir de `position` (início da reprodução, salto, troca de velocidade).
    void reset(const ProjectData& project, int64_t position, const LoopRegion& loop);

    // Gera `numFrames` quadros estéreo (sobrescreve `out`). Devolve o índice do primeiro
    // quadro depois de uma volta para A, ou -1.
    int render(const ProjectData& project, const LoopRegion& loop, double speed, float* out, int numFrames);

    // Posição na linha do tempo do próximo quadro de saída.
    int64_t position(const LoopRegion& loop) const;

private:
    void step(const ProjectData& project, const LoopRegion& loop, double speed);

    std::vector<float> window_;
    std::vector<float> accum_;    // estéreo, kWindow quadros
    std::vector<float> grain_;    // estéreo, kWindow quadros
    std::vector<float> ref_;      // estéreo, kHop quadros
    std::vector<float> cand_;     // estéreo, kHop + 2 * kSearch quadros
    std::vector<float> refMono_;
    std::vector<float> candMono_;
    std::vector<float> fifo_;     // estéreo, kHop quadros prontos
    int fifoRead_ = kHop;         // vazio
    double chunkNominal_ = 0.0;   // posição do primeiro quadro do fifo
    double chunkSpeed_ = 1.0;
    double nominal_ = 0.0;        // início nominal do último pedaço
    int64_t prevActual_ = 0;      // início real do último pedaço
    bool first_ = true;
};

}  // namespace musico
