#include "mic_fx_engine.h"

#include <android/log.h>

#include <cstring>

#define LOG_TAG "MicFxEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace micfx {

// ---- SpscFloatQueue --------------------------------------------------------------------

bool SpscFloatQueue::push(const float* src, size_t n) {
    const size_t head = head_.load(std::memory_order_relaxed);
    const size_t tail = tail_.load(std::memory_order_acquire);
    if (buf_.size() - (head - tail) < n) return false;
    for (size_t i = 0; i < n; ++i) buf_[(head + i) & mask_] = src[i];
    head_.store(head + n, std::memory_order_release);
    return true;
}

size_t SpscFloatQueue::pop(float* dst, size_t maxN) {
    const size_t tail = tail_.load(std::memory_order_relaxed);
    const size_t head = head_.load(std::memory_order_acquire);
    const size_t n = std::min(maxN, head - tail);
    for (size_t i = 0; i < n; ++i) dst[i] = buf_[(tail + i) & mask_];
    tail_.store(tail + n, std::memory_order_release);
    return n;
}

// ---- Lifecycle ---------------------------------------------------------------------------

MicFxEngine::MicFxEngine() = default;

MicFxEngine::~MicFxEngine() { stop(); }

bool MicFxEngine::start(int32_t inputDeviceId, int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (running_) return true;
    inputDeviceId_ = inputDeviceId > 0 ? inputDeviceId : oboe::kUnspecified;
    outputDeviceId_ = outputDeviceId > 0 ? outputDeviceId : oboe::kUnspecified;
    if (!openStreams()) {
        closeStreams();
        return false;
    }
    running_ = true;
    return true;
}

void MicFxEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    running_ = false;
    closeStreams();
}

void MicFxEngine::setRecording(bool on) {
    if (on) recordQueue_.clear();
    recording_.store(on, std::memory_order_release);
}

double MicFxEngine::outputLatencyMs() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (!output_) return -1.0;
    auto result = output_->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

bool MicFxEngine::openStreams() {
    // Output first: it decides the sample rate and burst size; input then matches it.
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Game) // keeps it out of media ducking/focus fights with the player
            ->setDeviceId(outputDeviceId_)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (outBuilder.openStream(output_) != oboe::Result::OK) {
        LOGE("Failed to open output stream");
        return false;
    }

    const int32_t sampleRate = output_->getSampleRate();
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(oboe::InputPreset::VoicePerformance) // lowest-latency mic path (API 29+)
            ->setDeviceId(inputDeviceId_);
    if (inBuilder.openStream(input_) != oboe::Result::OK) {
        LOGE("Failed to open input stream");
        return false;
    }

    // Double-buffer on output: the lowest setting that survives typical scheduling jitter.
    output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);

    const int32_t maxFrames = std::max(output_->getBufferCapacityInFrames(), output_->getFramesPerBurst() * 4);
    prepareDsp(static_cast<float>(sampleRate), maxFrames);
    sampleRate_ = sampleRate;

    // Input must be running before output starts pulling from it.
    if (input_->requestStart() != oboe::Result::OK || output_->requestStart() != oboe::Result::OK) {
        LOGE("Failed to start streams");
        return false;
    }
    LOGI("Duplex started: %d Hz, burst %d, in=%s out=%s", sampleRate, output_->getFramesPerBurst(),
         input_->getSharingMode() == oboe::SharingMode::Exclusive ? "excl" : "shared",
         output_->getSharingMode() == oboe::SharingMode::Exclusive ? "excl" : "shared");
    return true;
}

void MicFxEngine::closeStreams() {
    if (output_) { output_->stop(); output_->close(); output_.reset(); }
    if (input_) { input_->stop(); input_->close(); input_.reset(); }
}

// Runs on Oboe's own thread after a disconnect (e.g. headset plugged/unplugged).
void MicFxEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGE("Stream closed with %s; restarting", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (!running_) return;
    closeStreams();
    if (!openStreams()) {
        closeStreams();
        running_ = false;
    }
}

void MicFxEngine::prepareDsp(float sampleRate, int32_t maxFrames) {
    sr_ = sampleRate;
    inBuf_.assign(static_cast<size_t>(maxFrames), 0.f);
    fxBuf_.assign(static_cast<size_t>(maxFrames), 0.f);
    pitch_.prepare(sampleRate);
    reverb_.prepare(sampleRate);
    env_.prepare(sampleRate);
    robotComb_.allocate(static_cast<int>(sampleRate * 0.02f));
    appliedVoice_ = appliedAmbience_ = -1;
    drainInput_ = true;
}

// ---- Audio thread ------------------------------------------------------------------------

void MicFxEngine::applyPresetsIfChanged() {
    const int v = voice_.load(std::memory_order_relaxed);
    if (v != appliedVoice_) {
        appliedVoice_ = v;
        pitch_.reset(); hp_.reset(); lp_.reset(); bp_.reset(); robotComb_.clear();
        switch (static_cast<Voice>(v)) {
            case Voice::Baby:
                pitch_.setRatio(1.45f);
                hp_.set(Biquad::Type::HighPass, sr_, 250.f, 0.707f);
                break;
            case Voice::Chipmunk:
                pitch_.setRatio(1.85f);
                break;
            case Voice::DeepMonster:
                pitch_.setRatio(0.62f);
                lp_.set(Biquad::Type::LowPass, sr_, 2500.f, 0.9f);
                break;
            case Voice::Megaphone:
                pitch_.setRatio(1.f);
                hp_.set(Biquad::Type::HighPass, sr_, 600.f, 0.9f);
                lp_.set(Biquad::Type::LowPass, sr_, 3000.f, 1.2f);
                break;
            case Voice::Announcer:
                pitch_.setRatio(0.94f);
                hp_.set(Biquad::Type::HighPass, sr_, 120.f, 0.707f);
                bp_.set(Biquad::Type::BandPass, sr_, 2500.f, 0.8f); // presence boost
                break;
            case Voice::Whisper:
                pitch_.setRatio(1.f);
                bp_.set(Biquad::Type::BandPass, sr_, 4000.f, 0.6f);
                break;
            case Voice::Robot:
            case Voice::None:
            default:
                pitch_.setRatio(1.f);
                break;
        }
    }

    const int a = ambience_.load(std::memory_order_relaxed);
    if (a != appliedAmbience_) {
        appliedAmbience_ = a;
        reverb_.clear();
        //                      size  feedback damp  echoSec echoFb
        switch (static_cast<Ambience>(a)) {
            case Ambience::Stadium:    reverb_.configure(2.0f, 0.88f, 0.35f, 0.18f, 0.25f); break;
            case Ambience::Club:       reverb_.configure(1.0f, 0.80f, 0.45f, 0.00f, 0.00f); break;
            case Ambience::Bathroom:   reverb_.configure(0.45f, 0.84f, 0.10f, 0.00f, 0.00f); break;
            case Ambience::SpaceDelay: reverb_.configure(1.6f, 0.75f, 0.50f, 0.45f, 0.55f); break;
            case Ambience::None:
            default:                   reverb_.configure(1.0f, 0.f, 0.f, 0.f, 0.f); break;
        }
    }
}

float MicFxEngine::processVoice(float x) {
    switch (static_cast<Voice>(appliedVoice_)) {
        case Voice::Baby:        return hp_.process(pitch_.process(x));
        case Voice::Chipmunk:    return pitch_.process(x);
        case Voice::DeepMonster: return lp_.process(pitch_.process(x)) * 1.3f;
        case Voice::Megaphone:   return softClip(lp_.process(hp_.process(x)) * 6.f) * 0.5f;
        case Voice::Announcer: {
            const float body = hp_.process(pitch_.process(x));
            return softClip((body + bp_.process(body) * 0.8f) * 1.6f) * 0.7f;
        }
        case Voice::Robot: {
            ringPhase_ += 2.f * kPi * 70.f / sr_;
            if (ringPhase_ > 2.f * kPi) ringPhase_ -= 2.f * kPi;
            const float ring = x * std::sin(ringPhase_);
            const float combed = ring + robotComb_.read(sr_ * 0.008f) * 0.6f;
            robotComb_.push(combed);
            return combed;
        }
        case Voice::Whisper: {
            // Replace the voiced signal with band-limited noise shaped by the voice envelope.
            const float e = env_.process(x);
            return bp_.process(noise_.next() * e * 3.f) + x * 0.15f;
        }
        case Voice::None:
        default:
            return x;
    }
}

oboe::DataCallbackResult MicFxEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int32_t frames = std::min(numFrames, static_cast<int32_t>(inBuf_.size()));
    const auto input = input_; // local copy; the pointer is only swapped while callbacks are stopped

    if (!input) {
        std::memset(out, 0, sizeof(float) * numFrames * 2);
        return oboe::DataCallbackResult::Continue;
    }

    // On the first callback, throw away whatever piled up in the input buffer during startup;
    // otherwise that backlog becomes permanent monitor latency.
    if (drainInput_) {
        while (true) {
            auto r = input->read(inBuf_.data(), static_cast<int32_t>(inBuf_.size()), 0);
            if (!r || r.value() <= 0) break;
        }
        drainInput_ = false;
    }

    int32_t got = 0;
    if (auto r = input->read(inBuf_.data(), frames, 0); r) got = r.value();
    if (got < frames) std::memset(inBuf_.data() + got, 0, sizeof(float) * (frames - got));

    applyPresetsIfChanged();
    const float gain = gain_.load(std::memory_order_relaxed);
    const float wet = wet_.load(std::memory_order_relaxed);
    const bool reverbOn = appliedAmbience_ != static_cast<int>(Ambience::None);

    for (int32_t i = 0; i < frames; ++i) {
        float s = processVoice(inBuf_[i] * gain);
        if (reverbOn) s = s * (1.f - wet * 0.5f) + reverb_.process(s) * wet;
        fxBuf_[i] = softClip(s);
    }

    if (recording_.load(std::memory_order_acquire)) {
        recordQueue_.push(fxBuf_.data(), static_cast<size_t>(frames));
    }

    if (monitor_.load(std::memory_order_relaxed)) {
        for (int32_t i = 0; i < frames; ++i) out[2 * i] = out[2 * i + 1] = fxBuf_[i];
    } else {
        std::memset(out, 0, sizeof(float) * frames * 2);
    }
    if (frames < numFrames) std::memset(out + frames * 2, 0, sizeof(float) * (numFrames - frames) * 2);
    return oboe::DataCallbackResult::Continue;
}

} // namespace micfx
