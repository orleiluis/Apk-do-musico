cmake_minimum_required(VERSION 3.22.1)
project(musico_audio CXX)

find_package(oboe REQUIRED CONFIG)

add_library(musico_audio SHARED
    metronome.cpp
    wav_writer.cpp
    audio_buffer.cpp
    mixer.cpp
    audio_engine.cpp
    jni_bridge.cpp
)

target_link_libraries(musico_audio oboe::oboe log)
