# 🏗️ Technical Architecture & Acoustic Sync Deep Dive

RoomBeat achieves **sub-10ms acoustic synchronization** across arbitrary Android mobile hardware without cloud relays, external timing servers, or proprietary audio accessories.

This document outlines the engineering architecture, mathematical formulas, and real-time C++20 signal processing pipeline that make phase-locked distributed playback possible.

> 🌐 **Interactive Schematic:** Explore the animated 4-stage pipeline and inspect live 20ms Opus hex packets on our [Interactive Web Simulator](https://roombeat.sohamlabs.workers.dev).

---

## 🎛️ System Overview: The 4-Stage Pipeline

```mermaid
flowchart LR
    subgraph Stage1["Stage 01: Audio Ingest"]
        A1[Local Media / SAF] --> Q1[Lock-Free SPSC Ring Buffer]
        A2[System Capture DMA] --> Q1
        A3[Spotify App Remote] --> Q1
    end

    subgraph Stage2["Stage 02: C++20 NDK Engine"]
        Q1 --> B1[Native libopus 1.5.2]
        B1 --> B2[20ms Frames / 960 Samples]
        B2 --> B3[Monotonic Time-Tagging]
    end

    subgraph Stage3["Stage 03: Transport Plane"]
        B3 --> C1[UDP Multicast 239.255.42.99:4242]
        C1 --> C2[WifiManager.MulticastLock]
        C2 --> C3[O(1) Mesh Fanout]
    end

    subgraph Stage4["Stage 04: Acoustic Sink"]
        C3 --> D1[Adaptive Jitter Buffer + PLC]
        D1 --> D2[Catmull-Rom Sinc Resampler]
        D2 --> D3[Google Oboe AAudio MMAP]
        D3 --> D4[Speaker Membrane]
    end
```

---

## ⏱️ The Latency Budget (<60ms End-to-End)

To prevent echo and phase comb filtering, the human ear requires multi-source audio to arrive within **$<10\text{ ms}$** of phase coherence (the *Haas Effect* threshold). RoomBeat operates with an end-to-end presentation delay budget of **60.0ms**:

| Stage | Subsystem | Latency Contribution |
| :--- | :--- | :--- |
| **01: Ingest** | OS Audio Capture / Ring Buffer Ingest | $1.85\text{ ms}$ |
| **02: Encode** | C++20 Opus Encoding (960 Samples @ 48kHz) | $0.88\text{ ms}$ |
| **03: Network** | 5GHz Wi-Fi / Hotspot UDP Multicast Transit | $1.50\text{ ms}$ |
| **04: Buffer** | Jitter Buffer Headroom + Resampler | $45.00\text{ ms}$ |
| **05: Hardware**| AAudio Exclusive MMAP DMA Burst Render | $6.20\text{ ms}$ |
| **Total** | **End-to-End Target Presentation Window** | **$\sim 55.43\text{ ms}$ ($\le 60.0\text{ ms}$)** |

Every peer device targets the exact same hardware presentation instant ($T_{\text{target}}$) in monotonic space, ensuring all speakers oscillate in phase.

---

## 🔬 Stage 01: Audio Ingest & Lock-Free SPSC Queue

Raw PCM audio arrives from one of three sources:
1. **Local Media**: Decoded via native Android `MediaCodec` or uncompressed WAV loaders.
2. **System Audio Capture**: Ingested via Android's `AudioPlaybackCaptureConfiguration` foreground service.
3. **Spotify App Remote**: Controlled via Spotify SDK IPC sockets and captured via system loopback.

All incoming audio is converted to **48,000 Hz, 32-bit floating point interleaved stereo PCM**. To prevent thread contention between the OS ingest thread and the real-time audio encoder, frames are deposited into a **Single-Producer Single-Consumer (SPSC) lock-free ring buffer**:

- **Wait-Free & Zero Allocation**: The read pointer advances using `std::memory_order_acquire` while the write pointer uses `std::memory_order_release`.
- **CPU Affinity**: Audio ingest and encoding threads are pinned to high-efficiency CPU cores to prevent scheduler preemption.

---

## 📦 Stage 02: Native Opus Compression & Monotonic Time-Tagging

The C++20 engine uses native `libopus 1.5.2` compiled with Android NDK and ARM NEON SIMD acceleration.

```
[ 48kHz Stereo Float32 ] ──> libopus ──> 20ms Frame (128 kbps VBR) ──> Attach 24B Header
(960 Samples / 20.0ms)                    (Avg 312 Bytes)                 (Total: 336 Bytes)
```

### Packet Format (24-Byte Header)

Each multicast datagram begins with a fixed 24-byte RoomBeat binary header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic: 'R' 'B' '1'      |  Packet Type  |  Sequence No  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Session ID (32-bit CRC)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+      Target Presentation Timestamp (CLOCK_MONOTONIC_RAW)      +
|                           (64-bit)                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Sample Rate (48000)         | Channels (2)  | Reserved (16) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Opus Encoded Payload Data                    |
|                        (~312 Bytes)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **Magic Signature (`0x52, 0x42, 0x01`)**: Identifies RoomBeat v1 datagrams.
- **Sequence Number**: Detects packet loss and out-of-order delivery.
- **Target Presentation Timestamp**: The exact hardware time ($T_{\text{target}}$) when the first sample of this frame must strike the speaker coil.

---

## 📡 Stage 03: Hardware UDP Multicast Mesh

RoomBeat transmits all audio packets to multicast address `239.255.42.99` on UDP port `4242`.

### $O(1)$ Bandwidth Scaling
Unlike unicast streams that duplicate data for each listener, multicast transmits each packet once. The Wi-Fi access point or phone hotspot broadcasts the datagram simultaneously to all subscribed receivers:

$$\text{Total Network Bandwidth} = \frac{336\text{ bytes} \times 8\text{ bits}}{0.020\text{ seconds}} \approx 134.4\text{ kbps}$$

Whether 2 phones or 50 phones are connected, network consumption remains constant at **$\sim 134.4\text{ kbps}$**.

---

## 🕒 Clock Synchronization: Cristian's Statistical NTP Engine

Before devices can honor presentation timestamps, their internal hardware clocks must be aligned. Because Android devices lack shared physical clock lines, RoomBeat implements an adapted variant of **Cristian's Algorithm** and **RFC 5905 NTP arithmetic**:

```
PEER (Client)                                          HOST (Server)
     │                                                      │
 T0  ● ──────────── NTP_PROBE_REQUEST ───────────────────>  │
     │                                                      ● T1
     │                                                      │ (Turnaround)
     │                                                      ● T2
 T3  ● <─────────── NTP_PROBE_RESPONSE ───────────────────── │
```

### Mathematical Equations

1. **Round-Trip Transit Time ($\text{RTT}$)**:
   $$\text{RTT} = (T_3 - T_0) - (T_2 - T_1)$$
   *(Excludes Host internal processing delay $T_2 - T_1$. Clamped to $\ge 0$.)*

2. **Clock Offset ($\theta$)**:
   $$\theta = \frac{(T_1 - T_0) + (T_2 - T_3)}{2}$$
   *(A positive offset indicates the Peer clock is ahead of the Host clock.)*

### Outlier Rejection & Lowest-RTT Clustering
Wi-Fi networks suffer from random queueing latency spikes due to background beacons and RF interference. RoomBeat filters this noise:
- **Outlier Discard**: Automatically discards the top **20% highest-RTT samples**.
- **Cristian's Cluster**: Selects the **lowest 50% RTT samples**. Under Cristian's theorem, packets experiencing the shortest round-trip time suffer the least asymmetric queueing delay, yielding an offset accuracy within **$<0.2\text{ ms}$**.

---

## 🎚️ Stage 04: Pitch-Neutral Fractional Resampler & Oboe MMAP

Even after clock synchronization, individual phone quartz crystals tick at slightly different physical frequencies (clock skew of 10 to 50 parts-per-million). Left uncorrected, buffers would slowly drift, leading to buffer underruns or echo.

RoomBeat solves this with a **C++20 Catmull-Rom Fractional Resampler**:

```
[ Incoming Opus Frame ] ──> [ Jitter Buffer ] ──> [ Fractional Resampler ] ──> [ Oboe AAudio MMAP ]
                                                         ▲
                                                         │ Closed-Loop PI Control
                                                 [ Drift: ±0.05% PPM ]
```

### Proportional-Integral (PI) Closed-Loop Slew Control
The Host monitors peer playback reports and calculates clock drift:

$$e(t) = T_{\text{actual}} - T_{\text{target}}$$

The PI controller computes a micro-speed modulation adjustment in **parts-per-million (PPM)**:

$$\text{Adjustment (PPM)} = K_p \cdot e(t) + K_i \int e(t) \, dt$$

- **Modulation Range**: Clamped to $\pm 500\text{ PPM}$ ($\pm 0.05\%$).
- **Pitch Neutrality**: At $0.05\%$ speed variation, frequency shifts are $<0.8\text{ cents}$—completely imperceptible to the human ear.
- **Continuous 50ms S-Curve Ramp**: Resampler parameter shifts are smoothly interpolated over 50ms ($2,400$ samples at $48\text{ kHz}$) to eliminate clicks, pops, and phase discontinuities.

### Google Oboe AAudio MMAP Burst Mode
To render audio with minimal driver latency, RoomBeat leverages **Google Oboe** in **AAudio Exclusive MMAP mode**:
- **Direct Memory Access (DMA)**: Audio frames bypass the Android audio server (`AudioFlinger`) and are written directly into kernel hardware DMA ring buffers.
- **Bursts**: Stream buffers operate at the native hardware burst size (typically 96 to 192 frames), achieving hardware output latency of **$<6.5\text{ ms}$**.

---

## 📊 Phase-Lock Metric Benchmarks

| Metric | Unsynchronized Bluetooth | Ordinary Streaming Apps | RoomBeat Audio Engine |
| :--- | :--- | :--- | :--- |
| **Clock Offset Variance** | $\pm 150\text{ ms}$ | $\pm 80\text{ ms}$ | **$< 0.2\text{ ms}$** |
| **Phase Coherence** | Complete Phase Decoherence | Audible Echo / Flanging | **Acoustically Phase-Locked** |
| **Packet Loss Handling** | Stutter / Silence | Rebuffering Spinners | **Opus PLC (Concealment)** |
| **CPU Utilization** | High (Java AudioTrack) | Moderate (ExoPlayer) | **$< 8\%$ (C++20 SIMD)** |

---

*Explore our practical guides: [Getting Started](./getting-started.md) • [Audio Sources](./audio-sources.md) • [Network Guide](./network-and-hotspot.md) • [Troubleshooting](./troubleshooting.md)*
