# 🛠️ Troubleshooting & FAQs

This guide provides practical solutions, diagnostic flowcharts, and answers to common questions when hosting or joining a RoomBeat soundstage.

> 🌐 **Compare with a working baseline:** Test playback and sync mechanics in your browser with zero network hardware dependencies at [https://roombeat.sohamlabs.workers.dev](https://roombeat.sohamlabs.workers.dev).

---

## 🧭 Quick Diagnostic Flowchart

```
                 [ PROBLEM DETECTED ]
                          │
          ┌───────────────┴───────────────┐
          ▼                               ▼
 [ DISCOVERY / CONNECTION ]      [ AUDIO / PLAYBACK ]
          │                               │
  Are devices on the same        Does audio stutter when
  Wi-Fi network or Hotspot?      the screen locks?
          │                               │
     YES ── NO ──> Connect to        YES ── NO ──> Check for 2.4GHz
      │            same 5GHz Net      │            Wi-Fi congestion.
      ▼                               ▼            Switch to 5GHz!
  Is a VPN / Ad-blocker        Follow OEM Battery
  active on either phone?      Guide (Battery Saver ->
      │                        No Restrictions).
     YES ── NO ──> Disable AP Isolation
      │            on your Wi-Fi router.
      ▼
  Disable VPN / WARP
  (VPN routes LAN away).
```

---

## 🔍 Issue 1: Devices Cannot Discover Each Other (PIN / QR Fails)

### Symptoms
- Peer scans the QR code or enters the 6-digit PIN, but the app displays *"Searching for Host..."* and times out.

### Proven Solutions
1. **Verify Same Network Subnet**:
   - Ensure both devices are connected to the exact same Wi-Fi router or Hotspot. If one phone is on cellular data or connected to a neighbor's guest network, it cannot communicate with the Host.
2. **Turn Off VPNs, Ad-Blockers & Proxy Apps**:
   - Apps like **Cloudflare WARP (1.1.1.1)**, **AdGuard**, **Tailscale**, **ProtonVPN**, or corporate VPN profiles capture all device network traffic and redirect it into a virtual `tun0` interface. This blocks local UDP multicast packets (`239.255.42.99`). Temporarily disable your VPN during RoomBeat sessions.
3. **Disable "AP Isolation" on Your Router**:
   - Many home and venue routers isolate Wi-Fi devices from each other by default. In your router settings, disable **AP Isolation** (also called *Client Isolation* or *Station Isolation*). See the [Network & Hotspot Guide](./network-and-hotspot.md).
4. **Instant Fallback: Use Portable Hotspot**:
   - If a venue's corporate or hotel Wi-Fi blocks peer-to-peer traffic, have the Host create a **Portable Hotspot** and connect all friends directly. This bypasses the router entirely and works 100% offline.

---

## ⚡ Issue 2: Audio Stutter, Jitter, or Packet Dropouts

### Symptoms
- Audio clicks, pops, or stutters intermittently, especially when walking around or when phones go to sleep.

### Proven Solutions
1. **Switch from 2.4GHz to 5GHz Wi-Fi**:
   - 2.4GHz Wi-Fi is heavily congested by microwave ovens, Bluetooth monitors, and dozens of neighbor networks. Switching your router or phone hotspot to **5 GHz** eliminates packet jitter and brings latency variance under 1.5ms.
2. **Disable Aggressive OEM Battery Savers**:
   - If audio cuts out 30 seconds after locking your phone screen, your device's manufacturer is freezing the background network socket. Follow the steps in our [Battery & Power Optimization Guide](./battery-and-power.md) for Xiaomi, Samsung, OnePlus, Vivo, or Asus.
3. **Check for Thermal Throttling**:
   - If phones are sitting outdoors in direct sunlight, processors heat up and throttle background audio callbacks. Move phones into the shade.
4. **Increase Buffer Headroom**:
   - On the Host's Settings screen, increase the jitter buffer from the default **60ms** to **80ms** or **100ms** if operating in congested radio environments.

---

## 🔊 Issue 3: Perceived Echo or Hollow "Comb Filtering"

### Symptoms
- Music sounds slightly doubled, like a hollow bathroom chorus, rather than a punchy unified acoustic sound.

### Proven Solutions
1. **Run the Acoustic Calibration Probe**:
   - Different phone models have slight variations in internal DAC hardware buffering (often 10ms to 25ms difference between phones).
   - Place all phones side by side on a table and tap **Calibrate Phase** on the Host screen. RoomBeat emits a calibration pulse to measure each phone's physical acoustic delay and trims it out automatically.
2. **Check the Clock Sync Telemetry Badge**:
   - In the HUD header, check the sync badge. If a peer shows drift greater than **$\pm 1.0\text{ ms}$**, tap the peer card to force an immediate NTP re-sync.
3. **Physical Speaker Orientation**:
   - Avoid pointing phone speakers directly at each other at close range ($<10\text{ cm}$). Angle them outward into the room or place them facing up toward a flat reflective ceiling for natural spatial dispersion.

---

## 🎚️ Issue 4: Volume Too Low or Unbalanced Sound

### Symptoms
- One phone is overwhelmingly loud while other phones can barely be heard.

### Proven Solutions
1. **Set Physical Hardware Volume to 100% First**:
   - On all Peer phones, raise the Android system media volume (using the physical rocker buttons) to maximum ($100\%$).
2. **Use RoomBeat's Channel Faders for Mixing**:
   - Use the Host's on-screen **Active Playback Console** channel strips to trim individual phone levels ($\pm 12\text{ dB}$). Pull down the fader for phones with louder drivers and boost quieter devices.
3. **Disable Conflicting Sound Enhancers**:
   - Built-in phone features like *Dolby Atmos*, *Samsung SoundAlive*, or *Xiaomi Sound* can apply aggressive dynamic compression. Disabling these in phone settings allows RoomBeat's native 32-bit audio engine to output full dynamic range.

---

## 📺 Issue 5: System Audio Capture is Silent

### Symptoms
- Host selects **System Audio Capture**, but no sound is transmitted to peer devices.

### Proven Solutions
1. **Grant MediaProjection Consent**:
   - When launching System Audio Capture, Android requires you to tap **Start Now** on the system dialog. If dismissed, the OS blocks the capture pipeline.
2. **Verify Target App Audio Policies (DRM)**:
   - While YouTube, SoundCloud, Spotify, web browsers, and mobile games permit audio capture, certain DRM-protected video streaming apps (like specific protected Netflix movies or banking apps) restrict capture by setting `ALLOW_CAPTURE_BY_NONE`.
3. **Ensure Target App is Actively Emitting Sound**:
   - Ensure the media app on the Host phone is unmuted and actually playing audio.

---

## 🎧 Issue 6: Spotify App Remote Disconnects or Won't Control

### Symptoms
- RoomBeat shows *"Connecting to Spotify..."* but cannot control playback or fetch track details.

### Proven Solutions
1. **Official Spotify App Must Be Running**:
   - The official Spotify app must be installed and logged into an active account on the Host phone. Open Spotify once before connecting in RoomBeat.
2. **Enable Device Broadcast Status in Spotify**:
   - In Spotify, tap **Settings (Gear Icon) -> Device Broadcast Status** and toggle it to **ON**. This allows third-party integrations to receive track state updates.
3. **Disable Battery Optimization for Spotify**:
   - Ensure Spotify itself is not being killed by Android battery management while running in the background.

---

## 📋 Pre-Party Diagnostic Checklist

Before kicking off a major gathering or outdoor party, run through this 30-second checklist:

| Check | Optimal State | Status |
| :--- | :--- | :---: |
| **Network Band** | 5GHz Wi-Fi or 5GHz Portable Hotspot | [ ] |
| **VPN / WARP** | Disabled on Host and Peers | [ ] |
| **Battery Saver** | "Unrestricted" / "No Restrictions" granted | [ ] |
| **Phone Placement** | In shade, elevated on solid surface | [ ] |
| **Media Volume** | Physical volume at 100% on all peers | [ ] |

---

*Still having issues? Open an issue on GitHub or visit the [Documentation Hub](./README.md).*
