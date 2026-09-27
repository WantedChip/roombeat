# 🎵 Audio Sources Guide

RoomBeat gives you the flexibility to broadcast virtually any sound to your synchronized speaker array. Whether you are spinning high-resolution lossless FLAC tracks from your device storage, streaming a video party on YouTube or Netflix via system audio capture, or controlling Spotify across the room, this guide explains how each source works and how to achieve pristine sound fidelity.

> 🌐 **Try it online:** Hear how RoomBeat dynamically normalizes and syncs multi-node audio on the [Live Web Simulator](https://roombeat.sohamlabs.workers.dev).

---

## 🎚️ Overview & Source Comparison

RoomBeat normalizes all audio inputs into a standardized **48,000 Hz, 32-bit floating point stereo PCM** audio stream before encoding it into 20ms Opus frames.

```
┌────────────────────────────────┐
│   Local Files (FLAC/MP3/WAV)   │ ──┐
└────────────────────────────────┘   │
┌────────────────────────────────┐   ├──> [ 48kHz Float32 PCM Bus ] ──> [ 20ms Opus C++20 ]
│ System Capture (MediaProjection)│ ──┤
└────────────────────────────────┘   │
┌────────────────────────────────┐   │
│ Spotify Remote (App Remote SDK)│ ──┘
└────────────────────────────────┘
```

| Source | Best Used For | Supported Formats | Offline Capable | Setup Complexity |
| :--- | :--- | :--- | :--- | :--- |
| **📂 Local Storage** | Audiophiles, high-res listening, outdoor camping | FLAC, MP3, WAV, OGG, AAC, M4A | **100% Yes** | Simple (One tap) |
| **📺 System Audio Capture** | YouTube, Netflix, TikTok, SoundCloud, mobile gaming | Any audio playing on phone | Depends on target app | Moderate (Screen consent) |
| **🎧 Spotify App Remote** | Collaborative party queues, official playlists | Spotify Catalog (Free & Premium) | Requires Internet / Offline Cache | Simple (Requires Spotify app) |

---

## 📂 Source 1: Local Audio Files (Lossless & High-Res)

Playing local audio directly from your device storage provides the highest possible acoustic fidelity, lowest latency, and total independence from internet connections.

### Supported File Formats
- **FLAC (`.flac`)**: Up to 24-bit / 96kHz lossless decoding.
- **MP3 (`.mp3`)**: Constant (CBR) and Variable (VBR) bitrates up to 320 kbps.
- **WAV (`.wav`)**: Uncompressed 16-bit and 24-bit linear PCM.
- **OGG Vorbis (`.ogg`)**: High-efficiency variable bitrate audio.
- **AAC / M4A (`.m4a`, `.aac`)**: Advanced Audio Coding standard files.

### How to Select & Play Local Music
1. In RoomBeat, tap **Host Room** -> **Local Music**.
2. Tap **Select Audio Folder** or **Pick Tracks**. RoomBeat invokes Android's secure **Storage Access Framework (SAF)** to grant read access to your music directory without requesting broad file system permissions.
3. The built-in **Audio Metadata Extractor** parses ID3v2, Vorbis Comments, and MP4 tags to display:
   - Track Title, Artist, and Album Name.
   - Embedded high-resolution cover artwork.
   - Accurate duration and sample rate telemetry.
4. Tap any track to begin playback. RoomBeat loads the next track into a pre-warmed background decoder queue for seamless, gapless playback transitions.

### Technical Ingest Pipeline
Local audio files are decoded in native code via Android's hardware-accelerated `MediaCodec` and custom decoders:
1. File samples are unpacked into memory.
2. The `AudioResamplerPipe` resamples any non-48kHz material (such as 44.1kHz CD rips) using high-fidelity sinc interpolation to prevent pitch warping.
3. Audio frames pass into a lock-free Single-Producer Single-Consumer (SPSC) ring buffer with zero dynamic memory allocation.

---

## 📺 Source 2: System Audio Capture (Any Android App)

Want to watch a movie on your tablet while routing the sound across 4 phones placed around your tent? Or play a live DJ set from YouTube, SoundCloud, or Twitch? System Audio Capture captures everything playing through your phone's audio output.

### How to Enable System Audio Capture
1. In RoomBeat, tap **Host Room** -> **System Audio Capture**.
2. Android displays a system security dialog: *"Start recording or casting with RoomBeat?"*
3. Tap **Start Now** (or check *Don't ask again* on supported Android versions).
4. RoomBeat launches a dedicated foreground service displaying an active capture icon in your status bar.
5. Switch to any app on your phone (YouTube, Netflix, Chrome, Spotify, games) and hit play. RoomBeat intercepts the audio and streams it to your connected room nodes in real time.

```
┌─────────────────────────┐
│ Host: YouTube / Netflix │
└────────────┬────────────┘
             │ AudioPlaybackCaptureConfiguration
             ▼
┌─────────────────────────┐
│ RoomBeat Capture Service│ ──> Lock-free Ring Buffer ──> 20ms Multicast UDP
└─────────────────────────┘
```

### System Requirements & Restrictions
- **Android Version**: Requires **Android 10 (API Level 29)** or newer. Android 14+ is strongly recommended for enhanced capture stability and lower driver latency.
- **Protected Content (DRM)**: Android security policies allow apps to restrict audio capture by setting `AudioAttributes.ALLOW_CAPTURE_BY_NONE`. While YouTube, browsers, SoundCloud, and most games allow capture, some DRM-heavy video streams (such as certain protected Netflix titles or banking apps) may silence audio capture.
- **Voice Communication**: Phone calls and apps using `AudioAttributes.USAGE_VOICE_COMMUNICATION` (e.g., WhatsApp calls, Discord voice channels) are isolated by the Android kernel and cannot be captured.

---

## 🎧 Source 3: Spotify App Remote Sync

RoomBeat integrates directly with the **Spotify App Remote SDK**, allowing you to control playback on your phone while RoomBeat distributes the audio across the room.

### How to Connect Spotify Remote
1. Ensure the official **Spotify** app is installed and logged in on the Host phone.
2. In RoomBeat, tap **Host Room** -> **Spotify Remote**.
3. RoomBeat initiates a secure IPC handshake with Spotify. If prompted, grant RoomBeat permission to control Spotify playback.
4. Once connected, RoomBeat retrieves:
   - Current track title, artist, and album name.
   - High-resolution album artwork loaded directly from Spotify CDN.
   - Track duration and playback position.
5. You can play, pause, skip, and scrub tracks directly from the RoomBeat industrial control bar without switching apps.

### Dual-Mode Spotify Operation
- **Direct App Remote**: RoomBeat sends playback transport commands to Spotify while capturing the output via System Audio Capture for multi-phone distribution.
- **Spotify Free vs. Premium**: Works seamlessly with both tiers. Free tier users are subject to Spotify's standard ad breaks and shuffle limitations, while Premium users enjoy on-demand track selection and 320 kbps streaming.

---

## ⚡ Audio Quality & Performance Tuning

### The 48,000 Hz Native Advantage
Android audio hardware HALs (Hardware Abstraction Layers) operate natively at **48 kHz**. Playing audio at 44.1 kHz on Android frequently triggers low-quality OS resamplers that introduce intermodulation distortion and CPU overhead. RoomBeat's internal pipeline standardizes on 48 kHz with native C++ fractional sinc resampling, guaranteeing:
- Zero OS-level audio resampling jitter.
- Exactly 960 stereo samples per 20ms Opus frame.
- Pristine high-frequency acoustic clarity.

### Managing Latency vs. Stability
- **Local Audio**: Yields the absolute lowest ingest latency ($<1.85\text{ ms}$).
- **System Audio Capture**: Adds $\sim 15\text{ ms}$ to $25\text{ ms}$ of buffer latency due to Android's OS audio playback capture buffer. RoomBeat's jitter buffer automatically accommodates this so that video-to-audio sync remains tightly aligned.

---

*Need to set up your network for high-throughput streaming? Continue to the [Network & Hotspot Guide](./network-and-hotspot.md).*
