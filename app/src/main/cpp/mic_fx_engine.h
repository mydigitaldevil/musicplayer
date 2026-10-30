#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "dsp.h"

namespace micfx {

// Must stay in sync with VoicePreset / AmbiencePreset in MicFxEngine.kt.
enum class Voice : int { None = 0, Baby, Chipmunk, Robot, Megaphone, DeepMonster, Whisper, Announcer };
enum class Ambience : int { None = 0, Stadium, Club, Bathroom, SpaceDelay };

// Lock-free single-producer (audio thread) / single-consumer (Kotlin drain thread) queue.
class SpscFloatQueue {
public:
    explicit SpscFloatQueue(size_t capacityPow2) : buf_(capacityPow2), mask_(capacityPow2 - 1) {}
    // Returns false (dropping the block) if the consumer has fallen behind.
    bool push(const float* src, size_t n);
    size_t pop(float* dst, size_t maxN);
    void clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::vector<float> buf_;
    const size_t mask_;
    std::atomic<size_t> head_{0}; // written by producer
    std::atomic<size_t> tail_{0}; // written by consumer
};

// Full-duplex mic -> FX -> headphones engine.
//
// The output stream drives timing via its data callback; inside it we read exactly as many
// frames from the (non-callback) input stream with a zero timeout. This is the standard
// Oboe low-latency duplex pattern and keeps both streams on one thread.
class MicFxEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    MicFxEngine();
    ~MicFxEngine() override;

    bool start(int32_t inputDeviceId, int32_t outputDeviceId);
    void stop();

    void setVoice(int v) { voice_.store(v, std::memory_order_relaxed); }
    void setAmbience(int a) { ambience_.store(a, std::memory_order_relaxed); }
    void setWet(float w) { wet_.store(std::clamp(w, 0.f, 1.f), std::memory_order_relaxed); }
    void setInputGain(float g) { gain_.store(std::clamp(g, 0.f, 4.f), std::memory_order_relaxed); }
    void setMonitorEnabled(bool on) { monitor_.store(on, std::memory_order_relaxed); }
    void setRecording(bool on);

    // Drains post-FX mono samples captured while recording. Called from a Kotlin IO thread.
    int32_t readRecorded(float* dst, int32_t maxSamples) {
        return static_cast<int32_t>(recordQueue_.pop(dst, static_cast<size_t>(maxSamples)));
    }
    int32_t sampleRate() const { return sampleRate_.load(); }
    double outputLatencyMs();
    bool isRunning() const { return running_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void prepareDsp(float sampleRate, int32_t maxFrames);
    void applyPresetsIfChanged();
    float processVoice(float x);

    std::mutex lifecycleMutex_; // guards open/close/restart only, never taken on the audio thread
    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;
    int32_t inputDeviceId_ = oboe::kUnspecified;
    int32_t outputDeviceId_ = oboe::kUnspecified;
    std::atomic<bool> running_{false};
    std::atomic<int32_t> sampleRate_{48000};

    // Control parameters (written by UI thread, read by audio thread).
    std::atomic<int> voice_{0};
    std::atomic<int> ambience_{0};
    std::atomic<float> wet_{0.35f};
    std::atomic<float> gain_{1.f};
    std::atomic<bool> monitor_{true};
    std::atomic<bool> recording_{false};

    // Audio-thread-only state.
    std::vector<float> inBuf_;
    std::vector<float> fxBuf_;
    int appliedVoice_ = -1;
    int appliedAmbience_ = -1;
    bool drainInput_ = true;
    float sr_ = 48000.f;
    float ringPhase_ = 0.f;

    PitchShifter pitch_;
    Reverb reverb_;
    Biquad hp_, lp_, bp_;
    EnvelopeFollower env_;
    WhiteNoise noise_;
    DelayLine robotComb_;

    SpscFloatQueue recordQueue_{1u << 18}; // ~5.4 s at 48 kHz of slack for the drain thread
};

} // namespace micfx
