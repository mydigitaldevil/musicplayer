# Background Play Music & Video

Offline audio & video player for Android: 100% Kotlin + Jetpack Compose, Media3, Hilt and a
native Oboe mic-FX engine. Targets **Android 16 (API 36)**, min SDK 26, built with **Gradle 9.x**
(Kotlin DSL, version catalog).

## Build the APK in Android Studio

1. Unzip, then **File → Open** the `musicplayer` folder (the one containing `settings.gradle.kts`).
2. Let Gradle sync. Android Studio downloads Gradle 9.8.2, the Android SDK 36 platform, the **NDK**
   and **CMake** (accept the SDK Manager prompts; the C++ mic engine needs both).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The debug APK lands in
   `app/build/outputs/apk/debug/`. Debug builds use Google's **test** ad units, so they're
   safe to install and click around.
4. For a signed release: **Build → Generate Signed App Bundle / APK** after adding your real
   AdMob IDs (see *Release configuration*).

If Android Studio offers newer library versions on sync, accepting the patch updates is fine.
See *Status* at the bottom for what was and wasn't verified before shipping this code.

## Features and where they live

| Feature | Code |
|---|---|
| Library: songs + videos from MediaStore, search, Android 13/14 granular + partial media access | `library/*`, `ui/library/*` |
| Background / lock-screen playback, every container ExoPlayer supports + FFmpeg audio fallback | `playback/PlaybackService.kt`, `playback/PlayerConnection.kt` |
| Now Playing: artwork, seek, shuffle/repeat, custom blurred photo background | `ui/player/NowPlayingScreen.kt` |
| Audio-reactive visualizers (Bars free; Neon wave + Neon ring via rewarded unlock) | `audio/SpectrumAnalyzer.kt`, `ui/player/AudioVisualizer.kt` |
| Video player with auto picture-in-picture on the home gesture | `ui/player/VideoPlayerScreen.kt`, `MainActivity.kt` |
| Smart sleep timer: fade-out + optional "close app", shared by music and ambient | `playback/SleepTimer.kt`, `ui/player/SleepTimerSheet.kt` |
| Live Mic FX: 7 voices, 4 rooms, record over a song, export M4A, save to Music | `cpp/*`, `audio/fx/*`, `ui/micfx/*` |
| Karaoke: offline vocal remover + live mic; 1 free song/day, then rewarded 24 h unlock | `audio/VocalRemoverProcessor.kt`, `ui/karaoke/*` |
| Ambient mixer: rain, bonfire, café, river, white noise, binaural, all synthesised live | `ambient/*`, `ui/ambient/*` |
| Soundboard: in-app sheet, quick-pads notification, Quick Settings tile | `soundboard/*` |
| Listening stats (Room): top songs/artists, skip rate, repeats, peak hours, shareable Wrapped | `stats/*`, `ui/stats/*` |
| First-run onboarding (PiP, Mic FX with headphones, ambient + sleep timer) | `ui/onboarding/*` |
| Settings: Material You toggle, privacy options (UMP), clear stats, licences | `ui/settings/*` |
| AdMob layer: consent, pacing, safe placements | `ads/*`, `ads/ui/*` |

Architecture: single activity, Compose + Navigation, Hilt everywhere, ViewModels expose
`StateFlow` state and take user intents (MVI-style). Media is controlled only through a Media3
`MediaController`, so playback survives the UI and lock screen.

## Karaoke vocal remover

Centre-channel cancellation inside ExoPlayer's audio pipeline: lead vocals are usually mixed dead
centre, so the mid signal is removed while the side signal (stereo-spread instruments) stays.
Below ~150 Hz the mid is kept, so kick and bass survive. It's fully offline and costs almost no
CPU. Limits: centre-panned instruments are attenuated too, vocal reverb tails remain, and mono
files can't be processed. An ML separation model (Spleeter/Demucs-class) would sound cleaner
but adds tens of MB and heavy CPU, so it's a possible future upgrade rather than the default.

## Visualizers

A `TeeAudioProcessor` taps the decoded PCM, so there's no RECORD_AUDIO and no deprecated
`audiofx.Visualizer`. A 2048-point FFT feeds 32 log-spaced bands at ~30 fps, and it only runs
while a visualizer is on screen.

## Ambient mixer

Every sound is generated live (filtered noise, droplet/crackle/clink events, binaural sines), so
there are no audio files and no audible loop points. It plays on its own `AudioTrack` without
taking audio focus, so it layers under your music. Levels are matched so equal slider positions
sound equally loud (measured: 0.156–0.165 RMS for every sound).

## Soundboard (no overlay permission)

Instead of a floating overlay window (`SYSTEM_ALERT_WINDOW` is a restricted permission that
draws extra Play review), there are three ways to reach the sounds:

1. **In-app sheet** (FAB on the main screen). Tap a pad to play it; long-press to pin it or
   remove it. Mic FX recordings (`files/recordings/*.wav|m4a`, up to 15 s) can be added as pads.
2. **Quick-pads notification**: a silent notification whose buttons play up to 3 pinned pads.
   It works over games and video. There's no foreground service; each tap is a broadcast to
   `QuickPadReceiver`. Swiping the notification away turns the feature off.
3. **Quick Settings tile**: plays the first pinned pad from the shade, or opens the soundboard
   if nothing is pinned.

Pads don't request audio focus, so music and games keep playing underneath. During a phone call
they're heard locally only: Android has no API for regular apps to inject audio into the call
uplink. The sheet has no ads, because it's used mid-game or mid-call.

The 7 presets (ba-dum-tss, air horn, boing, wrong-answer buzzer, sad trombone, applause,
crickets) are synthesised by `tools/make_soundboard_pads.py`, so they're original and
royalty-free. They're loudness-matched to the same RMS and the script is reproducible
(`python3 tools/make_soundboard_pads.py app/src/main/res/raw`).

## Format support ("har format chalega")

- **Containers** parsed by ExoPlayer itself: MP4/M4A/MOV, MKV/WebM, AVI, MPEG-TS, FLV, OGG,
  FLAC, WAV, MP3, AAC/ADTS, AMR, Opus.
- **Audio codecs**: platform decoders first, then the bundled **FFmpeg** renderer
  (`org.jellyfin.media3:media3-ffmpeg-decoder`) for AC-3, E-AC-3, DTS, TrueHD, ALAC and others
  the phone can't decode.
- **Video codecs** use the phone's hardware decoders (H.264, H.265, VP9, AV1 on most devices),
  with decoder fallback turned on. Media3 has **no general FFmpeg video decoder**. Rare codecs
  the device can't decode (MPEG-2, VC-1, some 10-bit HEVC on older phones) won't play unless a
  software video decoder such as libVLC is added. That's a separate decision (APK size, licence).
- MP3/AAC files without a seek index still seek (constant-bitrate seeking is on).

## Mic FX engine

Output stream callback drives timing; the input is read non-blocking inside it (standard Oboe
duplex). Startup backlog is drained so it doesn't become permanent latency. All DSP is
allocation-free on the audio thread; parameters cross threads through atomics; recording goes
through a lock-free SPSC queue to a WAV writer, and `RecordingExporter` encodes to `.m4a`
(optionally mixed with the backing track) using Media3 Transformer.

Safety: monitoring is refused without headphones (a loudspeaker would howl with feedback) and
stops automatically if they're unplugged. Bluetooth is allowed but flagged as high-latency
(A2DP adds roughly 150–250 ms, which is too much for live monitoring).

Voices: Baby, Chipmunk, Robot, Megaphone, Deep Monster, Whisper, Announcer.
Rooms: Stadium, Club, Bathroom, Space Delay. Pitch shifting uses a 40 ms rotating-tape shifter,
which is low latency but does **not** preserve formants. That's fine for the character voices;
a natural-sounding formant-preserving shift would need a PSOLA/phase-vocoder stage with
more latency.

## AdMob policy design

All pacing rules live in `ads/AdPolicy.kt`:

- **Interstitials** only at `NaturalBreak` points (recording exported, back from stats,
  karaoke finished). They need at least 90 s since the last interstitial, 90 s since *any*
  full-screen ad closed, and 90 s after process start, with a cap per session. Navigation never
  waits for an ad. They're blocked while mic FX is live, in PiP, or when the activity isn't resumed.
- **Rewarded**: only after an explicit opt-in dialog that names the reward and duration. The
  reward is granted only from `onUserEarnedReward`, and the offer is hidden when no ad is loaded.
  Karaoke gives one free song per day, then the 24 h unlock.
- **App Open**: warm starts only. Never on a cold start, after lock-screen/screen-off, during
  an active playback session, after a short app switch (< 30 s), after an ad click-through,
  before onboarding, or more than once per 4 h (persisted).
- **Banners**: anchored adaptive with a fixed reserved height and a 16 dp clear zone. Collapsible
  only on browse screens, never on the player. Paused with the lifecycle (no refresh while
  playing in the background) and hidden in PiP.
- **Native**: "Ad" badge, AdChoices, all assets registered, separate list item styled unlike song rows.
- **Consent**: Google UMP runs before the SDK initialises. Expose `ConsentManager.showPrivacyOptions`
  in Settings when `isPrivacyOptionsRequired`.
- Debug builds always use Google's test ad units.

This code avoids the common causes of policy strikes, but it can't guarantee your account will
never be limited. Invalid-traffic limits also depend on traffic quality, and placement reviews
look at the final UI. Test with AdMob's ad inspector before release.

## Release configuration

Put real AdMob IDs in `~/.gradle/gradle.properties` or CI env (never commit them):

```
ADMOB_APP_ID=ca-app-pub-xxxxxxxx~yyyyyyyy
ADMOB_BANNER_ID=...
ADMOB_INTERSTITIAL_ID=...
ADMOB_REWARDED_ID=...
ADMOB_NATIVE_ID=...
ADMOB_APP_OPEN_ID=...
```

Play Console needs declarations for the `mediaPlayback` and `microphone` foreground-service
types. The app doesn't request `SYSTEM_ALERT_WINDOW` (see Soundboard above).

## Status

**Verified before shipping:**
- The pure-Kotlin logic (ad frequency capper, soundboard storage codec, FFT, ambient synth) was
  compiled with the real Kotlin 2.3.21 compiler, and its **20 unit tests pass**.
- The C++ DSP (`cpp/dsp.h`) was compiled and smoke-tested: no NaN/Inf output, pitch ratios land
  on target, and loudness is kept through the pitch shifter.
- Every `R.string` used in code or the manifest exists, there are no duplicates, and every XML
  resource is well-formed.
- Maven Central library versions (Kotlin, KSP, Hilt, coroutines, Truth, JUnit, Jellyfin FFmpeg)
  were checked against Central.

**Not verified:** the Android/Compose/Media3/AdMob code has **not** been compiled. The authoring
environment had no Android SDK and couldn't reach Google's Maven repository. The code was
reviewed against those APIs, but expect Android Studio to flag a few small things on the first
build (a renamed parameter, a missing opt-in). They're usually one-line fixes, and the error
message points to the exact line. Google-hosted versions (AGP, AndroidX, Media3, AdMob, UMP,
Oboe) are pinned to releases known at authoring time.
