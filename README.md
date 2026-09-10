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

## License

This project is licensed under the MIT License. See the [LICENSE](./LICENSE) file for full details.
