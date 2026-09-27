# 🎛️ RoomBeat Documentation Hub

Welcome to the **RoomBeat** documentation portal. RoomBeat transforms an ordinary room full of mobile phones into a high-fidelity, phase-locked distributed loudspeaker array over local Wi-Fi or zero-internet portable hotspots.

Designed with a **Tactile Acoustic Industrial** aesthetic, RoomBeat pairs studio-grade hardware aesthetics—analog VU meters, mechanical faders, and live phase oscilloscopes—with a native C++20/Oboe audio engine capable of **sub-10ms acoustic synchronization**.

> 🌐 **Interactive Web Simulator:** Try multi-device acoustic phase-locking in your browser at [https://roombeat.sohamlabs.workers.dev](https://roombeat.sohamlabs.workers.dev).

---

## 🧭 Documentation Index

Explore the comprehensive guides below to get the most out of your multi-device soundstage:

| Document | Description | Target Audience |
| :--- | :--- | :--- |
| [**🚀 Getting Started**](./getting-started.md) | Step-by-step walkthrough: Hosting a room, instant QR / 6-digit PIN joining, channel faders, and session termination. | All Users / First-time Hosts |
| [**🎵 Audio Sources**](./audio-sources.md) | How to stream Local Media (FLAC/MP3/WAV), System Audio Capture (YouTube/Netflix/Browser), and Spotify Remote. | Music Curators & DJs |
| [**📡 Network & Hotspot Guide**](./network-and-hotspot.md) | 5GHz vs 2.4GHz Wi-Fi tuning, router IGMP/multicast settings, and setting up 100% offline party hotspots for camping & beaches. | Network Hosts & Organizers |
| [**🔋 Battery & Power Optimization**](./battery-and-power.md) | OEM walkthroughs to disable background killing on Xiaomi (MIUI/HyperOS), Samsung (One UI), OnePlus/Oppo, Vivo, and Asus. | Android Device Owners |
| [**🛠️ Troubleshooting & FAQs**](./troubleshooting.md) | Quick diagnostic flowcharts, resolving Wi-Fi discovery failures, audio stutter fixes, and volume calibration. | Quick Support & Debugging |
| [**🏗️ Architecture & Sync Deep Dive**](./architecture.md) | Detailed technical breakdown: 20ms Opus pipeline, Google Oboe AAudio MMAP, Cristian's NTP sync, and Sinc Resampling. | Developers & Audio Engineers |

---

## ⚡ Quick Start: 3-Step Party Setup

```
┌─────────────────┐       Instant QR / PIN       ┌─────────────────┐
│   HOST PHONE    │ ───────────────────────────> │   PEER PHONES   │
│  (Pick Source)  │                              │  (Auto-Lock)    │
└────────┬────────┘                              └────────┬────────┘
         │                                                │
         └───────────── RAW UDP MULTICAST MESH ───────────┘
                     (239.255.42.99:4242 · <10ms Sync)
```

1. **Host a Room**: Launch RoomBeat on the primary phone, tap **Host Room**, and pick your audio source (Local Audio, System Audio Capture, or Spotify).
2. **Connect Peers**: Friends tap **Join Room** and scan the Host's on-screen QR code or punch in the 6-digit session PIN.
3. **Turn It Up**: Hit **Play**. All speakers instantly lock phase. Balance volume across devices using the Host's hardware channel strips.

---

## 🎯 Core Principles

- **100% Offline & Private**: Zero cloud relays, zero user accounts, zero telemetry. Audio stays inside your local subnet (`239.255.42.99:4242`).
- **Acoustic Coherence**: Hardware presentation time-tagging and micro-speed fractional resampling prevent phase comb filtering and hollow echo.
- **Hardware-Grade UI**: Tactile rotary dials, machined knurled faders, dual RMS/peak VU ballistics, and real-time Lissajous phase monitoring.
- **Extreme Efficiency**: Native C++20 SIMD processing and zero-copy ring buffers draw $<8\%$ CPU during 8+ device streaming sessions.

---

## 📱 Hardware & Platform Requirements

- **Operating System**: Android 11.0 (API Level 30) or newer (Android 14+ recommended for enhanced `AudioPlaybackCapture`).
- **Network Interface**: 802.11ac/ax (5GHz Wi-Fi recommended) or a Portable Mobile Hotspot (no cellular internet connection required).
- **Audio Output**: Device loudspeakers, wired 3.5mm AUX, USB-C DACs, or low-latency Bluetooth monitors.

---

*Need community help or want to contribute to RoomBeat? Check out our [Support Guide](../SUPPORT.md) and [Contributing Guidelines](../CONTRIBUTING.md).*
