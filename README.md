# APK do Músico

App Android para músicos: metrônomo, gravador, multipista, tablaturas (Guitar Pro) e navegador para aulas.

Plano completo: https://claude.ai/artifact/RBmWRELPHEGC6U6QhXAwqc

## O que já funciona (Fase 1)

- **Metrônomo** de 30 a 300 BPM, compassos 2/4 a 7/8, subdivisões (colcheia, tercina, semicolcheia), acento no tempo 1, tap tempo e volume. O clique é gerado no motor C++ com precisão de amostra.
- **Gravador** em WAV (mono, 16 bits, 48 kHz), com medidor de nível, metrônomo junto (com fone), e lista de gravações para ouvir, renomear, compartilhar e apagar.

## Como instalar no celular

1. Abra a aba **Actions** do repositório no GitHub e escolha a execução mais recente de "Build do APK".
2. Baixe o artefato `apk-do-musico-debug` (vem em .zip) e extraia o `.apk`.
3. No celular, abra o arquivo e permita "instalar apps desconhecidos" quando o Android pedir.

## Estrutura

| Pasta | O que tem |
| --- | --- |
| `app/` | Telas em Jetpack Compose (metrônomo e gravador) |
| `core/audio/` | Motor de áudio em C++ com Oboe e a ponte Kotlin (`NativeAudio`) |
| `core/audio/src/test/cpp/` | Testes do motor que rodam no computador |

## Compilar no computador

Requer Android Studio (ou Android SDK + NDK) e JDK 17.

```
./gradlew assembleDebug
```

Testes do motor sem Android:

```
cmake -S core/audio/src/test/cpp -B build-tests && cmake --build build-tests && ./build-tests/engine_tests
```

## Limitações conhecidas

- A gravação acontece com o app aberto; gravar com a tela apagada fica para uma fase seguinte.
- Sem fone, o clique do metrônomo é captado pelo microfone.
