#include "engine.h"
#include "tsf.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <thread>

#define LOG_TAG "BreakEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace bkaudio {

static inline int imod(long a, int b) { int r = (int) (a % b); return r < 0 ? r + b : r; }

Engine::~Engine() {
    stop();
    collectGarbage();
    delete pattern_;
    delete pendingPattern_.exchange(nullptr);
    delete track_;
    delete pendingTrack_.exchange(nullptr);
    delete building_;
    delete[] loopData_.exchange(nullptr);
    if (beat_) tsf_close(beat_);
    if (keys_) tsf_close(keys_);
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading & stream management
// ─────────────────────────────────────────────────────────────────────────────
bool Engine::load(AAssetManager *am, const char *assetName) {
    if (keys_) return true;
    AAsset *asset = AAssetManager_open(am, assetName, AASSET_MODE_BUFFER);
    if (!asset) { LOGE("soundfont asset %s missing", assetName); return false; }
    const void *buf = AAsset_getBuffer(asset);
    const off_t len = AAsset_getLength(asset);
    keys_ = buf ? tsf_load_memory(buf, (int) len) : nullptr;
    AAsset_close(asset);
    if (!keys_) { LOGE("soundfont parse failed"); return false; }

    beat_ = tsf_copy(keys_);
    tsf_set_max_voices(keys_, 160);
    tsf_set_max_voices(beat_, 96);

    // Pre-create every channel we use so nothing allocates on the audio thread.
    tsf_channel_set_bank_preset(keys_, 0, 0, 0);
    tsf_channel_set_bank_preset(keys_, 1, 0, 48);
    tsf_channel_set_volume(keys_, 1, layerMix.load());
    tsf_channel_set_bank_preset(keys_, 2, 0, 0);   // instrument preview channel
    tsf_channel_set_bank_preset(beat_, 9, 128, 0);
    tsf_channel_set_presetnumber(beat_, 1, 33, 0);

    for (int &p : playing_) p = -1;
    recRing_.init(1u << 20);
    configureForRate(48000);
    LOGI("soundfont loaded: %d presets", tsf_get_presetcount(keys_));
    return true;
}

void Engine::configureForRate(int sr) {
    sampleRate_ = sr;
    if (configuredRate_ == sr) return;
    configuredRate_ = sr;
    tsf_set_output(keys_, TSF_STEREO_INTERLEAVED, sr, -3.0f);
    tsf_set_output(beat_, TSF_STEREO_INTERLEAVED, sr, -1.5f);
    reverb_.init(sr);
    bass_.setSampleRate((float) sr);
    click_.setSampleRate((float) sr);
    atkCoef_ = onePoleCoef(0.008f, (float) sr);
    relCoef_ = onePoleCoef(0.35f, (float) sr);
    limRel_ = onePoleCoef(0.15f, (float) sr);
    levelRel_ = onePoleCoef(0.25f, (float) sr);
    smooth_ = onePoleCoef(0.025f, (float) sr / SUB);
}

bool Engine::openStream() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        b.setSharingMode(oboe::SharingMode::Shared);
        r = b.openStream(stream_);
    }
    if (r != oboe::Result::OK) { LOGE("openStream failed: %s", oboe::convertToText(r)); stream_.reset(); return false; }

    configureForRate(stream_->getSampleRate());
    // Two bursts = the sweet spot between latency and glitch-free playback.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    r = stream_->requestStart();
    LOGI("stream: %d Hz, burst %d, exclusive=%d", stream_->getSampleRate(), stream_->getFramesPerBurst(),
         stream_->getSharingMode() == oboe::SharingMode::Exclusive);
    return r == oboe::Result::OK;
}

bool Engine::start() {
    std::lock_guard<std::mutex> g(streamLock_);
    wantRunning_ = true;
    if (!keys_) return false;
    if (stream_) return true;
    return openStream();
}

void Engine::stop() {
    std::lock_guard<std::mutex> g(streamLock_);
    wantRunning_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    // Audio thread is gone — safe to reset synth state directly.
    if (keys_) {
        tsf_note_off_all(keys_);
        tsf_channel_set_sustain(keys_, 0, 0);
        tsf_channel_set_sustain(keys_, 1, 0);
    }
    if (beat_) tsf_note_off_all(beat_);
    for (int &p : playing_) p = -1;
    for (auto &t : pedalOff_) t = 0;
    pedalPending_ = 0;
    beatActive_ = false; running_ = false; tick_ = 0.0;
    userPlaying = false; uiBeatPlaying = false; uiBeat = -1;
    bass_.kill();
    // Looper: a take in progress is kept, playback pauses.
    if (loopState_ == L_REC) { loopLen_ = loopRec_; loopPos_ = 0; loopState_ = loopLen_ > sampleRate_ / 10 ? L_STOP : L_EMPTY; }
    else if (loopState_ == L_PLAY || loopState_ == L_DUB || loopState_ == L_ARMPLAY) loopState_ = L_STOP;
    else if (loopState_ == L_ARMED) loopState_ = L_EMPTY;
    loopTarget_ = -1;
    uiLoopState = loopState_;
    Cmd c; while (cmds_.pop(c)) {}
}

void Engine::prepareLooper(int seconds) {
    if (loopData_.load(std::memory_order_acquire)) return;
    const int64_t frames = (int64_t) seconds * std::max(sampleRate_, 48000);
    auto *buf = new int16_t[frames * 2]();
    loopCap_ = frames;
    loopData_.store(buf, std::memory_order_release);
}

void Engine::onErrorAfterClose(oboe::AudioStream *, oboe::Result r) {
    LOGI("stream closed: %s — reopening", oboe::convertToText(r));
    // Headphones (un)plugged, BT switch, etc. Reopen on a fresh thread.
    std::thread([this] {
        std::lock_guard<std::mutex> g(streamLock_);
        stream_.reset();
        if (wantRunning_) openStream();
    }).detach();
}

double Engine::latencyMs() {
    std::lock_guard<std::mutex> g(streamLock_);
    if (!stream_) return -1.0;
    auto r = stream_->calculateLatencyMillis();
    if (r) return r.value();
    return stream_->getBufferSizeInFrames() * 1000.0 / std::max(1, stream_->getSampleRate());
}

// ─────────────────────────────────────────────────────────────────────────────
// Cross-thread ownership hand-offs
// ─────────────────────────────────────────────────────────────────────────────
void Engine::setPattern(Pattern *p) {
    delete retiredPattern_.exchange(nullptr);
    delete pendingPattern_.exchange(p);
}

void Engine::beginUserTrack(int framesHint, int rate) {
    delete building_;
    building_ = new UserTrack();
    building_->rate = rate;
    building_->pcm.reserve((size_t) std::max(0, framesHint) * 2 + 4096);
}

void Engine::appendUserTrack(const int16_t *stereo, int frames) {
    if (!building_) return;
    building_->pcm.insert(building_->pcm.end(), stereo, stereo + frames * 2);
}

void Engine::commitUserTrack(float gain) {
    if (!building_) return;
    building_->frames = (int) (building_->pcm.size() / 2);
    building_->gain = gain;
    if (building_->frames < 2) { delete building_; building_ = nullptr; return; }
    delete retiredTrack_.exchange(nullptr);
    delete pendingTrack_.exchange(building_);
    building_ = nullptr;
}

void Engine::clearUserTrack() {
    userPlaying = false;
    clearTrack_ = true;
}

void Engine::collectGarbage() {
    delete retiredPattern_.exchange(nullptr);
    delete retiredTrack_.exchange(nullptr);
}

// ─────────────────────────────────────────────────────────────────────────────
// Audio thread
// ─────────────────────────────────────────────────────────────────────────────
void Engine::drainCommands() {
    Cmd c;
    while (cmds_.pop(c)) {
        switch (c.type) {
            case C_NOTE_ON: {
                const int key = c.a & 127;
                if (playing_[key] >= 0) {
                    tsf_channel_note_off(keys_, 0, playing_[key]);
                    tsf_channel_note_off(keys_, 1, playing_[key]);
                }
                const int n = std::clamp(key + transpose.load(std::memory_order_relaxed), 0, 127);
                if (pedalOff_[n]) releasePedalNote(n);
                tsf_channel_note_on(keys_, 0, n, c.f);
                if (layerOn_) tsf_channel_note_on(keys_, 1, n, c.f);
                playing_[key] = n;
                break;
            }
            case C_NOTE_OFF: {
                const int key = c.a & 127;
                const int n = playing_[key];
                if (n >= 0) {
                    const float ped = pedalSec.load(std::memory_order_relaxed);
                    if (ped > 0.f) {
                        // Pedal down: let it ring a while longer, then release naturally.
                        if (!pedalOff_[n]) pedalPending_++;
                        pedalOff_[n] = std::max<int64_t>(1, frameClock_ + (int64_t) (ped * sampleRate_));
                    } else {
                        tsf_channel_note_off(keys_, 0, n);
                        tsf_channel_note_off(keys_, 1, n);
                    }
                    playing_[key] = -1;
                }
                break;
            }
            case C_ALL_OFF:
                tsf_channel_note_off_all(keys_, 0);
                tsf_channel_note_off_all(keys_, 1);
                for (int &p : playing_) p = -1;
                for (auto &t : pedalOff_) t = 0;
                pedalPending_ = 0;
                break;
            case C_SUSTAIN:
                tsf_channel_set_sustain(keys_, 0, c.a);
                tsf_channel_set_sustain(keys_, 1, c.a);
                break;
            case C_PRESET:
                if (!tsf_channel_set_bank_preset(keys_, 0, c.a, c.b))
                    tsf_channel_set_presetnumber(keys_, 0, c.b, 0);
                break;
            case C_LAYER:
                if (c.a < 0) {
                    layerOn_ = false;
                    tsf_channel_note_off_all(keys_, 1);
                } else {
                    if (!tsf_channel_set_bank_preset(keys_, 1, c.a, c.b))
                        tsf_channel_set_presetnumber(keys_, 1, c.b, 0);
                    layerOn_ = true;
                }
                break;
            case C_TUNING:
                tsf_channel_set_tuning(keys_, 0, c.f);
                tsf_channel_set_tuning(keys_, 1, c.f);
                tsf_channel_set_tuning(keys_, 2, c.f);
                break;
            case C_PREVIEW_PRESET:
                tsf_channel_sounds_off_all(keys_, 2);
                if (!tsf_channel_set_bank_preset(keys_, 2, c.a, c.b))
                    tsf_channel_set_presetnumber(keys_, 2, c.b, 0);
                break;
            case C_PREVIEW_NOTE:
                if (c.f > 0.f) tsf_channel_note_on(keys_, 2, c.a & 127, c.f);
                else tsf_channel_note_off(keys_, 2, c.a & 127);
                break;
            case C_PREVIEW_STOP:
                tsf_channel_note_off_all(keys_, 2);
                break;
            case C_LOOP:
                loopAction(c.a);
                break;
            case C_BEAT_PLAY: {
                const int num = std::max(1, tsNum.load());
                const double clickT = PPQ * 4.0 / std::max(1, tsDen.load());
                if (running_) {
                    // Metronome already running → drop in on the next bar, no count-in needed.
                    const double barT = num * clickT;
                    beatStart_ = std::ceil(tick_ / barT) * barT;
                    countIn_ = false;
                } else {
                    running_ = true;
                    beatStart_ = 0.0;
                    countIn_ = c.a != 0;
                    tick_ = countIn_ ? -(num * clickT) : 0.0;
                }
                beatActive_ = true;
                uiBeatPlaying = true;
                break;
            }
            case C_BEAT_STOP:
                beatActive_ = false;
                countIn_ = false;
                bass_.release();
                tsf_channel_note_off_all(beat_, 1);
                uiBeatPlaying = false;
                break;
            case C_PANIC:
                tsf_channel_sounds_off_all(keys_, 0);
                tsf_channel_sounds_off_all(keys_, 1);
                tsf_channel_sounds_off_all(keys_, 2);
                tsf_channel_sounds_off_all(beat_, 9);
                tsf_channel_sounds_off_all(beat_, 1);
                bass_.kill();
                for (int &p : playing_) p = -1;
                for (auto &t : pedalOff_) t = 0;
                pedalPending_ = 0;
                break;
        }
    }
}

void Engine::swapPending() {
    if (retiredTrack_.load(std::memory_order_acquire)) return;
    if (clearTrack_.exchange(false)) {
        retiredTrack_.store(track_, std::memory_order_release);
        track_ = nullptr;
        trackPos_ = 0.0;
        return;
    }
    if (UserTrack *t = pendingTrack_.exchange(nullptr)) {
        retiredTrack_.store(track_, std::memory_order_release);
        track_ = t;
        trackPos_ = 0.0;
    }
}

void Engine::scheduleOff(int ch, int note, int64_t at) {
    Off *free = nullptr;
    for (Off &o : offs_) {
        if (o.on && o.ch == ch && o.note == note) { o.at = at; return; }
        if (!o.on && !free) free = &o;
    }
    if (free) { free->at = at; free->ch = (int16_t) ch; free->note = (int16_t) note; free->on = true; }
}

void Engine::releasePedalNote(int n) {
    tsf_channel_note_off(keys_, 0, n);
    tsf_channel_note_off(keys_, 1, n);
    pedalOff_[n] = 0;
    if (pedalPending_ > 0) pedalPending_--;
}

void Engine::processOffs() {
    if (pedalPending_ > 0) {
        const bool up = pedalSec.load(std::memory_order_relaxed) <= 0.f;
        for (int n = 0; n < 128; n++)
            if (pedalOff_[n] && (up || pedalOff_[n] <= frameClock_)) releasePedalNote(n);
    }
    for (Off &o : offs_) {
        if (o.on && o.at <= frameClock_) {
            tsf_channel_note_off(beat_, o.ch, o.note);
            o.on = false;
        }
    }
}

void Engine::triggerEvent(const PatternEvent &e, double fpt) {
    if (e.kind == 0) {
        tsf_channel_note_on(beat_, 9, e.note, e.vel / 127.f);
        scheduleOff(9, e.note, frameClock_ + (int64_t) (std::max(e.len, 48) * fpt));
        return;
    }
    if (!bassOn.load(std::memory_order_relaxed)) return;
    const int key = std::clamp(beatKey.load(std::memory_order_relaxed), 0, 11);
    const int root = key <= 5 ? 36 + key : 24 + key;   // keeps 808s in the F#1–F2 sweet spot
    const int note = std::clamp(root + e.note, 12, 96);
    const int64_t lenF = (int64_t) (std::max(e.len, 6) * fpt);
    if (pattern_->bassMode == 3) {
        tsf_channel_note_on(beat_, 1, note, e.vel / 127.f);
        scheduleOff(1, note, frameClock_ + lenF);
    } else {
        const float freq = 440.f * std::pow(2.f, (note - 69 + tuningSemis.load(std::memory_order_relaxed)) / 12.f);
        bass_.noteOn(freq, e.glide != 0, (int) lenF);
    }
}

// ── Looper ───────────────────────────────────────────────────────────────────
// One button drives it: record → (tap) loop plays → (tap) overdub → (tap) play …
// With a beat or metronome running, takes start on the next bar and are rounded to whole bars,
// so the loop stays locked to the groove.
void Engine::finishLoopRecording(bool thenPlay) {
    if (transportOn_ && framesPerBar_ > 1.0) {
        const int64_t bars = std::max<int64_t>(1, std::llround(loopRec_ / framesPerBar_));
        const int64_t target = std::min<int64_t>(loopCap_, (int64_t) (bars * framesPerBar_));
        if (loopRec_ >= target) {
            loopLen_ = target;
            loopPos_ = (loopRec_ - target) % std::max<int64_t>(1, target);
            loopState_ = thenPlay ? L_PLAY : L_STOP;
        } else {
            loopTarget_ = target;          // keep recording until the bar line
            loopThenPlay_ = thenPlay;
        }
        return;
    }
    loopLen_ = loopRec_;
    loopPos_ = 0;
    loopState_ = loopLen_ > sampleRate_ / 10 ? (thenPlay ? L_PLAY : L_STOP) : L_EMPTY;
}

void Engine::loopAction(int action) {
    if (!loopData_.load(std::memory_order_acquire)) return;
    if (action == LA_CLEAR) {
        loopState_ = L_EMPTY; loopLen_ = loopPos_ = loopRec_ = 0; loopTarget_ = -1;
        return;
    }
    const bool stopKey = action == LA_STOP;
    switch (loopState_) {
        case L_EMPTY:
            if (stopKey) break;
            if (transportOn_) loopState_ = L_ARMED;
            else { loopState_ = L_REC; loopRec_ = 0; }
            break;
        case L_ARMED: loopState_ = L_EMPTY; break;
        case L_REC:
            if (loopTarget_ < 0) finishLoopRecording(!stopKey);
            else loopThenPlay_ = !stopKey;
            break;
        case L_PLAY: loopState_ = stopKey ? L_STOP : L_DUB; break;
        case L_DUB: loopState_ = stopKey ? L_STOP : L_PLAY; break;
        case L_STOP:
            if (transportOn_) loopState_ = L_ARMPLAY;
            else { loopState_ = L_PLAY; loopPos_ = 0; }
            break;
        case L_ARMPLAY: loopState_ = L_STOP; break;
    }
}

void Engine::transportStep(int n) {
    const bool metro = metroOn.load(std::memory_order_relaxed);
    barCross_ = -1;
    if (!metro && !beatActive_) {
        transportOn_ = false;
        running_ = false;
        tick_ = 0.0;
        uiBeat.store(-1, std::memory_order_relaxed);
        // still allow a pattern to be swapped in while idle
        if (!retiredPattern_.load(std::memory_order_acquire)) {
            if (Pattern *p = pendingPattern_.exchange(nullptr)) {
                retiredPattern_.store(pattern_, std::memory_order_release);
                pattern_ = p;
                tsf_channel_set_bank_preset(beat_, 9, 128, p->kit);
                if (p->bassMode == 3) tsf_channel_set_presetnumber(beat_, 1, p->bassPreset, 0);
                else bass_.setMode(p->bassMode);
            }
        }
        return;
    }
    if (!running_) { running_ = true; tick_ = 0.0; }

    const double tpf = bpm.load(std::memory_order_relaxed) * PPQ / (60.0 * sampleRate_);
    const double fpt = 1.0 / tpf;
    const double t0 = tick_, t1 = t0 + n * tpf;
    transportOn_ = true;

    // ── pattern swap, quantized to the next bar so style changes land on the one ──
    if (pendingPattern_.load(std::memory_order_acquire) && !retiredPattern_.load(std::memory_order_acquire)) {
        bool swap = !beatActive_ || !pattern_;
        double boundary = -1.0;
        if (!swap) {
            const double rel0 = t0 - beatStart_, rel1 = t1 - beatStart_;
            if (rel0 < 0) swap = true;
            else {
                const double nb = std::ceil(rel0 / PATTERN_BAR) * PATTERN_BAR;
                if (nb < rel1) { swap = true; boundary = beatStart_ + nb; }
            }
        }
        if (swap) {
            if (Pattern *p = pendingPattern_.exchange(nullptr)) {
                retiredPattern_.store(pattern_, std::memory_order_release);
                pattern_ = p;
                tsf_channel_set_bank_preset(beat_, 9, 128, p->kit);
                if (p->bassMode == 3) tsf_channel_set_presetnumber(beat_, 1, p->bassPreset, 0);
                else bass_.setMode(p->bassMode);
                if (boundary >= 0) beatStart_ = boundary;   // new pattern starts from its bar 1
            }
        }
    }

    // ── metronome / count-in ──
    const int num = std::max(1, tsNum.load(std::memory_order_relaxed));
    const int den = std::max(1, tsDen.load(std::memory_order_relaxed));
    const double clickT = PPQ * 4.0 / den;
    {   // where does the next bar start? (the looper locks to it)
        const double barT = num * clickT;
        framesPerBar_ = barT * fpt;
        const double nb = std::ceil(t0 / barT) * barT;
        if (nb < t1) barCross_ = std::min(n - 1, std::max(0, (int) ((nb - t0) * fpt)));
    }
    const bool countingIn = beatActive_ && countIn_ && t0 < beatStart_;
    if (metro || countingIn) {
        click_.setType(metroSound.load(std::memory_order_relaxed));
        for (double k = std::ceil(t0 / clickT); k * clickT < t1; k += 1.0) {
            const int inBar = imod((long) k, num);
            const int accent = inBar == 0 ? 2 : ((den == 8 && num % 3 == 0 && inBar % 3 == 0) ? 1 : 0);
            click_.trigger(accent, (int) ((k * clickT - t0) * fpt));
        }
    }

    // ── beat pattern ──
    if (beatActive_ && pattern_ && !pattern_->events.empty() && t1 > beatStart_) {
        const auto &ev = pattern_->events;
        const double L = pattern_->lengthTicks;
        const double p0 = std::max(t0, beatStart_) - beatStart_;
        const double p1 = t1 - beatStart_;
        for (long m = (long) std::floor(p0 / L);; ++m) {
            const double base = m * L;
            if (base >= p1) break;
            const double lo = std::max(p0 - base, 0.0), hi = std::min(p1 - base, L);
            auto it = std::lower_bound(ev.begin(), ev.end(), lo,
                                       [](const PatternEvent &e, double v) { return e.tick < v; });
            for (; it != ev.end() && it->tick < hi; ++it) triggerEvent(*it, fpt);
        }
    }

    const long kb = (long) std::floor(t0 / clickT);
    uiBeat.store((countingIn ? (1 << 16) : 0) | (num << 8) | imod(kb, num), std::memory_order_relaxed);
    tick_ = t1;
}

void Engine::renderSub(float *out, int n) {
    transportStep(n);
    processOffs();

    tsf_render_float(keys_, keysBuf_, n, 0);
    tsf_render_float(beat_, beatBuf_, n, 0);

    // smoothed parameters (once per sub-block — no zipper noise)
    const bool smart = smartMix.load(std::memory_order_relaxed);
    gKeys_ += (keysVol.load(std::memory_order_relaxed) - gKeys_) * smooth_;
    gBeat_ += (beatVol.load(std::memory_order_relaxed) - gBeat_) * smooth_;
    gUser_ += (userVol.load(std::memory_order_relaxed) - gUser_) * smooth_;
    gMetro_ += (metroVol.load(std::memory_order_relaxed) - gMetro_) * smooth_;
    gMaster_ += (masterVol.load(std::memory_order_relaxed) - gMaster_) * smooth_;
    gRev_ += (reverbAmt.load(std::memory_order_relaxed) - gRev_) * smooth_;

    // user track
    UserTrack *tr = track_;
    bool userOn = tr && userPlaying.load(std::memory_order_relaxed);
    double step = 0.0;
    float uGain = 0.f;
    if (tr) {
        const float seek = userSeek.exchange(-1.f, std::memory_order_relaxed);
        if (seek >= 0.f) trackPos_ = std::clamp((double) seek, 0.0, 1.0) * (tr->frames - 1);
        step = (double) tr->rate / sampleRate_;
        uGain = (smart ? tr->gain : 1.f) * gUser_ * (1.f / 32768.f);
    }
    const bool loop = userLoop.load(std::memory_order_relaxed);
    gLoop_ += (loopVol.load(std::memory_order_relaxed) - gLoop_) * smooth_;
    int16_t *lbuf = loopData_.load(std::memory_order_acquire);
    if (!lbuf) loopState_ = L_EMPTY;
    if (loopState_ == L_ARMED && !transportOn_) { loopState_ = L_REC; loopRec_ = 0; }
    if (loopState_ == L_ARMPLAY && !transportOn_) { loopState_ = L_PLAY; loopPos_ = 0; }
    const bool rec = recording.load(std::memory_order_relaxed);

    for (int i = 0; i < n; i++) {
        float kl = keysBuf_[2 * i] * gKeys_, kr = keysBuf_[2 * i + 1] * gKeys_;

        // ducking detector runs on the dry keys
        const float kpk = std::max(std::fabs(kl), std::fabs(kr));
        duckEnv_ += (kpk - duckEnv_) * (kpk > duckEnv_ ? atkCoef_ : relCoef_);

        float rl, rr;
        reverb_.process(kl, kr, rl, rr);
        kl += rl * gRev_;
        kr += rr * gRev_;

        // ── looper: records the keys (with reverb), plays back underneath ──
        float ll = 0.f, lr = 0.f;
        if (loopState_ != L_EMPTY) {
            if (i == barCross_) {
                if (loopState_ == L_ARMED) { loopState_ = L_REC; loopRec_ = 0; }
                else if (loopState_ == L_ARMPLAY) { loopState_ = L_PLAY; loopPos_ = 0; }
            }
            if (loopState_ == L_REC) {
                if (loopRec_ < loopCap_) {
                    lbuf[2 * loopRec_] = (int16_t) (std::clamp(kl, -1.f, 1.f) * 32767.f);
                    lbuf[2 * loopRec_ + 1] = (int16_t) (std::clamp(kr, -1.f, 1.f) * 32767.f);
                    loopRec_++;
                }
                if (loopTarget_ > 0 && loopRec_ >= loopTarget_) {
                    loopLen_ = loopTarget_; loopPos_ = 0; loopTarget_ = -1;
                    loopState_ = loopThenPlay_ ? L_PLAY : L_STOP;
                } else if (loopRec_ >= loopCap_) {
                    loopTarget_ = -1;
                    loopLen_ = loopRec_; loopPos_ = 0; loopState_ = L_PLAY;
                }
            } else if ((loopState_ == L_PLAY || loopState_ == L_DUB) && loopLen_ > 0) {
                int16_t *f = &lbuf[2 * loopPos_];
                ll = f[0] * (1.f / 32768.f);
                lr = f[1] * (1.f / 32768.f);
                if (loopState_ == L_DUB) {
                    f[0] = (int16_t) (std::clamp(ll + kl, -1.f, 1.f) * 32767.f);
                    f[1] = (int16_t) (std::clamp(lr + kr, -1.f, 1.f) * 32767.f);
                }
                ll *= gLoop_; lr *= gLoop_;
                if (++loopPos_ >= loopLen_) loopPos_ = 0;
            }
        }

        const float b8 = bass_.process() * 0.6f;
        const float bl = (beatBuf_[2 * i] + b8) * gBeat_;
        const float br = (beatBuf_[2 * i + 1] + b8) * gBeat_;

        float ul = 0.f, ur = 0.f;
        if (userOn) {
            int i0 = (int) trackPos_;
            if (i0 >= tr->frames - 1) {
                if (loop) { trackPos_ -= (tr->frames - 1); i0 = (int) trackPos_; }
                else { userOn = false; userPlaying = false; trackPos_ = 0.0; i0 = -1; }
            }
            if (i0 >= 0) {
                const float fr = (float) (trackPos_ - i0);
                const int16_t *s = &tr->pcm[2 * i0];
                ul = (s[0] + (s[2] - s[0]) * fr) * uGain;
                ur = (s[1] + (s[3] - s[1]) * fr) * uGain;
                trackPos_ += step;
            }
        }

        // Smart mix: beats sit ~3 dB lower while you play, then breathe back.
        const float duck = smart ? 1.f - std::min(0.32f, duckEnv_ * 0.75f) : 1.f;
        const float m = click_.process() * gMetro_ * 0.55f;

        float L = (kl + ll + (bl + ul) * duck + m) * gMaster_;
        float R = (kr + lr + (br + ur) * duck + m) * gMaster_;

        // Peak limiter: instant attack, smooth release, -1 dBFS ceiling.
        const float pk = std::max(std::fabs(L), std::fabs(R));
        constexpr float ceiling = 0.89f;
        if (pk * limGain_ > ceiling) limGain_ = ceiling / pk;
        else limGain_ += (1.f - limGain_) * limRel_;
        L = std::clamp(L * limGain_, -1.f, 1.f);
        R = std::clamp(R * limGain_, -1.f, 1.f);

        out[2 * i] = L;
        out[2 * i + 1] = R;

        const float lv = std::max(std::fabs(L), std::fabs(R));
        levelEnv_ += (lv - levelEnv_) * (lv > levelEnv_ ? 0.5f : levelRel_);

        if (rec) {
            recRing_.push((int16_t) (L * 32767.f));
            recRing_.push((int16_t) (R * 32767.f));
        }
    }
    uiLoopState.store(loopState_, std::memory_order_relaxed);
    if (loopLen_ > 0) uiLoopPos.store((float) loopPos_ / (float) loopLen_, std::memory_order_relaxed);
    uiLoopLenSec.store((float) loopLen_ / sampleRate_, std::memory_order_relaxed);
    uiLoopRecSec.store((float) loopRec_ / sampleRate_, std::memory_order_relaxed);
    if (tr) uiUserPos.store((float) (trackPos_ / std::max(1, tr->frames)), std::memory_order_relaxed);
    frameClock_ += n;
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream *, void *data, int32_t frames) {
    float *out = static_cast<float *>(data);
    if (!keys_) {
        std::memset(out, 0, sizeof(float) * frames * 2);
        return oboe::DataCallbackResult::Continue;
    }
    drainCommands();
    swapPending();
    const float lm = layerMix.load(std::memory_order_relaxed);
    if (std::fabs(tsf_channel_get_volume(keys_, 1) - lm) > 0.001f) tsf_channel_set_volume(keys_, 1, lm);

    for (int done = 0; done < frames;) {
        const int n = std::min(SUB, frames - done);
        renderSub(out + done * 2, n);
        done += n;
    }
    uiLevel.store(levelEnv_, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

} // namespace bkaudio
