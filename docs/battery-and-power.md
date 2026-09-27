# 🔋 Battery & Power Optimization Guide

To deliver uninterrupted multi-device sound without stuttering, RoomBeat requires an open network socket and a continuous real-time audio thread even when your phone's display is turned off.

However, many Android device manufacturers (OEMs) ship aggressive proprietary battery management engines—often dubbed **"Don't Kill My App"** behaviors—that freeze network sockets, throttle high-efficiency CPU cores, or terminate background services the moment you lock your screen.

This guide provides step-by-step instructions to configure your specific device so RoomBeat plays flawlessly for hours with the screen asleep.

> 🌐 **Test without hardware constraints:** Check out the [RoomBeat Web Simulator](https://roombeat.sohamlabs.workers.dev) to see real-time phase synchronization running directly in your browser.

---

## 🛑 Why OEM Battery Killers Break Multi-Device Audio

Android devices employ two layers of battery management:

```
[ STANDARD AOSP DOZE MODE ] ──> Respects Foreground Services & MulticastLock [OK]
                                        │
[ AGGRESSIVE OEM TASK KILLERS ] ────────┴──> Kills Network Sockets & Throttles NDK Audio!
(MIUI, One UI, ColorOS, OriginOS)
```

1. **Standard Android Doze**: Automatically respects RoomBeat's active foreground audio service, `PowerManager.WakeLock`, and `WifiManager.MulticastLock`.
2. **Aggressive OEM Task Killers**: Custom manufacturer software (such as MIUI's battery saver or Samsung's Device Care) will ignore standard Android APIs unless you explicitly grant the app an exemption. When an OEM killer activates, it causes:
   - Complete silence or random audio dropouts when the screen locks.
   - Clock drift spikes causing peers to drop out of sync.
   - Sudden session disconnects.

---

## 📱 Manufacturer-Specific Configuration

Find your device manufacturer below and follow the quick setup steps:

---

### 1. Xiaomi / Redmi / POCO (MIUI & HyperOS)

Xiaomi devices feature the most aggressive background process restrictions in the Android ecosystem.

```
App Info ──> Battery Saver: "No restrictions" ──> Autostart: "ON" ──> Lock in Recents
```

1. Long-press the **RoomBeat** app icon on your home screen and tap **App Info** (or the small `(i)` icon).
2. Tap **Battery Saver** and select **No restrictions** (instead of the default *"MIUI Battery Saver (recommended)"*).
3. Under **Permissions**, toggle **Autostart** to **ON**.
4. Open your phone's **Recent Apps** tray, long-press the RoomBeat card, and tap the **Lock Icon** (padlock) so the OS never clears it during memory sweeps.

---

### 2. Samsung Galaxy (One UI 5, 6 & newer)

Samsung's Device Care periodically places active background streaming apps into deep sleep.

```
Settings ──> Device Care ──> Battery ──> Background Usage Limits ──> "Never sleeping apps"
```

1. Open **Settings -> Battery and Device Care -> Battery**.
2. Tap **Background usage limits**.
3. Tap **Never sleeping apps**, tap the **+** (plus) icon at the top right, select **RoomBeat**, and tap **Add**.
4. Return to your phone's main **Settings -> Apps -> RoomBeat -> Battery**.
5. Change the setting from *Optimized* to **Unrestricted**.

---

### 3. OnePlus / Oppo / Realme (OxygenOS & ColorOS)

ColorOS freezes background network sockets after 30 seconds of screen sleep if background permissions are restricted.

```
Settings ──> Apps ──> RoomBeat ──> Battery Usage ──> "Allow background activity: ON"
```

1. Open **Settings -> Apps -> App Management -> RoomBeat**.
2. Tap **Battery usage**.
3. Enable both **Allow background activity** and **Allow auto-launch**.
4. Go to **Settings -> Battery -> More settings -> Optimize battery use**.
5. Find **RoomBeat** and set it to **Don't optimize**.

---

### 4. Vivo / iQOO (Funtouch OS & OriginOS)

Vivo devices terminate UDP multicast sockets under their default "Smart Background Power" policy.

```
Settings ──> Battery ──> High Background Power Consumption ──> Toggle RoomBeat ON
```

1. Open **Settings -> Battery -> Background power consumption management** (or *High background power consumption*).
2. Locate **RoomBeat** in the list and toggle the switch to **Allow high background power consumption**.
3. Go to **Settings -> Apps -> Special app access -> Autostart** and ensure RoomBeat is permitted to run.

---

### 5. Asus / ROG Phone (ZenUI & ROG UI)

Asus PowerMaster aggressively stops background sockets to conserve battery for gaming.

1. Open the **Mobile Manager** or **PowerMaster** app.
2. Tap **Auto-start Manager**.
3. Locate **RoomBeat** and switch it to **Allowed**.
4. In **Settings -> Apps -> RoomBeat -> Battery**, select **Unrestricted**.

---

### 6. Google Pixel, Motorola, Sony, Nothing (Stock Android)

Pure Android devices follow standard AOSP guidelines and only require standard optimization exemption:

1. Open **Settings -> Apps -> RoomBeat -> App battery usage**.
2. Select **Unrestricted** (removes restrictions on background battery and networking).

---

## 🌡️ Outdoor Parties: Thermal Management & Sun Exposure

When hosting outdoor gatherings (such as beach parties, tailgates, or summer park hangouts), phones exposed to direct sunlight can heat up quickly:

- **Thermal Throttling Protection**: Modern Android CPUs throttle clock speeds when chip temperatures exceed $\sim 42^\circ\text{C}$. This throttling can introduce CPU scheduling latency in real-time audio threads.
- **Keep Phones in the Shade**: Never place your Host or Peer phones on hot asphalt, car dashboards, or direct sunlight. Place them under an umbrella, on a wooden table, or in the shade.
- **Dynamic Jitter Buffer**: RoomBeat monitors system thermal telemetry (`ThermalStatusMonitor`). If a device enters severe thermal throttling, RoomBeat automatically widens the jitter buffer from 40ms to 80ms to prevent acoustic dropouts without interrupting playback.

---

## ✅ Battery Health Impact

RoomBeat is engineered for extreme hardware efficiency:
- Written in **native C++20** with Google Oboe and NEON SIMD optimizations.
- Zero heap allocations in the audio thread hot path.
- CPU utilization is typically **$<8\%$** on modern mid-range and flagship chipsets.
- An average 5,000 mAh phone can stream continuous multi-device music for **8 to 12 hours** on a single charge.

---

*Encountering audio dropouts or connection issues? Check out the [Troubleshooting & FAQs Guide](./troubleshooting.md).*
