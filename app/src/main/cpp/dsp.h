// Real-time-safe DSP building blocks for the mic FX chain.
//
// Rules for everything in this file: all memory is allocated in prepare()/allocate(), which
// run on a control thread before the stream starts. process() never allocates, locks, logs
// or makes a syscall.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace micfx {

constexpr float kPi = 3.14159265358979f;

inline float softClip(float x) {
    // Cheap tanh approximation; keeps the monitor from ever slamming the user's ears.
    if (x > 3.f) return 1.f;
    if (x < -3.f) return -1.f;
    const float x2 = x * x;
    return x * (27.f + x2) / (27.f + 9.f * x2);
}

class DelayLine {
public:
    void allocate(int maxSamples) {
        buf_.assign(static_cast<size_t>(maxSamples) + 2, 0.f);
        write_ = 0;
    }
    void clear() { std::fill(buf_.begin(), buf_.end(), 0.f); }
    void push(float x) {
        buf_[write_] = x;
        if (++write_ == static_cast<int>(buf_.size())) write_ = 0;
    }
    // Fractional read `delay` samples behind the most recent write (linear interpolation).
    float read(float delay) const {
        const int size = static_cast<int>(buf_.size());
        float pos = static_cast<float>(write_ - 1) - delay;
        while (pos < 0.f) pos += static_cast<float>(size);
        const int i0 = static_cast<int>(pos);
        const int i1 = (i0 + 1) % size;
        const float frac = pos - static_cast<float>(i0);
        return buf_[i0] + (buf_[i1] - buf_[i0]) * frac;
    }
    int capacity() const { return static_cast<int>(buf_.size()) - 2; }

private:
    std::vector<float> buf_;
    int write_ = 0;
};

// RBJ cookbook biquad, transposed direct form II.
class Biquad {
public:
    enum class Type { LowPass, HighPass, BandPass };

    void set(Type type, float sampleRate, float freq, float q) {
        const float w0 = 2.f * kPi * std::min(freq, sampleRate * 0.45f) / sampleRate;
        const float cosw = std::cos(w0), alpha = std::sin(w0) / (2.f * q);
        float b0, b1, b2;
        switch (type) {
            case Type::LowPass:  b0 = (1 - cosw) / 2; b1 = 1 - cosw;    b2 = b0; break;
            case Type::HighPass: b0 = (1 + cosw) / 2; b1 = -(1 + cosw); b2 = b0; break;
            case Type::BandPass: b0 = alpha;          b1 = 0;           b2 = -alpha; break;
        }
        const float a0 = 1 + alpha;
        b0_ = b0 / a0; b1_ = b1 / a0; b2_ = b2 / a0;
        a1_ = -2 * cosw / a0; a2_ = (1 - alpha) / a0;
    }
    void reset() { z1_ = z2_ = 0.f; }
    float process(float x) {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

private:
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0, z1_ = 0, z2_ = 0;
};

// Two crossfaded read heads sweeping a short delay line ("rotating tape" shifter).
// ~40 ms window: low enough latency for live monitoring, at the cost of formant shift
// (which is what makes "chipmunk" / "monster" sound the way they do).
class PitchShifter {
public:
    void prepare(float sampleRate) {
        window_ = 0.040f * sampleRate;
        line_.allocate(static_cast<int>(window_) + 8);
        phase_ = 0.f;
    }
    void setRatio(float ratio) { ratio_ = ratio; }
    void reset() { line_.clear(); phase_ = 0.f; }
    float process(float x) {
        line_.push(x);
        if (ratio_ == 1.f) return x;
        phase_ += (1.f - ratio_) / window_;
        phase_ -= std::floor(phase_);
        float p2 = phase_ + 0.5f;
        p2 -= std::floor(p2);
        // Equal-power crossfade (sin/cos): the taps are mostly uncorrelated, so this keeps
        // loudness steady where amplitude-complementary Hann weights would dip ~3 dB.
        const float g1 = std::sin(kPi * phase_);
        const float g2 = std::sin(kPi * p2);
        return g1 * line_.read(2.f + phase_ * window_) + g2 * line_.read(2.f + p2 * window_);
    }

private:
    DelayLine line_;
    float window_ = 1.f, phase_ = 0.f, ratio_ = 1.f;
};

class FeedbackComb {
public:
    void allocate(int maxLen) { line_.allocate(maxLen); }
    void configure(int len, float feedback, float damp) {
        len_ = std::min(len, line_.capacity());
        feedback_ = feedback;
        damp_ = damp;
    }
    void clear() { line_.clear(); store_ = 0.f; }
    float process(float x) {
        const float out = line_.read(static_cast<float>(len_ - 1));
        store_ = out * (1.f - damp_) + store_ * damp_;
        line_.push(x + store_ * feedback_);
        return out;
    }

private:
    DelayLine line_;
    int len_ = 1;
    float feedback_ = 0.f, damp_ = 0.f, store_ = 0.f;
};

class Allpass {
public:
    void allocate(int maxLen) { line_.allocate(maxLen); }
    void configure(int len) { len_ = std::min(len, line_.capacity()); }
    void clear() { line_.clear(); }
    float process(float x) {
        const float delayed = line_.read(static_cast<float>(len_ - 1));
        line_.push(x + delayed * 0.5f);
        return delayed - x;
    }

private:
    DelayLine line_;
    int len_ = 1;
};

// Freeverb-style mono reverb (4 combs + 2 allpasses) with a pre-delay/echo tap.
class Reverb {
public:
    void prepare(float sampleRate) {
        sr_ = sampleRate;
        const float scale = sampleRate / 44100.f * kMaxSize;
        for (int i = 0; i < 4; ++i) combs_[i].allocate(static_cast<int>(kCombLen[i] * scale) + 1);
        for (int i = 0; i < 2; ++i) allpasses_[i].allocate(static_cast<int>(kApLen[i] * scale) + 1);
        echo_.allocate(static_cast<int>(sampleRate * kMaxEchoSec) + 1);
    }
    // size in (0, kMaxSize], feedback < 1, echoSec <= kMaxEchoSec.
    void configure(float size, float feedback, float damp, float echoSec, float echoFeedback) {
        const float scale = sr_ / 44100.f * std::min(size, kMaxSize);
        for (int i = 0; i < 4; ++i) combs_[i].configure(static_cast<int>(kCombLen[i] * scale), feedback, damp);
        for (int i = 0; i < 2; ++i) allpasses_[i].configure(static_cast<int>(kApLen[i] * scale));
        echoSamples_ = std::min(echoSec, kMaxEchoSec) * sr_;
        echoFeedback_ = echoFeedback;
    }
    void clear() {
        for (auto& c : combs_) c.clear();
        for (auto& a : allpasses_) a.clear();
        echo_.clear();
    }
    float process(float x) {
        float in = x;
        if (echoSamples_ > 1.f) {
            const float e = echo_.read(echoSamples_);
            echo_.push(x + e * echoFeedback_);
            in = x + e;
        }
        float out = 0.f;
        for (auto& c : combs_) out += c.process(in * 0.015f);
        for (auto& a : allpasses_) out = a.process(out);
        return out * 3.f + (in - x);
    }

private:
    static constexpr float kMaxSize = 2.0f;
    static constexpr float kMaxEchoSec = 1.0f;
    static constexpr int kCombLen[4] = {1116, 1188, 1277, 1356};
    static constexpr int kApLen[2] = {556, 441};
    FeedbackComb combs_[4];
    Allpass allpasses_[2];
    DelayLine echo_;
    float sr_ = 48000.f, echoSamples_ = 0.f, echoFeedback_ = 0.f;
};

class EnvelopeFollower {
public:
    void prepare(float sampleRate) {
        attack_ = std::exp(-1.f / (0.002f * sampleRate));
        release_ = std::exp(-1.f / (0.080f * sampleRate));
    }
    float process(float x) {
        const float a = std::fabs(x);
        const float c = a > env_ ? attack_ : release_;
        env_ = a + c * (env_ - a);
        return env_;
    }

private:
    float attack_ = 0.f, release_ = 0.f, env_ = 0.f;
};

class WhiteNoise {
public:
    float next() {
        state_ ^= state_ << 13; state_ ^= state_ >> 17; state_ ^= state_ << 5;
        return static_cast<float>(state_) * (2.f / 4294967295.f) - 1.f;
    }

private:
    uint32_t state_ = 0x9E3779B9u;
};

} // namespace micfx
