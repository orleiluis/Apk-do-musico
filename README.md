# APK do Músico

App Android para músicos: metrônomo, gravador, multipista, tablaturas (Guitar Pro) e navegador para aulas.

Plano completo: https://claude.ai/artifact/RBmWRELPHEGC6U6QhXAwqc

## O que o app faz

| Aba | Recursos |
| --- | --- |
| **Estúdio** | Projetos com até 8 pistas. Grava ouvindo as outras pistas e o clique, com compensação de latência. Importa MP3, M4A, FLAC, OGG e WAV. Move, corta, divide, duplica e apaga clipes; fades e ganho; volume, pan, mudo e solo; desfazer/refazer. Exporta a mixagem em WAV. |
| **Tabs** | Abre Guitar Pro (.gp, .gp3, .gp4, .gp5, .gpx), MusicXML e Capella com [alphaTab](https://www.alphatab.net). Toca com cursor, repete trecho selecionado, velocidade de 25% a 150%, metrônomo, contagem, mudo/solo por instrumento. |
| **Aulas** | Navegador para YouTube, Hotmart, Kiwify e qualquer site, com favoritos, vídeos com DRM e tela cheia. Modo estudo: vídeo em cima, metrônomo ou tablatura embaixo. Logins do Google abrem no navegador seguro do sistema. Em WebView antigo (tablets Fire, por exemplo) o app completa funções que faltam aos sites novos; **Detalhes** mostra o que a página não conseguiu carregar. |
| **Metrônomo** | 30 a 300 BPM, compassos 1/1 a 7/8, subdivisões, acento, tap tempo. Clique gerado no motor C++ com precisão de amostra. |
| **Gravador** | Áudio: gravação rápida em WAV com medidor de nível, para ouvir, renomear, compartilhar ou levar para o Estúdio. Vídeo: grava com a câmera (frontal ou traseira) e o microfone, com metrônomo opcional; no Android 10+ os vídeos vão para a galeria em Filmes/Apk do Musico. |
| **Timbre** | Abre o [Timbre Chord](https://github.com/orleiluis/timbre-chord): acordes ao vivo, grade de tempos, tom, BPM, cadeia de timbre e separação em 6 faixas. Precisa do Android 10 ou mais novo. |

## Como instalar no celular

1. Abra a aba **Actions** do repositório no GitHub e escolha a execução mais recente de "Build do APK".
2. Baixe o artefato `apk-do-musico-debug` (vem em .zip) e extraia o `.apk`.
3. No celular, abra o arquivo e permita "instalar apps desconhecidos" quando o Android pedir.

## Estrutura

| Pasta | O que tem |
| --- | --- |
| `app/.../multitrack` | Estúdio multipista (modelo, edição, importação, telas) |
| `app/.../tabs` + `assets/tabs` | Biblioteca e visualizador de tablaturas (alphaTab) |
| `app/.../browser` | Navegador de aulas e modo estudo |
| `app/.../ui` | Metrônomo, gravador e navegação |
| `core/audio/` | Motor de áudio em C++ com Oboe e a ponte Kotlin (`NativeAudio`) |
| `core/audio/src/test/cpp/` | Testes do motor que rodam no computador |
| `timbrechord/` | Timbre Chord (Java) como módulo; o modelo de separação é gerado no GitHub Actions a partir de `tools/separation` |

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

- A gravação acontece com o app aberto; gravar com a tela apagada não é suportado.
- Sem fone, o clique e as outras pistas são captados pelo microfone.
- O áudio fica na memória ao editar: projetos muito longos com muitas pistas podem pesar em celulares com pouca RAM.
- O navegador não baixa nem grava vídeos das aulas, e não toca vídeo com o app em segundo plano (termos do YouTube, Hotmart e Kiwify).

## Licenças de terceiros

- [Oboe](https://github.com/google/oboe) (Apache 2.0)
- [alphaTab](https://github.com/CoderLine/alphaTab) (MPL 2.0), fonte Bravura (SIL OFL 1.1) e SoundFont Sonivox (Apache 2.0), em `app/src/main/assets/alphatab/`
