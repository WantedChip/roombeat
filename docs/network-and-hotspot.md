# 📡 Network & Hotspot Guide

RoomBeat replaces cumbersome Bluetooth pairing limits with a high-throughput, low-latency **UDP Multicast Local Mesh**. By leveraging standard Wi-Fi hardware, RoomBeat streams studio-quality audio to 8 or more phones simultaneously across an entire home, garden, or campsite.

This guide explains how to optimize your network for sub-millisecond packet delivery, how to configure home routers, and how to create a **100% offline portable hotspot** with zero internet connection.

> 🌐 **Interactive Demo:** Experience how RoomBeat handles network jitter and packet loss on the [Live Web Simulator](https://roombeat.sohamlabs.workers.dev).

---

## 📶 Why Wi-Fi Mesh Beats Bluetooth

| Metric | Bluetooth Audio (A2DP / Auracast) | RoomBeat Wi-Fi Multicast Mesh |
| :--- | :--- | :--- |
| **Max Devices** | 1 to 2 phones (Auracast hardware is rare) | **Unlimited / 8+ simultaneous phones** |
| **Acoustic Sync** | 100ms – 500ms delay (severe echo) | **Sub-10ms phase-lock** (crisp & unified) |
| **Operating Range** | ~10 meters (cuts out through walls) | **50 – 100 meters** (entire Wi-Fi coverage) |
| **Bandwidth Scaling**| $O(N)$ (Bandwidth multiplies per phone) | **$O(1)$** (1 broadcast feeds all nodes) |
| **Internet Need** | None | **None (100% offline local subnet)** |

In standard unicast audio (like Bluetooth or cloud streams), adding more phones doubles the required transmission bandwidth ($O(N)$). In contrast, RoomBeat emits a single 336-byte UDP datagram to multicast group `239.255.42.99:4242` every 20ms. Every phone on the network picks up this identical packet simultaneously with **$O(1)$ zero overhead scaling**.

---

## ⚡ 5GHz vs. 2.4GHz Wi-Fi

When configuring your router or portable hotspot, selecting the correct radio frequency is the single most important factor for rock-solid audio synchronization:

```
[ 2.4 GHz Band ]  === Microwave === Bluetooth === 15 Neighbor Wi-Fis ===> JITTER: 15-45ms (Stutter)
[ 5.0 GHz Band ]  ------------------ Clean High-Throughput Spectrum ---> JITTER: < 1.5ms  (Phase Lock)
```

### Why 5GHz is Strongly Recommended
1. **Clean Spectrum**: The 2.4GHz band is shared by Bluetooth headphones, microwave ovens, baby monitors, and dozens of neighboring routers. This congestion creates packet retransmissions and jitter spikes.
2. **Low Packet Delay Variation (PDV)**: 5GHz provides vast 80MHz and 160MHz channels with deterministic transit times (typically $<1.5\text{ ms}$).
3. **Shorter Airtime**: Higher PHY link rates mean audio packets leave the transmitter faster, reducing queueing delays in the phone's Wi-Fi chip.

> [!TIP]
> If your router broadcasts both 2.4GHz and 5GHz under the same network name (SSID), connect all RoomBeat phones to the dedicated 5GHz band, or use a 5GHz mobile hotspot.

---

## 🏕️ 100% Offline Portable Hotspot (Zero Internet Setup)

One of RoomBeat's greatest features is that **it does not require an active internet connection**. You can turn an empty beach, a remote mountain campsite, or a moving road-trip car into an amphitheater using a single phone's personal hotspot.

```
                  ┌───────────────────────────────┐
                  │          HOST PHONE           │
                  │   - Mobile Hotspot: ON        │
                  │   - Mobile Cellular Data: OFF │
                  │   - Local IP: 192.168.43.1    │
                  └──────────────┬────────────────┘
                                 │
                     Local Wi-Fi SSID: "CampBeat"
                     (Zero Internet Gateway Required)
                                 │
             ┌───────────────────┴───────────────────┐
             ▼                                       ▼
┌─────────────────────────┐             ┌─────────────────────────┐
│       PEER PHONE 1      │             │       PEER PHONE 2      │
│  IP: 192.168.43.14      │             │  IP: 192.168.43.82      │
└─────────────────────────┘             └─────────────────────────┘
```

### Step-by-Step Hotspot Instructions

1. **Disable Cellular Mobile Data (Optional, saves battery)**:
   - On the Host phone, turn off **Mobile Data** in Quick Settings. RoomBeat does not require cellular data.
2. **Configure the Hotspot**:
   - Go to **Settings -> Network & Internet -> Portable Hotspot / Tethering**.
   - Set the **AP Band** to **5 GHz** (if your device supports 5GHz tethering).
   - Set a simple Network Name (SSID) and Password.
3. **Prevent Hotspot Auto-Timeout**:
   - In your Hotspot settings, disable options such as *"Turn off hotspot automatically when no devices are connected"* or *"Power saving mode"*.
4. **Turn the Hotspot ON**:
   - Toggle the hotspot switch to **Active**.
5. **Connect Peer Phones**:
   - Have friends connect their phones to your hotspot's Wi-Fi network.
   - Ignore any Android warnings stating *"Wi-Fi has no internet access"*—tap **Stay Connected**.
6. **Launch RoomBeat**:
   - The Host taps **Host Room**. Peers tap **Join Room** and scan the Host's QR code.
   - All packets flow directly over the local `192.168.43.x` link with near-zero latency.

---

## 🏠 Home & Venue Router Settings

If you are using RoomBeat on a residential or office Wi-Fi router, certain router security settings can block multicast packets between wireless clients. Follow these steps to ensure smooth discovery and playback:

### 1. Disable "AP Isolation" (Client Isolation)
- **The Issue**: Many routers enable "AP Isolation", "Station Isolation", or "Client Isolation" by default (especially on Guest networks). This feature blocks Wi-Fi devices from talking to one another.
- **The Fix**: In your router management portal (typically `192.168.1.1` or `192.168.0.1`), locate **Wireless Settings -> Advanced** and set **AP Isolation** to **Disabled**.

### 2. Enable "IGMP Snooping" / Multicast Support
- **The Issue**: Unmanaged multicast traffic can be treated as broadcast traffic by low-end switches, flooding all Wi-Fi channels and causing packet drops.
- **The Fix**: Under router LAN/Wireless settings, set **IGMP Snooping** and **Multicast Enhancement** to **Enabled**. This converts multicast frames to directed unicast at the hardware MAC layer, drastically improving Wi-Fi reliability.

### 3. WMM / QoS (Wireless Multimedia Quality of Service)
- **The Issue**: Heavy downloads on other computers can delay real-time audio datagrams.
- **The Fix**: Ensure **WMM (Wi-Fi Multimedia)** is enabled in your router. RoomBeat marks its UDP packets with real-time DSCP priority tags (`AF41` / `CS6`) so routers prioritize audio packets over background web downloads.

### 4. Port Forwarding is NOT Required
- Because RoomBeat communicates strictly within your local subnet, you **do not** need to forward any ports or configure UPnP on your router.

---

## 🔒 Android MulticastLock Architecture

Standard Android power management places the Wi-Fi chip into a power-saving multicast filter mode whenever the screen turns off. To prevent the audio stream from cutting out when your screen locks, RoomBeat automatically acquires Android's native hardware locks:

1. **`WifiManager.MulticastLock`**: Tells the wireless driver chip to accept incoming multicast packets on address `239.255.42.99`.
2. **`PowerManager.WakeLock`**: Prevents the CPU from entering deep sleep while an audio session is actively streaming.
3. **Foreground Service**: Ensures Android's Low Memory Killer (LMK) does not terminate the networking socket.

> [!IMPORTANT]
> Some device manufacturers (Xiaomi, Samsung, OnePlus) override these Android locks with proprietary battery-saving software. Follow our [Battery & Power Optimization Guide](./battery-and-power.md) to grant RoomBeat unrestricted background permissions.

---

*Continue to the [Battery & Power Optimization Guide](./battery-and-power.md) to ensure uninterrupted background audio streaming.*
