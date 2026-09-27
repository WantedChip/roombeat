# Security Policy

## Supported Versions

We take the security of RoomBeat and our users' privacy very seriously. Because RoomBeat processes audio frames in memory and operates over local networks, security updates and patches are prioritized.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Security Principles & Architecture

RoomBeat is engineered with a strict **Privacy and Security by Design** philosophy:

1. **Zero External Telemetry / Zero Cloud Backends**:
   - RoomBeat contains **0 analytics trackers, 0 advertising SDKs, 0 user tracking identifiers, and 0 remote cloud backends**.
   - No data is ever transmitted to external internet servers.
2. **Local Network Subnet Isolation**:
   - All audio streaming and presentation clock synchronization take place exclusively over local Layer 2 UDP Multicast (`239.255.42.99:4242`) and local TCP / WebSocket connections.
   - Multicast audio packets are non-routable beyond the local router or portable Wi-Fi hotspot gateway.
3. **Ephemeral Volatile RAM Audio Processing**:
   - System audio ingested via Android's `AudioPlaybackCaptureConfiguration` (`mediaProjection`) and local storage decoded frames are held strictly in transient memory (circular ring buffers) and immediately discarded after rendering.
   - Captured audio is **never written to persistent disk storage, cached in temporary files, or uploaded**.
4. **Isolated IPC Communication**:
   - Spotify App Remote communication utilizes Android's secured binder IPC directly between the official Spotify client and RoomBeat without intermediate network hops.

---

## Reporting a Vulnerability

If you discover a security vulnerability or privacy flaw in RoomBeat, please report it privately:

**DO NOT file a public GitHub issue.**

Please report all security concerns directly via email to:
📧 **ty121rt@gmail.com**

### Please include in your report:
- A description of the issue and potential security/privacy impact.
- Step-by-step instructions to reproduce the vulnerability (or proof-of-concept code).
- Device model, Android OS version, and network topology tested.
- Any suggested remediations or mitigations.

### Response Timeline
- **Initial Acknowledgment**: Within 48 hours of receipt.
- **Triage & Assessment**: Within 5 business days with status and reproduction validation.
- **Fix & Public Advisory**: Coordinated disclosure after a patched release is published.

Thank you for responsibly reporting vulnerabilities and helping keep RoomBeat secure for everyone!
