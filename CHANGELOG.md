# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-09-27

### Added
- **Native Audio Hot Path (NDK C++20 & Google Oboe)**:
  - High-performance low-latency audio rendering using Google Oboe with AAudio MMAP burst mode and OpenSL ES fallback.
  - Native format standard: 48,000 Hz, stereo (2 channels), 16-bit PCM.
  - Native Opus audio codec integration: 20ms frames (960 samples/channel), 128 kbps VBR compression.
  - Fractional sinc resampler in NDK for pitch-neutral speed micro-adjustments ($\pm 0.05\%$ to $\pm 0.1\%$) to continuously eliminate crystal oscillator clock skew.
  - Scheduled circular audio jitter buffer with packet loss concealment (PLC) and linear interpolation.
- **Local Network Discovery & Mesh Control Plane**:
  - Embedded high-throughput WebSocket / TCP session host server for instant peer-to-peer control signaling.
  - Zero-configuration local network discovery via Android `NsdManager` (mDNS service `_roombeat._tcp`).
  - Cryptographic 6-digit `SecureRandom` room PIN generation and verification.
  - High-speed CameraX QR code scanning engine with zero persistent image retention.
  - Room lobby and dynamic peer lifecycle management (join, heartbeat, status updates, graceful exit).
- **Acoustic Calibration & Clock Synchronization Engine**:
  - Nanosecond-precision monotonic presentation clock (`CLOCK_MONOTONIC_RAW` / `System.nanoTime()`).
  - NTP round-trip probe burst engine with Cristian's lowest-RTT cluster filter and statistical outlier rejection.
  - Continuous network RTT, jitter, and clock offset telemetry calculation.
  - Multicast health probe with automatic fallback detector.
  - Compose radar calibration reticle and tactile haptic feedback.
- **Multi-Source Audio Streaming Pipelines**:
  - **Local Storage Audio**: Storage Access Framework (SAF) document tree loader with real-time metadata extraction (ID3/Vorbis tags, album artwork) and 20ms frame chunking.
  - **System App Audio Capture**: Android 10+ `AudioPlaybackCaptureConfiguration` via `MediaProjection` foreground service (API 30–35+), capturing audio from YouTube, games, browsers, and media players.
  - **Spotify App Remote SDK**: Integration with Spotify client via secured binder IPC, `SPOTIFY_CMD` protocol synchronization dispatcher, and drift tracking loop.
- **Tactile Acoustic Industrial UI & Visualizers**:
  - Jetpack Compose hardware aesthetic theme (`#0B0C0E` chassis, `#13151A` module surface, `#FF5500` signal orange, `#00E599` phosphor lock).
  - High-performance 60fps Multi-Channel VU peak meter canvas with realistic analog decay ballistics.
  - Lissajous phase correlation oscilloscope rendering stereo and multi-node phase coherence.
  - Active playback telemetry HUD displaying monotonic target timestamps, RTT, jitter variance, and resampler ratio.
- **Power, Thermal & Hardware Hardening**:
  - Dynamic OS thermal status listener (`PowerManager.OnThermalStatusChangedListener`) automatically expanding jitter buffer depth (120ms to 240ms) during device throttling.
  - Multi-tiered OEM aggressive battery-saver exemption helper (MIUI/HyperOS, One UI, EMUI, ColorOS, FuntouchOS, Asus ROG).
  - Hardened `PowerLockManager` holding low-latency Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`), multicast lock, and wake lock (`PARTIAL_WAKE_LOCK`) with lifecycle assertions and 4-hour safety timeouts.
- **Packaging, Code Shrinking & Release Distribution**:
  - Production ProGuard / R8 keep rules preserving JNI native entry points, Google Oboe C++ bindings, Spotify App Remote SDK IPC models, and Kotlinx Serialization data classes.
  - Native symbol table extraction (`SYMBOL_TABLE`) reducing native `.so` payloads to ~522 KB while packaging unstripped symbols in `native-debug-symbols.zip`.
  - Architecture-specific release APK splits under 15MB (`arm64-v8a`: 11.55 MB, `armeabi-v7a`: 9.28 MB, `x86_64`: 12.51 MB) and universal release APK.
  - Production Google Play App Bundle (`app-release.aab`).
- **Landing Page & WebAudio Interactive Simulator (`site/`)**:
  - Static landing page built with Astro and Tailwind CSS v4.
  - Interactive multi-node WebAudio sync simulator soundboard demonstrating phase locking and resampler drift convergence in-browser.
  - GSAP scroll-driven architecture tracing beam and real-time 20ms Opus binary packet hex inspector.
  - Origin UI recessed 6-slot OTP keypad with animated PIN scrambling.
  - Responsive layout verified across mobile (360px) to ultra-wide desktop (1920px) with zero layout shift (CLS = 0.0001).
  - In-app offline privacy policy (`privacy_policy.html`) and public web privacy policy (`/privacy`).
- **Automated CI & Concurrency Testing**:
  - Automated 8-device concurrency stress test suite simulating 60 minutes of uninterrupted playback (180,000 frames) with zero crashes and zero buffer underruns.
  - Network chaos simulator with synthetic 50ms jitter spikes and abrupt peer disconnection resilience.
  - GitHub Actions CI workflows for Android testing (`android-ci.yml`) and Site validation (`site-ci.yml`).

### Security
- Complete privacy by design: zero analytics, zero telemetry, zero advertising trackers, and zero user accounts.
- Local subnet network isolation: all multicast audio packets (`239.255.42.99:4242`) are strictly non-routable across external gateway routers.
- Volatile RAM audio processing: captured media projection frames are never written to disk or uploaded.
