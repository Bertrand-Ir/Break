// Break audio engine — one Oboe low-latency stream that renders and mixes:
//   • keys bus   : TinySoundFont instance (main instrument ch0 + optional layer ch1) → reverb
//   • beat bus   : second TinySoundFont instance sharing the same samples (drum kit ch9,
//                  soundfont bass ch1) + a synthesized 808 / log-drum voice
//   • user track : an imported beat (int16 PCM, resampled on the fly)
//   • metronome  : synthesized click
// Mix: smart ducking of beats under the keys, master gain, peak limiter, optional recording tap.
#pragma once

#include <oboe/Oboe.h>
#include <android/asset_manager.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <memory>
#include <cstdint>
#include "dsp.h"

struct tsf;

namespace bkaudio {

constexpr int PPQ = 96;           // sequencer ticks per quarter note
constexpr int SUB = 32;           // render sub-block (≈0.7 ms at 48 kHz) = event resolution
constexpr int PATTERN_BAR = 4 * PPQ;

enum CmdType : uint8_t {
    C_NOTE_ON, C_NOTE_OFF, C_ALL_OFF, C_SUSTAIN, C_PRESET, C_LAYER, C_TUNING,
    C_BEAT_PLAY, C_BEAT_STOP, C_PANIC
};

struct Cmd {
    CmdType type;
    int a, b;
    float f;
};

// Bounded MPSC-by-mutex / SPSC-lock-free-consumer command queue.
class CmdQueue {
    static constexpr uint32_t N = 1024;
    Cmd buf_[N];
    std::atomic<uint32_t> head_{0}, tail_{0};
    std::mutex pushLock_;
public:
    bool push(const Cmd &c) {
        std::lock_guard<std::mutex> g(pushLock_);
        const uint32_t h = head_.load(std::memory_order_relaxed);
        if (h - tail_.load(std::memory_order_acquire) >= N) return false;
        buf_[h % N] = c;
        head_.store(h + 1, std::memory_order_release);
        return true;
    }
    bool pop(Cmd &out) {
        const uint32_t t = tail_.load(std::memory_order_relaxed);
        if (t == head_.load(std::memory_order_acquire)) return false;
        out = buf_[t % N];
        tail_.store(t + 1, std::memory_order_release);
        return true;
    }
};

struct PatternEvent {
    int tick;        // position inside the pattern
    uint8_t kind;    // 0 = drum, 1 = bass
    int8_t note;     // drum: GM note; bass: semitone offset from root
    uint8_t vel;     // 1..127
    uint8_t glide;   // bass slide into this note
    int len;         // ticks
};

struct Pattern {
    int lengthTicks = PATTERN_BAR;
    int kit = 0;          // GM drum kit program (bank 128)
    int bassMode = 0;     // 0 long 808, 1 punchy 808, 2 log drum, 3 soundfont bass
    int bassPreset = 33;  // GM program for soundfont bass mode
    std::vector<PatternEvent> events; // sorted by tick
};

struct UserTrack {
    std::vector<int16_t> pcm; // interleaved stereo
    int frames = 0;
    int rate = 44100;
    float gain = 1.f;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    ~Engine() override;

    bool load(AAssetManager *am, const char *assetName);
    bool start();
    void stop();
    bool isLoaded() const { return keys_ != nullptr; }

    // ── control (any thread) ──
    void push(CmdType t, int a = 0, int b = 0, float f = 0.f) { cmds_.push({t, a, b, f}); }
    void setPattern(Pattern *p);        // takes ownership
    void beginUserTrack(int framesHint, int rate);
    void appendUserTrack(const int16_t *stereo, int frames);
    void commitUserTrack(float gain);
    void clearUserTrack();
    void collectGarbage();
    int readRecording(int16_t *dst, int max) { return recRing_.pop(dst, max); }
    int sampleRate() const { return sampleRate_; }
    double latencyMs();

    // ── parameters (lock-free) ──
    std::atomic<float> keysVol{0.9f}, beatVol{0.8f}, userVol{0.85f}, metroVol{0.7f}, masterVol{1.f};
    std::atomic<float> reverbAmt{0.22f}, layerMix{0.55f}, tuningSemis{0.f};
    std::atomic<bool> smartMix{true};
    std::atomic<int> transpose{0};
    std::atomic<float> bpm{90.f};
    std::atomic<int> tsNum{4}, tsDen{4};
    std::atomic<bool> metroOn{false};
    std::atomic<int> metroSound{0};
    std::atomic<int> beatKey{0};
    std::atomic<bool> bassOn{true};
    std::atomic<bool> userPlaying{false}, userLoop{true};
    std::atomic<float> userSeek{-1.f};
    std::atomic<bool> recording{false};

    // ── status for the UI ──
    std::atomic<int> uiBeat{-1};
    std::atomic<float> uiUserPos{0.f};
    std::atomic<float> uiLevel{0.f};
    std::atomic<bool> uiBeatPlaying{false};

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t frames) override;
    void onErrorAfterClose(oboe::AudioStream *s, oboe::Result r) override;

private:
    bool openStream();
    void configureForRate(int sr);
    void drainCommands();
    void swapPending();
    void renderSub(float *out, int n);
    void transportStep(int n);
    void triggerEvent(const PatternEvent &e, double framesPerTick);
    void scheduleOff(int ch, int note, int64_t atFrame);
    void processOffs();

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamLock_;
    bool wantRunning_ = false;
    int sampleRate_ = 48000;
    int configuredRate_ = 0;

    tsf *keys_ = nullptr;
    tsf *beat_ = nullptr;
    CmdQueue cmds_;

    // keys state (audio thread)
    int playing_[128];
    bool layerOn_ = false;

    // transport (audio thread)
    bool running_ = false;
    double tick_ = 0.0;
    bool beatActive_ = false;
    bool countIn_ = false;
    double beatStart_ = 0.0;
    int64_t frameClock_ = 0;
    struct Off { int64_t at; int16_t ch; int16_t note; bool on; };
    Off offs_[96]{};

    Pattern *pattern_ = nullptr;
    std::atomic<Pattern *> pendingPattern_{nullptr};
    std::atomic<Pattern *> retiredPattern_{nullptr};

    UserTrack *building_ = nullptr;   // owned by control thread while loading
    UserTrack *track_ = nullptr;      // owned by audio thread
    std::atomic<UserTrack *> pendingTrack_{nullptr};
    std::atomic<UserTrack *> retiredTrack_{nullptr};
    std::atomic<bool> clearTrack_{false};
    double trackPos_ = 0.0;

    Bass808 bass_;
    Click click_;
    Reverb reverb_;
    SampleRing recRing_;

    float keysBuf_[SUB * 2]{};
    float beatBuf_[SUB * 2]{};

    // smoothed gains + dynamics
    float gKeys_ = 0.9f, gBeat_ = 0.8f, gUser_ = 0.85f, gMetro_ = 0.7f, gMaster_ = 1.f, gRev_ = 0.2f;
    float duckEnv_ = 0.f, limGain_ = 1.f, levelEnv_ = 0.f;
    float atkCoef_ = 0.f, relCoef_ = 0.f, limRel_ = 0.f, smooth_ = 0.f, levelRel_ = 0.f;
};

} // namespace bkaudio
