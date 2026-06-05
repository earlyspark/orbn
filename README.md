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

🚧 **Very early — not yet usable.** The hardest technical risks are validated on real hardware:
on-device audio analysis runs end to end on the MindOne — a real track is decoded and tagged with
tempo, key, energy, and mood entirely on the device. The library, playback, biometrics, matching,
and visualizer are being built out milestone by milestone in the open.

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

## Building

orbn is an Android app. You'll need Android Studio (or the Android SDK + NDK) and JDK 21.

```bash
./scripts/fetch-native-deps.sh   # one-time: download prebuilt native deps (Essentia, ONNX Runtime, Eigen, models)
./gradlew :app:installDebug      # build and install on a connected device
```

The native dependencies are large prebuilt binaries kept out of git; the script fetches them
from a GitHub Release and unpacks them where the build expects.

> More detailed setup (including how to connect your own Oura account) will be documented as those
> parts of the app come together.

## Roadmap (direction, not promises)

On-device music analysis → background tagging of your library → audiophile playback →
Oura integration → biometric matching → the reactive visualizer → polish.

## Author

Built by RayAna ([@earlyspark](https://github.com/earlyspark)).

## License

[MIT](LICENSE).

## Acknowledgements

Standing on the shoulders of open source — including [Essentia](https://essentia.upf.edu/)
(audio analysis), [ONNX Runtime](https://onnxruntime.ai/) (on-device inference), and
[projectM](https://github.com/projectM-visualizer/projectm) (MilkDrop-style visuals).
