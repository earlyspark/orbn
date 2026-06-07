<div align="center">

# orbn

**music that matches how your body feels**

A biometric-aware, fully on-device music player for the [iKKO MindOne](https://ikko.com/).
It reads your body's daily recovery from an Oura Ring, understands the *feel* of your own
music library, and plays what fits — all wrapped in a full-screen, nostalgic MilkDrop-style visualizer.

</div>

---

## What is orbn?

Most "mood" players stream someone else's catalog and react to a single number like heart rate.
orbn is different on three counts:

- **It plays *your* music** — the local files you own, not a streaming service.
- **It understands how you are** — using your Oura Ring's overnight recovery/HRV as a daily
  baseline, nudged by your heart rate through the day.
- **It runs entirely on the device** — analysis, matching, and visuals all happen locally on the
  MindOne. No cloud, no account, no data leaving the device.

Under the hood, orbn analyzes each song once (tempo, key, energy, mood, genre) and places it in a
simple *valence–energy* space. Your biometric state becomes a target point in that same space, and
orbn plays the tracks that fit — then renders them as living, reactive visuals you can just stare at.

## The story
This is my first project of a career break that began in June 2026 — built in public, partly as
a thing I genuinely want to use, partly to learn in the open. It's a personal tool first; if it's
useful or interesting to anyone else, all the better. Expect rough edges and frequent change.

I spent almost 10 years at Twitch and I left due to personal reasons, mostly health and general burnout.
I wrote a longer piece on [my LinkedIn](https://www.linkedin.com/feed/update/urn:li:activity:7467651824775266304/)
if you're curious. Taking a career break in this job market is objectively unwise, and I'm aware of
the privilege involved. 

Why iKKO MindOne? I backed it on Kickstarter in August 2025 because I love small phones and I was looking 
to replace my Jelly Star. What arrived nine months later was a device without Google Play Services, no 
OTA updates, and no real path to being a normal phone. Rather than fight for a refund I couldn't get, I was
inspired by cyberdecks and decided to turn this into a music player with no distractions that can read my "mood".

If the idea of a music player that actually knows how you're doing seems neat to you, I'd appreciate your support:

- [GitHub Sponsors](https://github.com/sponsors/earlyspark)
- [Buy me a coffee](https://buymeacoffee.com/earlyspark)

## Status

🚧 **Early, but the core loop works: it reads your body and plays music to match.** On real
hardware, orbn scans and analyzes your library entirely on-device (tempo, key, energy, mood, genre),
connects to your Oura Ring to read your recovery, heart rate, and stress into a "how you are right
now" signal, and **builds a play queue that fits that signal** — sampled for variety, ordered to
flow, and mindful of what you've recently played so it doesn't repeat. Pull down to re-tune the
queue to right now. Plays back hi-res to the iKKO's DAC when the case is attached. Still to come, in
the open: the full-screen reactive visualizer, a manual mood picker, and a lot of polish.

## The device

orbn targets the **iKKO MindOne**: a small, near-square Android 15 device with a Cirrus Logic DAC,
no Google services, and a focus on audio. The goal is a standalone, glanceable music player you live
inside — with the occasional check of your Oura data — rather than a phone-style app.

> orbn is being developed on the iKKO MindOne but is written as a general Android app —
> the intention is for it to work for any Android + Oura Ring user, not just iKKO owners.

## How it works (high level)

```
your music files ──► on-device analysis (tempo / key / energy / mood / genre)
                                   │
Oura Ring ──► daily recovery + intra-day heart rate ──► "how you are" right now
                                   │
                     match in a valence–energy space
                                   │
                 play fitting tracks ──► full-screen reactive visualizer
```

## Tech & privacy

- **Kotlin + Jetpack Compose**, Android NDK for the native pipeline.
- On-device audio analysis and machine-learning inference for tagging.
- A MilkDrop-style visualizer for the now-playing experience.
- The Oura Web API for biometric data.
- **Privacy by design:** everything runs locally. Your listening and biometric data stay on the
  device; orbn talks to the Oura API only to fetch *your own* data, and to nothing else.
  See the [Privacy Policy](PRIVACY.md) for the full picture.

## Building

orbn is an Android app. You'll need Android Studio (or the Android SDK + NDK) and JDK 21.

```bash
./scripts/fetch-native-deps.sh   # one-time: download prebuilt native deps (Essentia, ONNX Runtime, Eigen, models)
./gradlew :app:installDebug      # build and install on a connected device
```

The native dependencies are large prebuilt binaries kept out of git; the script fetches them
from a GitHub Release and unpacks them where the build expects.

### Connecting Oura

To use the biometric features you supply your **own** Oura developer app credentials — orbn ships
none. Register an application at [developer.ouraring.com/applications](https://developer.ouraring.com/applications)
with the redirect URI `com.earlyspark.orbn://oauth2redirect`, then add the values to
`local.properties` (which is gitignored and never committed):

```properties
OURA_CLIENT_ID=your_client_id
OURA_CLIENT_SECRET=your_client_secret
OURA_REDIRECT_URI=com.earlyspark.orbn://oauth2redirect
```

The build injects these into `BuildConfig` at compile time; they are never hardcoded in source.
Without them the app still builds and plays music — it just shows "Oura: add credentials" instead
of biometric data.

Once connected, orbn reads your data on app-open and at each track change (and on demand), showing
the time of the freshest reading. For this to stay current, **turn on background sync in the Oura
app** — your ring only reaches Oura's cloud through that app, so with background sync off orbn can
only see data from the last time you manually opened Oura.

> More detailed setup will be documented as the remaining parts of the app come together.

## Roadmap

On-device music analysis → background tagging of your library → playback → Oura integration →
biometric matching ✅ **— done so far** — → **the reactive visualizer (next)** → polish.

## Author

Built by RayAna ([@earlyspark](https://github.com/earlyspark)).

## License

[MIT](LICENSE).

## Acknowledgements

Standing on the shoulders of open source — including [Essentia](https://essentia.upf.edu/)
(audio analysis), [ONNX Runtime](https://onnxruntime.ai/) (on-device inference), and
[projectM](https://github.com/projectM-visualizer/projectm) (MilkDrop-style visuals).
