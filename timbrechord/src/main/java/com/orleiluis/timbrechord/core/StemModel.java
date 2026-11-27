package com.orleiluis.timbrechord.core;

import java.nio.FloatBuffer;

/**
 * Rede de separação (htdemucs_6s sem a STFT). A cada trecho o separador escreve as entradas,
 * chama run() e lê as saídas, sempre nos mesmos buffers (nada é alocado por trecho).
 *
 * Formatos (float32, índices em ordem C):
 *  mix   [2][segment]                    forma de onda do trecho
 *  spec  [4][bins][frames]               STFT do trecho, complexo como canais: L.re, L.im, R.re, R.im
 *  freq  [sources][4][bins][frames]      ramo de frequência (espectro de cada fonte, mesmo arranjo)
 *  time  [sources][2][segment]           ramo do tempo (forma de onda de cada fonte)
 */
public interface StemModel extends AutoCloseable {
    FloatBuffer mixInput();

    FloatBuffer specInput();

    FloatBuffer freqOutput();

    FloatBuffer timeOutput();

    void run() throws Exception;

    @Override
    void close();
}
