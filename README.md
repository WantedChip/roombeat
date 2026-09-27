# RoomBeat

> Perceptually-synchronized audio playback across a room full of Android phones on the same local network.

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-blue)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

---

## About RoomBeat

RoomBeat is an Android application that synchronizes audio playback across multiple smartphones on the same local Wi-Fi network or portable hotspot, turning a room full of phones into a unified, high-output sound system. 

The system operates without requiring an external relay server or cloud backend—the host phone acts as the entire local broadcast server. Audio is captured and encoded into 20ms Opus frames, broadcast to peer devices via low-latency raw UDP multicast, and scheduled for synchronized hardware output via Kotlin and the Android NDK (Oboe/AAudio). RoomBeat supports local storage audio files, third-party media app audio capture via `AudioPlaybackCapture`, and Spotify command-sync via the Spotify App Remote SDK.

---

## Repository Structure

- `android/` — Native Android application (Kotlin, Jetpack Compose, NDK C++ Oboe audio engine).
- `site/` — Static landing page and WebAudio interactive demonstration (Astro, Tailwind CSS).

---

## Documentation & Community Health

- **[Changelog](./CHANGELOG.md)** — Comprehensive record of v1.0.0 features, audio engines, and optimizations.
- **[Contributing Guidelines](./CONTRIBUTING.md)** — Guide to monorepo setup, development commands, and pull requests.
- **[Code of Conduct](./CODE_OF_CONDUCT.md)** — Community standards and enforcement pledge.
- **[Security Policy](./SECURITY.md)** — Vulnerability reporting guidelines and security principles.
- **[Support & Discussions](./SUPPORT.md)** — Getting help and community resources.

## Privacy by Design (100% Offline Local Network)

RoomBeat collects **zero telemetry, zero analytics, zero personal identifiers, and zero crash reports**. 
All audio streaming and clock synchronization operate strictly on your local Wi-Fi subnet or portable hotspot via low-latency UDP multicast (`239.255.42.99:4242`). Audio packets never cross the gateway router to the external internet.

Read the complete [Privacy Policy](https://roombeat.app/privacy) or view the offline document in [privacy_policy.html](./android/app/src/main/assets/privacy_policy.html).

---

## License & Third-Party Attributions

RoomBeat is licensed under the **MIT License**. Copyright (C) 2026 WantedChip. See [LICENSE](./LICENSE) for details.

### Third-Party Software Attributions
- **[Google Oboe](https://github.com/google/oboe)** — Apache License 2.0 (Copyright (C) 2018 The Android Open Source Project)
- **[Xiph.Org Opus Codec (libopus)](https://opus-codec.org/)** — BSD 3-Clause License (Copyright (C) 2001-2011 Xiph.Org Foundation, Skype Limited, CSIRO)
- **[Spotify App Remote SDK](https://developer.spotify.com/documentation/android)** — Spotify Developer Terms of Service (Copyright (C) Spotify AB)
- **[AndroidX & Jetpack Compose](https://developer.android.com/jetpack/compose)** — Apache License 2.0 (Copyright (C) Google LLC / AOSP)
- **[ZXing](https://github.com/zxing/zxing)** & **[Google MLKit](https://developers.google.com/ml-kit)** — Apache License 2.0
- **[Cabinet Grotesk & General Sans](https://www.fontshare.com)** — Fontshare Font Software License (Indian Type Foundry)
- **[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** — SIL Open Font License 1.1 / Apache License 2.0 (JetBrains s.r.o.)

