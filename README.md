# Background Play Music & Video

Offline audio & video player for Android: 100% Kotlin + Jetpack Compose, Media3, Hilt and a
native Oboe mic-FX engine. Targets **Android 16 (API 36)**, min SDK 26, built with **Gradle 9.x**
(Kotlin DSL, version catalog).

## What is in this commit

| Area | Files |
|---|---|
| Build (Gradle 9.8.2 wrapper, AGP 9 with built-in Kotlin, version catalog) | `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| Manifest (media + mic FGS types, granular media permissions, PiP, AdMob ID) | `app/src/main/AndroidManifest.xml` |
| Background / lock-screen playback (Media3 `MediaSessionService`) | `playback/PlaybackService.kt`, `playback/PlaybackStateRepository.kt` |
| Low-latency mic FX (C++ / Oboe full duplex + JNI + Kotlin facade) | `cpp/*`, `audio/fx/*` |
| AdMob layer (consent, pacing, safe placements) | `ads/*`, `ads/ui/*`, `di/AdModule.kt` |
| Soundboard (in-app sheet, quick-pads notification, Quick Settings tile, 7 presets) | `soundboard/*`, `res/raw/pad_*.wav`, `tools/make_soundboard_pads.py` |
| App shell (Hilt app, edge-to-edge activity, auto-enter PiP, Material You theme) | `MusicPlayerApp.kt`, `MainActivity.kt`, `ui/theme/Theme.kt` |

Feature screens (library/MediaStore browser, player UI, karaoke vocal remover, ambient mixer,
visualizers, Room stats + Wrapped, onboarding) are **not built yet**. They plug into
the services and the `AdManager` API above.

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

Not compiled yet. The authoring environment had no Android SDK and couldn't reach Google Maven.
Library versions in `gradle/libs.versions.toml` are pinned to releases known at authoring time,
so check them for updates in Android Studio on first sync. The standalone DSP (`cpp/dsp.h`) was
compiled and smoke-tested on desktop: no NaN/Inf output, pitch ratios land on target, and
broadband loudness is kept through the pitch shifter.
