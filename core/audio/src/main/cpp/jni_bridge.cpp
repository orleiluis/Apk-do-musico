#include <jni.h>

#include <memory>
#include <string>

#include "audio_engine.h"

using musico::AudioEngine;

namespace {
AudioEngine& engine() {
    static AudioEngine instance;
    return instance;
}
}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_apkdomusico_audio_NativeAudio_##name

JNI_FN(jboolean, metronomeStart)(JNIEnv*, jobject) { return engine().startMetronome(); }
JNI_FN(void, metronomeStop)(JNIEnv*, jobject) { engine().stopMetronome(); }
JNI_FN(jboolean, metronomeIsRunning)(JNIEnv*, jobject) { return engine().metronome().isRunning(); }
JNI_FN(void, metronomeSetBpm)(JNIEnv*, jobject, jdouble bpm) { engine().metronome().setBpm(bpm); }
JNI_FN(void, metronomeSetBeatsPerBar)(JNIEnv*, jobject, jint beats) {
    engine().metronome().setBeatsPerBar(beats);
}
JNI_FN(void, metronomeSetSubdivision)(JNIEnv*, jobject, jint sub) {
    engine().metronome().setSubdivision(sub);
}
JNI_FN(void, metronomeSetAccent)(JNIEnv*, jobject, jboolean on) {
    engine().metronome().setAccent(on);
}
JNI_FN(void, metronomeSetVolume)(JNIEnv*, jobject, jfloat volume) {
    engine().metronome().setVolume(volume);
}
JNI_FN(jint, metronomeCurrentBeat)(JNIEnv*, jobject) { return engine().metronome().currentBeat(); }
JNI_FN(jint, metronomeCurrentTick)(JNIEnv*, jobject) { return engine().metronome().currentTick(); }
JNI_FN(void, metronomeSetSubdivisionVolume)(JNIEnv*, jobject, jfloat volume) {
    engine().metronome().setSubdivisionVolume(volume);
}

JNI_FN(jboolean, recordStart)(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    std::string p(chars);
    env->ReleaseStringUTFChars(path, chars);
    return engine().startRecording(p);
}
JNI_FN(jlong, recordStop)(JNIEnv*, jobject) { return engine().stopRecording(); }
JNI_FN(jboolean, recordIsActive)(JNIEnv*, jobject) { return engine().isRecording(); }
JNI_FN(jdouble, recordSeconds)(JNIEnv*, jobject) { return engine().recordedSeconds(); }
JNI_FN(jint, recordSampleRate)(JNIEnv*, jobject) { return engine().recordingSampleRate(); }
JNI_FN(jfloat, recordTakePeak)(JNIEnv*, jobject) { return engine().takeInputPeak(); }

// ---------- Multipista ----------

namespace {
std::string toString(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}
}  // namespace

JNI_FN(jint, engineSampleRate)(JNIEnv*, jobject) { return AudioEngine::kSampleRate; }
JNI_FN(jlong, sourceLoad)(JNIEnv* env, jobject, jstring path) {
    return engine().loadSource(toString(env, path));
}
JNI_FN(void, sourceForget)(JNIEnv* env, jobject, jstring path) {
    engine().forgetSource(toString(env, path));
}
JNI_FN(jfloatArray, sourcePeaks)(JNIEnv* env, jobject, jstring path, jint buckets) {
    const std::vector<float> peaks = engine().peaks(toString(env, path), buckets);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (arr && !peaks.empty())
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(peaks.size()), peaks.data());
    return arr;
}
JNI_FN(void, projectBegin)(JNIEnv*, jobject) { engine().beginProject(); }
JNI_FN(void, projectAddTrack)(JNIEnv*, jobject, jfloat volume, jfloat pan, jboolean mute, jboolean solo) {
    engine().addTrack(volume, pan, mute, solo);
}
JNI_FN(jboolean, projectAddClip)(JNIEnv* env, jobject, jint track, jstring path, jlong start, jlong offset,
                                 jlong length, jlong fadeIn, jlong fadeOut, jfloat gain) {
    return engine().addClip(track, toString(env, path), start, offset, length, fadeIn, fadeOut, gain);
}
JNI_FN(void, projectCommit)(JNIEnv*, jobject) { engine().commitProject(); }
JNI_FN(jboolean, transportPlay)(JNIEnv*, jobject, jlong from, jboolean click) {
    return engine().play(from, click);
}
JNI_FN(void, transportStop)(JNIEnv*, jobject) { engine().stopPlayback(); }
JNI_FN(jboolean, transportIsPlaying)(JNIEnv*, jobject) { return engine().isPlaying(); }
JNI_FN(jlong, transportPosition)(JNIEnv*, jobject) { return engine().position(); }

JNI_FN(void, transportSetLoop)(JNIEnv*, jobject, jboolean enabled, jlong a, jlong b) {
    engine().setLoop(enabled, a, b);
}

JNI_FN(void, transportSetSpeed)(JNIEnv*, jobject, jdouble speed) { engine().setSpeed(speed); }
JNI_FN(jboolean, exportMix)(JNIEnv* env, jobject, jstring path, jlong frames) {
    return engine().exportMix(toString(env, path), frames);
}
JNI_FN(jlong, recordStartPosition)(JNIEnv*, jobject) { return engine().recordStartPosition(); }
JNI_FN(jlong, recordRoundTripLatency)(JNIEnv*, jobject) { return engine().lastRoundTripLatency(); }
