<div align="center">

# 🎛️ RoomBeat

### Turn a room full of phones into a unified, phase-locked sound system.
**No Bluetooth range limits. No cloud relay. Zero accounts. 100% offline.**

[![Version](https://img.shields.io/badge/Release-v1.0.0-FF5500?style=for-the-badge&logo=android&logoColor=white)](#)
[![Deployed with Cloudflare Pages](https://img.shields.io/badge/Deployed%20with-Cloudflare%20Pages-F38020?style=for-the-badge&logo=cloudflare&logoColor=white)](https://roombeat.app)
[![Android](https://img.shields.io/badge/Android-11%2B%20(API%2030%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-F5A623.svg?style=for-the-badge)](./LICENSE)
[![Zero Cloud](https://img.shields.io/badge/Cloud%20Dependency-0%25%20(100%25%20Offline)-00E599?style=for-the-badge)](#)

[**🌐 Live Interactive Demo**](https://roombeat.app) • [**⚡ Quick Start**](#-quick-start-3-steps) • [**✨ Why RoomBeat?**](#-why-roombeat) • [**📖 Documentation**](#-documentation--community)

</div>

---

## 🔊 What is RoomBeat?

Ever wanted to play music at a party, picnic, or beach hangout, but only had everyone's phones? Portable Bluetooth speakers only connect to one phone at a time, and ordinary "party apps" lag, drop connections, force you to create accounts, or route audio through sluggish cloud servers.

**RoomBeat fixes this completely.**

One phone becomes the **Host**, and any number of surrounding phones connect as **Peers**. Audio is streamed across your local Wi-Fi or portable mobile hotspot with **sub-10ms acoustic synchronization**—eliminating the annoying echo chamber effect and creating a massive, punchy, distributed sound wall.

> 🏕️ **Works everywhere—even with zero internet.** Turn on your phone's personal hotspot in the middle of nowhere, connect your friends' phones via QR code, and start blasting music.

---

## ✨ Why RoomBeat?

| Feature | RoomBeat | Typical Bluetooth / "Party" Apps |
| :--- | :--- | :--- |
| **Acoustic Sync** | **Sub-10ms phase-lock** (crisp & unified) | 100ms–500ms delay (terrible echo) |
| **Connection Range** | Entire Wi-Fi / Hotspot range | 10 meters (stutters if you walk away) |
| **Phone Limit** | **8+ simultaneous devices** | Usually 1 or 2 devices |
| **Cloud Dependency** | **0% — 100% Local Network Mesh** | Requires cloud relay / external server |
| **Accounts / Ads** | **Zero accounts, zero trackers, zero ads** | Aggressive signups, ads & tracking |
| **Audio Sources** | **Anything**: YouTube, Spotify, Local MP3/FLAC, Media Players | Restricted to custom in-app playlists |

---

## 🚀 Key Highlights

- ⏱️ **Sub-10ms Acoustic Sync**: Hardware-timed presentation clock with continuous micro-speed drift correction keeps all speakers perfectly in phase. No hollow comb filtering or muddy delay.
- 📡 **Raw UDP Multicast Mesh**: Transmits high-efficiency 20ms Opus frames (`239.255.42.99:4242`) directly across the local subnet. One host broadcast effortlessly feeds dozens of listening nodes without multiplying network bandwidth.
- 📲 **Stream ANY Audio**:
  - 📂 **Local Audio**: Play FLAC, MP3, WAV, OGG, or M4A directly from your phone's storage.
  - 📺 **System Audio Capture**: Broadcast sound from YouTube, Netflix, browsers, audio players, or games via Android's high-fidelity `AudioPlaybackCapture`.
  - 🎧 **Spotify Sync**: Synchronize playback commands across the room using Spotify App Remote.
- ⚡ **2-Second Instant Join**:
  - Scan the Host's on-screen QR code with your camera, or type a simple 6-digit PIN. Zero Bluetooth pairing headaches.
- 🎛️ **Tactile Industrial Console**:
  - Clean, dark hardware rack aesthetic with real-time 60fps analog VU meters, individual device faders, and a live phase oscilloscope.
- 🔋 **Battery & Thermal Optimized**:
  - Consumes $<8\%$ CPU load during 8-device streaming. Smoothly adapts jitter buffers if phones get warm in the sun.

---

## ⚡ Quick Start (3 Steps)

1. **Host a Room**:
   Open RoomBeat on the primary phone and tap **Create Room**. Choose your audio source (Local Music, System Capture, or Spotify).
2. **Connect Phones**:
   Other phones open RoomBeat and scan the Host's QR code or enter the 6-digit PIN.
3. **Turn Up the Volume**:
   Hit Play! All connected phones instantly lock phase and blast audio together. Adjust individual device volumes right from the Host's channel strips.

---

## 🌐 Try the Web Simulator

Experience RoomBeat's dynamic multi-node acoustic synchronization directly in your browser:
👉 **[roombeat.app](https://roombeat.app)**

Play with the interactive 4-node soundboard, drag faders, inject network latency spikes, and watch the phase-lock engine lock nodes back into sub-0.5ms synchronization in real time.

---

## 🛠️ Monorepo Structure

- [`android/`](./android/) — Native Android client built with Kotlin, Jetpack Compose, and NDK C++20 (Google Oboe, Opus codec, and fractional sinc resampler).
- [`site/`](./site/) — Production landing page and interactive WebAudio sync simulator built with Astro and Tailwind CSS v4.

---

## 📖 Documentation & Community

- 📜 **[Changelog](./CHANGELOG.md)** — Detailed record of v1.0.0 release milestones and technical features.
- 🤝 **[Contributing Guidelines](./CONTRIBUTING.md)** — Guide to local environment setup, Gradle/npm commands, and PR submissions.
- 🛡️ **[Code of Conduct](./CODE_OF_CONDUCT.md)** — Contributor Covenant community pledge.
- 🔒 **[Security Policy](./SECURITY.md)** — Vulnerability reporting guidelines and security principles.
- 💬 **[Support & Discussions](./SUPPORT.md)** — Getting help, troubleshooting, and community resources.

---

## 🛡️ Privacy by Design (100% Offline Local Network)

RoomBeat is built from the ground up to respect user privacy:
- **Zero Telemetry**: No analytics SDKs, no advertising frameworks, no tracking cookies, and no crash collectors.
- **Local Network Only**: Audio streams and clock packets stay strictly inside your local Wi-Fi / Hotspot subnet (`239.255.42.99:4242`) and never cross the internet gateway.
- **Ephemeral RAM Audio**: Captured system audio frames are processed in transient memory and are **never written to disk or recorded**.

Read the full [Privacy Policy](https://roombeat.app/privacy) or view the offline asset at [`privacy_policy.html`](./android/app/src/main/assets/privacy_policy.html).

---

## 📄 License & Attributions

RoomBeat is open-source software licensed under the [MIT License](./LICENSE). Copyright (C) 2026 WantedChip.

### Third-Party Software Attributions
- **[Google Oboe](https://github.com/google/oboe)** — Apache License 2.0 (The Android Open Source Project)
- **[Xiph.Org Opus Codec](https://opus-codec.org/)** — BSD 3-Clause License (Xiph.Org Foundation, Skype Limited, CSIRO)
- **[Spotify App Remote SDK](https://developer.spotify.com/documentation/android)** — Spotify Developer Terms of Service (Spotify AB)
- **[AndroidX & Jetpack Compose](https://developer.android.com/jetpack/compose)** — Apache License 2.0 (Google LLC / AOSP)
- **[ZXing](https://github.com/zxing/zxing)** & **[Google MLKit](https://developers.google.com/ml-kit)** — Apache License 2.0
- **[Cabinet Grotesk & General Sans](https://www.fontshare.com)** — Fontshare Font Software License (Indian Type Foundry)
- **[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** — SIL Open Font License 1.1 / Apache License 2.0 (JetBrains s.r.o.)
