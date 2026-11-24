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
