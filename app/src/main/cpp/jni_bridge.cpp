// JNI surface for app.breakpiano.audio.NativeEngine (a Kotlin `object`).
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <algorithm>
#include "engine.h"

using namespace bkaudio;

static Engine *gEngine = nullptr;
static Engine &E() {
    if (!gEngine) gEngine = new Engine();
    return *gEngine;
}

#define JFN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_breakpiano_audio_NativeEngine_##name

JFN(jboolean, load)(JNIEnv *env, jobject, jobject assetManager, jstring asset) {
    AAssetManager *am = AAssetManager_fromJava(env, assetManager);
    const char *name = env->GetStringUTFChars(asset, nullptr);
    const bool ok = E().load(am, name);
    env->ReleaseStringUTFChars(asset, name);
    return ok;
}

JFN(jboolean, start)(JNIEnv *, jobject) { return E().start(); }
JFN(void, stop)(JNIEnv *, jobject) { E().stop(); }

// ── keys ──
JFN(void, noteOn)(JNIEnv *, jobject, jint key, jfloat vel) { E().push(C_NOTE_ON, key, 0, vel); }
JFN(void, noteOff)(JNIEnv *, jobject, jint key) { E().push(C_NOTE_OFF, key); }
JFN(void, allNotesOff)(JNIEnv *, jobject) { E().push(C_ALL_OFF); }
JFN(void, panic)(JNIEnv *, jobject) { E().push(C_PANIC); }
JFN(void, setSustain)(JNIEnv *, jobject, jboolean on) { E().push(C_SUSTAIN, on ? 1 : 0); }
JFN(void, setPedal)(JNIEnv *, jobject, jfloat seconds) { E().pedalSec = std::max(0.f, (float) seconds); }
JFN(void, setInstrument)(JNIEnv *, jobject, jint bank, jint program) { E().push(C_PRESET, bank, program); }
JFN(void, setLayer)(JNIEnv *, jobject, jint bank, jint program) { E().push(C_LAYER, bank, program); }
JFN(void, setLayerMix)(JNIEnv *, jobject, jfloat mix) { E().layerMix = mix; }
JFN(void, setTuning)(JNIEnv *, jobject, jfloat semis) {
    E().tuningSemis = semis;
    E().push(C_TUNING, 0, 0, semis);
}
JFN(void, setTranspose)(JNIEnv *, jobject, jint semis) { E().transpose = semis; }

// ── mix ──
JFN(void, setVolumes)(JNIEnv *, jobject, jfloat keys, jfloat beat, jfloat user, jfloat metro, jfloat master) {
    Engine &e = E();
    e.keysVol = keys; e.beatVol = beat; e.userVol = user; e.metroVol = metro; e.masterVol = master;
}
JFN(void, setReverb)(JNIEnv *, jobject, jfloat amount) { E().reverbAmt = amount; }
JFN(void, setSmartMix)(JNIEnv *, jobject, jboolean on) { E().smartMix = on; }

// ── transport ──
JFN(void, setTempo)(JNIEnv *, jobject, jfloat bpm) { E().bpm = std::clamp((float) bpm, 30.f, 260.f); }
JFN(void, setTimeSignature)(JNIEnv *, jobject, jint num, jint den) { E().tsNum = num; E().tsDen = den; }
JFN(void, setMetronome)(JNIEnv *, jobject, jboolean on, jint sound) { E().metroSound = sound; E().metroOn = on; }

JFN(void, setPattern)(JNIEnv *env, jobject, jint lengthTicks, jint kit, jint bassMode, jint bassPreset,
                      jintArray ticks, jbyteArray kinds, jbyteArray notes, jbyteArray vels,
                      jbyteArray glides, jintArray lens) {
    const jsize n = env->GetArrayLength(ticks);
    auto *p = new Pattern();
    p->lengthTicks = std::max(1, (int) lengthTicks);
    p->kit = kit; p->bassMode = bassMode; p->bassPreset = bassPreset;
    p->events.resize(n);
    jint *t = env->GetIntArrayElements(ticks, nullptr);
    jint *l = env->GetIntArrayElements(lens, nullptr);
    jbyte *k = env->GetByteArrayElements(kinds, nullptr);
    jbyte *no = env->GetByteArrayElements(notes, nullptr);
    jbyte *v = env->GetByteArrayElements(vels, nullptr);
    jbyte *g = env->GetByteArrayElements(glides, nullptr);
    for (jsize i = 0; i < n; i++) {
        p->events[i] = PatternEvent{t[i], (uint8_t) k[i], (int8_t) no[i],
                                    (uint8_t) std::clamp((int) (uint8_t) v[i], 1, 127), (uint8_t) g[i], l[i]};
    }
    env->ReleaseIntArrayElements(ticks, t, JNI_ABORT);
    env->ReleaseIntArrayElements(lens, l, JNI_ABORT);
    env->ReleaseByteArrayElements(kinds, k, JNI_ABORT);
    env->ReleaseByteArrayElements(notes, no, JNI_ABORT);
    env->ReleaseByteArrayElements(vels, v, JNI_ABORT);
    env->ReleaseByteArrayElements(glides, g, JNI_ABORT);
    std::stable_sort(p->events.begin(), p->events.end(),
                     [](const PatternEvent &a, const PatternEvent &b) { return a.tick < b.tick; });
    E().setPattern(p);
}
JFN(void, playBeat)(JNIEnv *, jobject, jboolean countIn) { E().push(C_BEAT_PLAY, countIn ? 1 : 0); }
JFN(void, stopBeat)(JNIEnv *, jobject) { E().push(C_BEAT_STOP); }
JFN(void, setBeatKey)(JNIEnv *, jobject, jint key) { E().beatKey = key; }
JFN(void, setBassEnabled)(JNIEnv *, jobject, jboolean on) { E().bassOn = on; }

// ── imported beat ──
JFN(void, beginUserTrack)(JNIEnv *, jobject, jint framesHint, jint rate) { E().beginUserTrack(framesHint, rate); }
JFN(void, appendUserTrack)(JNIEnv *env, jobject, jshortArray pcm, jint frames) {
    jshort *s = env->GetShortArrayElements(pcm, nullptr);
    E().appendUserTrack(reinterpret_cast<int16_t *>(s), frames);
    env->ReleaseShortArrayElements(pcm, s, JNI_ABORT);
}
JFN(void, commitUserTrack)(JNIEnv *, jobject, jfloat gain) { E().commitUserTrack(gain); }
JFN(void, clearUserTrack)(JNIEnv *, jobject) { E().clearUserTrack(); }
JFN(void, setUserTrackPlaying)(JNIEnv *, jobject, jboolean on) { E().userPlaying = on; }
JFN(void, setUserTrackLoop)(JNIEnv *, jobject, jboolean on) { E().userLoop = on; }
JFN(void, seekUserTrack)(JNIEnv *, jobject, jfloat f) { E().userSeek = f; }

// ── recording ──
JFN(void, setRecording)(JNIEnv *, jobject, jboolean on) { E().recording = on; }
JFN(jint, readRecording)(JNIEnv *env, jobject, jshortArray buf) {
    const jsize cap = env->GetArrayLength(buf);
    jshort *s = env->GetShortArrayElements(buf, nullptr);
    const int n = E().readRecording(reinterpret_cast<int16_t *>(s), cap);
    env->ReleaseShortArrayElements(buf, s, 0);
    return n;
}

// ── status ──
JFN(jint, getBeatStatus)(JNIEnv *, jobject) { return E().uiBeat.load(); }
JFN(jboolean, isBeatPlaying)(JNIEnv *, jobject) { return E().uiBeatPlaying.load(); }
JFN(jboolean, isUserTrackPlaying)(JNIEnv *, jobject) { return E().userPlaying.load(); }
JFN(jfloat, getUserTrackPosition)(JNIEnv *, jobject) { return E().uiUserPos.load(); }
JFN(jfloat, getLevel)(JNIEnv *, jobject) { return E().uiLevel.load(); }
JFN(jint, getSampleRate)(JNIEnv *, jobject) { return E().sampleRate(); }
JFN(jfloat, getLatencyMs)(JNIEnv *, jobject) { return (jfloat) E().latencyMs(); }
JFN(void, collectGarbage)(JNIEnv *, jobject) { E().collectGarbage(); }

// ── instrument preview (separate channel, never touches what you're playing) ──
JFN(void, previewProgram)(JNIEnv *, jobject, jint bank, jint program) { E().push(C_PREVIEW_PRESET, bank, program); }
JFN(void, previewNote)(JNIEnv *, jobject, jint note, jfloat vel) { E().push(C_PREVIEW_NOTE, note, 0, vel); }
JFN(void, previewStop)(JNIEnv *, jobject) { E().push(C_PREVIEW_STOP); }

// ── looper ──
JFN(void, looperAction)(JNIEnv *, jobject, jint action) {
    E().prepareLooper(120);
    E().push(C_LOOP, action);
}
JFN(void, setLoopVolume)(JNIEnv *, jobject, jfloat v) { E().loopVol = v; }
JFN(jint, getLooperState)(JNIEnv *, jobject) { return E().uiLoopState.load(); }
JFN(jfloat, getLooperPosition)(JNIEnv *, jobject) { return E().uiLoopPos.load(); }
JFN(jfloat, getLooperLength)(JNIEnv *, jobject) { return E().uiLoopLenSec.load(); }
JFN(jfloat, getLooperRecorded)(JNIEnv *, jobject) { return E().uiLoopRecSec.load(); }
