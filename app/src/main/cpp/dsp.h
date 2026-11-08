// Small, allocation-free DSP building blocks used on the audio thread.
#pragma once
#include <vector>
#include <cmath>
#include <algorithm>
#include <atomic>
#include <cstdint>

namespace bkaudio {

constexpr float kTwoPi = 6.28318530717958647692f;

inline float onePoleCoef(float timeSec, float sr) {
    if (timeSec <= 0.f) return 1.f;
    return 1.f - std::exp(-1.f / (timeSec * sr));
}

// ── Freeverb (Jezar, public domain) ──────────────────────────────────────────
class Comb {
    std::vector<float> buf_; int idx_ = 0; float store_ = 0.f;
public:
    float feedback = 0.84f, damp1 = 0.2f, damp2 = 0.8f;
    void setSize(int n) { buf_.assign(std::max(1, n), 0.f); idx_ = 0; store_ = 0.f; }
    inline float process(float in) {
        float out = buf_[idx_];
        store_ = out * damp2 + store_ * damp1;
        buf_[idx_] = in + store_ * feedback;
        if (++idx_ >= (int) buf_.size()) idx_ = 0;
        return out;
    }
};

class Allpass {
    std::vector<float> buf_; int idx_ = 0;
public:
    void setSize(int n) { buf_.assign(std::max(1, n), 0.f); idx_ = 0; }
    inline float process(float in) {
        float b = buf_[idx_];
        buf_[idx_] = in + b * 0.5f;
        if (++idx_ >= (int) buf_.size()) idx_ = 0;
        return b - in;
    }
};

class Reverb {
    Comb cl_[8], cr_[8];
    Allpass al_[4], ar_[4];
public:
    void init(int sr) {
        static const int c[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int a[4] = {556, 441, 341, 225};
        const float s = sr / 44100.f;
        for (int i = 0; i < 8; i++) { cl_[i].setSize(int(c[i] * s)); cr_[i].setSize(int((c[i] + 23) * s)); }
        for (int i = 0; i < 4; i++) { al_[i].setSize(int(a[i] * s)); ar_[i].setSize(int((a[i] + 23) * s)); }
        setParams(0.84f, 0.42f);
    }
    void setParams(float room, float damp) {
        for (int i = 0; i < 8; i++) {
            cl_[i].feedback = cr_[i].feedback = room;
            cl_[i].damp1 = cr_[i].damp1 = damp;
            cl_[i].damp2 = cr_[i].damp2 = 1.f - damp;
        }
    }
    inline void process(float inL, float inR, float &outL, float &outR) {
        const float in = (inL + inR) * 0.015f;
        float l = 0.f, r = 0.f;
        for (int i = 0; i < 8; i++) { l += cl_[i].process(in); r += cr_[i].process(in); }
        for (int i = 0; i < 4; i++) { l = al_[i].process(l); r = ar_[i].process(r); }
        outL = l * 3.f; outR = r * 3.f;
    }
};

// ── 808 / log-drum bass voice ────────────────────────────────────────────────
// Sine core + 2nd harmonic, pitch "punch" envelope, glide, soft saturation.
class Bass808 {
    float sr_ = 48000.f;
    double phase_ = 0.0;
    float freq_ = 55.f, target_ = 55.f;
    float glideCoef_ = 1.f;
    float env_ = 0.f, atk_ = 0.f, atkInc_ = 0.f;
    float decayCoef_ = 1.f, releaseCoef_ = 1.f;
    float pitchEnv_ = 0.f, pitchEnvCoef_ = 1.f;
    int hold_ = 0;
    // style params
    float decaySec_ = 1.6f, punch_ = 1.0f, punchSec_ = 0.03f, drive_ = 1.8f, harm_ = 0.12f, glideSec_ = 0.07f;
public:
    void setSampleRate(float sr) { sr_ = sr; atkInc_ = 1.f / (0.002f * sr); }
    // mode: 0 = long 808, 1 = punchy short 808, 2 = log drum (amapiano)
    void setMode(int mode) {
        switch (mode) {
            case 1: decaySec_ = 0.45f; punch_ = 1.2f; punchSec_ = 0.025f; drive_ = 2.2f; harm_ = 0.15f; glideSec_ = 0.05f; break;
            case 2: decaySec_ = 0.28f; punch_ = 2.2f; punchSec_ = 0.018f; drive_ = 2.8f; harm_ = 0.32f; glideSec_ = 0.04f; break;
            default: decaySec_ = 1.7f; punch_ = 1.0f; punchSec_ = 0.03f; drive_ = 1.9f; harm_ = 0.12f; glideSec_ = 0.075f; break;
        }
    }
    void noteOn(float freq, bool glide, int holdFrames) {
        const bool sounding = env_ > 0.02f;
        target_ = freq;
        if (glide && sounding) {
            glideCoef_ = onePoleCoef(glideSec_, sr_);
        } else {
            freq_ = freq; glideCoef_ = 1.f;
            pitchEnv_ = punch_;
            pitchEnvCoef_ = std::exp(-1.f / (punchSec_ * sr_));
            env_ = 1.f;
            if (!sounding) { atk_ = 0.f; phase_ = 0.0; }
        }
        hold_ = holdFrames;
        decayCoef_ = std::exp(-1.f / (decaySec_ * sr_));
        releaseCoef_ = std::exp(-1.f / (0.06f * sr_));
    }
    void release() { hold_ = 0; }
    void kill() { env_ = 0.f; hold_ = 0; }
    inline float process() {
        if (env_ < 1e-5f) return 0.f;
        freq_ += (target_ - freq_) * glideCoef_;
        pitchEnv_ *= pitchEnvCoef_;
        const float f = freq_ * (1.f + pitchEnv_);
        phase_ += f / sr_;
        if (phase_ >= 1.0) phase_ -= 1.0;
        const float p = (float) phase_ * kTwoPi;
        float s = std::sin(p) + harm_ * std::sin(2.f * p);
        if (hold_ > 0) { hold_--; env_ *= decayCoef_; } else env_ *= releaseCoef_;
        if (atk_ < 1.f) atk_ = std::min(1.f, atk_ + atkInc_);
        const float x = s * env_ * atk_ * drive_;
        return std::tanh(x) / std::tanh(drive_);
    }
};

// ── Metronome click voice ────────────────────────────────────────────────────
class Click {
    float sr_ = 48000.f;
    float phase1_ = 0.f, phase2_ = 0.f, f1_ = 1000.f, f2_ = 0.f;
    float env_ = 0.f, decay_ = 0.999f, noiseEnv_ = 0.f, noiseDecay_ = 0.99f, level_ = 1.f;
    uint32_t rng_ = 22222u;
    int delay_ = 0;
    int type_ = 0;
public:
    void setSampleRate(float sr) { sr_ = sr; }
    void setType(int t) { type_ = t; }
    // accent: 0 normal, 1 secondary, 2 downbeat
    void trigger(int accent, int delayFrames) {
        delay_ = std::max(0, delayFrames);
        phase1_ = phase2_ = 0.f;
        switch (type_) {
            case 1: // woodblock
                f1_ = accent == 2 ? 1900.f : 1500.f; f2_ = f1_ * 1.48f;
                env_ = 1.f; decay_ = std::exp(-1.f / (0.018f * sr_));
                noiseEnv_ = 0.5f; noiseDecay_ = std::exp(-1.f / (0.004f * sr_));
                break;
            case 2: // soft beep
                f1_ = accent == 2 ? 880.f : 660.f; f2_ = 0.f;
                env_ = 1.f; decay_ = std::exp(-1.f / (0.07f * sr_));
                noiseEnv_ = 0.f;
                break;
            default: // classic click
                f1_ = accent == 2 ? 1760.f : (accent == 1 ? 1320.f : 1100.f); f2_ = f1_ * 2.01f;
                env_ = 1.f; decay_ = std::exp(-1.f / (0.022f * sr_));
                noiseEnv_ = 0.35f; noiseDecay_ = std::exp(-1.f / (0.002f * sr_));
                break;
        }
        level_ = accent == 2 ? 1.f : (accent == 1 ? 0.8f : 0.62f);
    }
    inline float process() {
        if (delay_ > 0) { delay_--; return 0.f; }
        if (env_ < 1e-4f) return 0.f;
        phase1_ += f1_ / sr_; if (phase1_ >= 1.f) phase1_ -= 1.f;
        float s = std::sin(phase1_ * kTwoPi);
        if (f2_ > 0.f) { phase2_ += f2_ / sr_; if (phase2_ >= 1.f) phase2_ -= 1.f; s = 0.75f * s + 0.25f * std::sin(phase2_ * kTwoPi); }
        rng_ = rng_ * 1664525u + 1013904223u;
        const float noise = ((rng_ >> 9) * (1.f / 4194304.f) - 1.f) * noiseEnv_;
        const float out = (s * env_ + noise) * level_;
        env_ *= decay_; noiseEnv_ *= noiseDecay_;
        return out;
    }
};

// ── Lock-free single-producer / single-consumer ring of int16 (recording tap) ─
class SampleRing {
    std::vector<int16_t> buf_;
    std::atomic<uint32_t> head_{0}, tail_{0};
    uint32_t mask_ = 0;
public:
    void init(uint32_t pow2) { buf_.assign(pow2, 0); mask_ = pow2 - 1; head_ = tail_ = 0; }
    void clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }
    inline void push(int16_t s) {
        const uint32_t h = head_.load(std::memory_order_relaxed);
        if (h - tail_.load(std::memory_order_acquire) >= buf_.size()) return; // full → drop
        buf_[h & mask_] = s;
        head_.store(h + 1, std::memory_order_release);
    }
    int pop(int16_t *dst, int max) {
        const uint32_t t = tail_.load(std::memory_order_relaxed);
        const uint32_t avail = head_.load(std::memory_order_acquire) - t;
        const int n = (int) std::min<uint32_t>(avail, (uint32_t) max);
        for (int i = 0; i < n; i++) dst[i] = buf_[(t + i) & mask_];
        tail_.store(t + n, std::memory_order_release);
        return n;
    }
};

} // namespace bkaudio
