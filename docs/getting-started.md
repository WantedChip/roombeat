# 🚀 Getting Started with RoomBeat

This guide walks you through setting up, connecting, and fine-tuning your first multi-device RoomBeat sound session. Whether you are at a backyard barbecue, hanging out at the beach, or setting up a multi-room house soundstage, RoomBeat gets everyone in sync within seconds.

> 🌐 **Prefer a quick browser test?** Experience multi-node acoustic phase-locking right now on the [RoomBeat Web Simulator](https://roombeat.sohamlabs.workers.dev).

---

## 🎛️ System Concepts: Host vs. Peer

A RoomBeat soundstage consists of two device roles running over your local Wi-Fi or portable hotspot:

```
                          ┌─────────────────────────────┐
                          │         HOST DEVICE         │
                          │   (Transmitter & Console)   │
                          │  - Selects audio source     │
                          │  - Broadcasts Opus stream   │
                          │  - Master clock authority   │
                          │  - Remote volume faders     │
                          └──────────────┬──────────────┘
                                         │
                   Raw UDP Multicast Mesh (239.255.42.99:4242)
                   50Hz Cadence · Sub-10ms Acoustic Sync
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
  ┌─────────────────────────────┐                 ┌─────────────────────────────┐
  │         PEER NODE 1         │                 │         PEER NODE 2..N      │
  │     (Acoustic Receiver)     │                 │     (Acoustic Receiver)     │
  │  - Synchronizes to clock    │                 │  - Synchronizes to clock    │
  │  - Sinc resampler playback  │                 │  - Sinc resampler playback  │
  │  - Independent channel trim │                 │  - Independent channel trim │
  └─────────────────────────────┘                 └─────────────────────────────┘
```

- **The Host Device**: Plays or captures audio (Local files, YouTube/System audio, or Spotify), generates the session QR code and 6-digit PIN, broadcasts 20ms Opus packets, and manages master volume and individual peer faders.
- **The Peer Devices**: Receive the low-latency audio stream, synchronize their internal hardware playback clocks to the Host using Cristian's NTP algorithm, and play through their phone speakers or connected external audio gear.

---

## 📋 Pre-Flight Permissions Checklist

When launching RoomBeat for the first time, grant the necessary hardware permissions:

| Permission | Role | Why It's Needed |
| :--- | :--- | :--- |
| **Nearby Wi-Fi Devices** (`NEARBY_WIFI_DEVICES`) | Host & Peer | Discovers neighboring nodes without requiring GPS location access. |
| **Camera** (`CAMERA`) | Peer Only | Scans the Host's session QR code for instant zero-type connection. |
| **Notifications & Foreground Service** | Host & Peer | Keeps the native audio pipeline and UDP sockets awake when your screen locks. |
| **MediaProjection Consent** | Host Only | Required *only* if you choose **System Audio Capture** to broadcast sound from YouTube, Netflix, or other apps. |
| **Battery Optimization Exemption** | Host & Peer | Critical to prevent OEM task killers from freezing background audio. See the [Battery & Power Guide](./battery-and-power.md). |

---

## 🛠️ Step 1: Hosting a Room

1. Connect your phone to your local Wi-Fi network, or turn on your **Portable Mobile Hotspot** (see the [Network & Hotspot Guide](./network-and-hotspot.md) for offline setup).
2. Launch **RoomBeat** and tap **Host Room**.
3. Choose your audio input source:
   - 📂 **Local Storage**: High-resolution FLAC, MP3, WAV, OGG, or AAC audio files.
   - 📺 **System Audio Capture**: Broadcast anything playing on your device (YouTube, SoundCloud, Netflix, browser, games).
   - 🎧 **Spotify App Remote**: Synchronized control through your official Spotify client.
   *(For full details on each source, see the [Audio Sources Guide](./audio-sources.md).)*
4. Once the session initializes, the Host enters the **Room Lobby**:

```
+====================================================================+
| [O] ROOMBEAT CHASSIS RB-1                MONO CLOCK: 48,000Hz [||] |
+====================================================================+
| ROOM PIN: [ 4 8 2 9 0 1 ]       NET: 192.168.43.1 (5GHz Hotspot)   |
|                                                                    |
|    +---------------------+   PEERS CONNECTED: 3 / 8               |
|    | #######  ##  ###### |   ------------------------------------  |
|    | ##   ##  ##  ##  ## |   [#1] Pixel 8 Pro      (-0.2ms) [OK]   |
|    | #######  ##  ###### |   [#2] Galaxy S24 Ultra (+0.4ms) [OK]   |
|    |    ##    ##    ##   |   [#3] OnePlus 12       (-0.1ms) [OK]   |
|    | #######  ##  ###### |                                         |
|    +---------------------+   [ CALIBRATE PHASE ]  [ START SESSION ]|
+====================================================================+
```

---

## 📱 Step 2: Joining as a Peer Node

Friends can join your soundstage in under two seconds using either of the two connection methods:

### Method A: Instant QR Code Scan (Fastest)

1. On the Peer device, open RoomBeat and tap **Join Room**.
2. Point the camera at the QR code displayed on the Host's screen.
3. The app automatically reads the encrypted network payload (Host IP, Port, Multicast Group, Session ID), performs clock synchronization, and attaches to the stream.

### Method B: 6-Digit PIN Entry

If the camera is unavailable or phones are separated across a room:
1. Tap **Enter PIN Manually** on the Join screen.
2. Punch in the 6-digit PIN displayed on the Host's screen using the tactile industrial keypad.
3. Tap **Engage Link**. The peer locates the Host on the local subnet and begins syncing.

```
       [ 4 ]  [ 8 ]  [ 2 ]
       [ 9 ]  [ 0 ]  [ 1 ]
       [ CLEAR ] [ CONNECT ]
```

---

## 🎚️ Step 3: Multi-Device Volume Balancing & Channel Strips

Once playback starts, the Host screen transforms into an industrial **Active Playback Console** featuring individual channel strips for every connected phone:

```
+====================================================================+
| MASTER BUS: 0.0 dB   [ > PLAYING ]     BUFFER: 60ms | DRIFT: <0.5ms |
+====================================================================+
|  HOST CH.1  |  PEER #1    |  PEER #2    |  PEER #3    |   MASTER   |
|  Pixel 8    |  Galaxy S24 |  OnePlus 12 |  Xperia 1   |   OUTPUT   |
|  [VU: -6dB] |  [VU: -3dB] |  [VU: -4dB] |  [VU: -2dB] | [VU: 0dB]  |
|     ---     |     ---     |     ---     |     ---     |    ===     |
|     [|]     |      |      |      |      |     [|]     |    [#]     |
|      |      |     [|]     |     [|]     |      |      |     |      |
|      |      |      |      |      |      |      |      |     |      |
|    -2.0 dB  |   +1.5 dB   |    0.0 dB   |   -1.0 dB   |   0.0 dB   |
|    [ MUTE ] |   [ MUTE ]  |   [ MUTE ]  |   [ MUTE ]  |  [ MUTE ]  |
+====================================================================+
```

### Mixing Controls

- **Master Fader**: Controls the overall acoustic output of the entire room simultaneously.
- **Individual Channel Faders**: Adjust the digital gain ($\pm 12\text{ dB}$) of specific phones. If one phone has louder speakers or is placed closer to the listener, pull its fader down to maintain a balanced stereo field.
- **Instant Mute Keycaps**: Temporarily silence a specific phone without disconnecting it from the clock sync mesh.
- **Dual VU Ballistics**: Real-time 60fps analog VU meters display both RMS (perceived loudness) and True Peak levels to prevent digital clipping.

---

## 🔊 Step 4: Fine-Tuning Acoustic Phase (Calibration)

Different phone models feature varying internal DAC output latencies and speaker enclosure acoustics. While RoomBeat automatically synchronizes network presentation timestamps to **$<1.0\text{ ms}$**, you can run the optional **Acoustic Calibration Probe**:

1. Place the phones side by side on a flat surface.
2. Tap **Calibrate Phase** on the Host console.
3. The Host emits a brief, high-frequency acoustic calibration pulse across all channels.
4. Each phone records the pulse arrival via its microphone, measuring its hardware acoustic delay.
5. RoomBeat calculates the hardware offset and stores the trim value in local device memory for future sessions.

*(Learn more about our clock and phase synchronization algorithms in the [Architecture Deep Dive](./architecture.md).)*

---

## ⏹️ Step 5: Ending Sessions & Graceful Teardown

- **Ending a Session as Host**: Tap the red **Disengage System** keycap on the top right. A confirmation prompt prevents accidental stops. When confirmed, a `SESSION_TEARDOWN` packet is broadcast to all peers, returning them safely to the idle lobby screen.
- **Leaving as a Peer**: Tap **Disconnect**. The peer gracefully closes its AAudio MMAP stream, releases the multicast lock, and drops out without interrupting the remaining soundstage.
- **Host Loss Recovery**: If the Host phone runs out of battery or loses signal, peer devices immediately detect the missing heartbeat, fade audio smoothly over 300ms to eliminate pop sounds, and display a reconnect banner.

---

## 💡 Best Practices for Big Sound

1. **Use 5GHz Wi-Fi or Hotspot**: 5GHz offers vastly lower packet jitter and avoids 2.4GHz microwave/Bluetooth interference.
2. **Elevate Your Phones**: Place phones on wooden or solid surfaces rather than soft couches. Acoustic reflection off tabletops dramatically enhances low-mid frequencies.
3. **Stereo Separation**: Place your brightest-sounding phone in the center and bass-heavy phones near corners or walls for natural boundary acoustic bass loading.
4. **Disable Battery Killers**: Ensure all phones have disabled aggressive battery optimizations so playback does not stutter when the screen dims. Follow our [Battery & Power Optimization Guide](./battery-and-power.md).

---

*Next up: Learn how to stream different media in our [Audio Sources Guide](./audio-sources.md) or optimize your connection in the [Network & Hotspot Guide](./network-and-hotspot.md).*
