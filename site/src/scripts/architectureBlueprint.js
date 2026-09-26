/**
 * RoomBeat Architecture Blueprint & Tracing Beam Engine (v0.9.7)
 * 
 * Orchestrates:
 * 1. Aceternity Tracing Beam: Scroll-driven vertical glowing signal beam through the 4 stages
 *    (Capture -> Encode -> Multicast -> Playback) scrubbed smoothly by GSAP ScrollTrigger.
 * 2. Magic UI Terminal / React Bits Decrypted Text: Interactive 20ms Opus packet hex dump
 *    inspector with decipher scrambling, field dissecting, and hot-path registers.
 */

import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

gsap.registerPlugin(ScrollTrigger);

// ============================================================================
// 1. Stage Diagnostic Data Definitions
// ============================================================================
const STAGE_DATA = {
  1: {
    label: 'STAGE 01 // HIGH-RES AUDIO CAPTURE SUBSYSTEM',
    sublabel: 'Android 14+ AudioPlaybackCaptureConfiguration & Ring Buffer',
    registers: [
      { key: 'CAPTURE_API', val: 'AudioPlaybackCaptureConfiguration (UID: MediaProjection-FGS)' },
      { key: 'RING_BUFFER', val: 'Lock-free SPSC circular queue · 48,000 Hz · 16-bit PCM Stereo' },
      { key: 'INGEST_BURDEN', val: 'Buffer latency: 1.82ms · 0 dropped samples · Core affinity: #6' },
      { key: 'SAMPLE_RATE', val: '48,000 Hz (48 kHz native Android sample clock)' },
      { key: 'BITRATE_RAW', val: '1,536 kbps uncompressed linear PCM' }
    ]
  },
  2: {
    label: 'STAGE 02 // NATIVE OPUS ENCODER & TIME-TAGGER',
    sublabel: 'C++20 NDK libopus 20ms Framing & CLOCK_MONOTONIC Presentation Target',
    registers: [
      { key: 'OPUS_ENCODER', val: 'libopus 1.5.2 · 960 frame_size (20.0ms) · 128 kbps VBR · Audio signal' },
      { key: 'TIMESTAMP_GEN', val: 'T_target = clock_gettime(CLOCK_MONOTONIC_RAW) + 60,000,000 ns' },
      { key: 'HEADER_MAGIC', val: '0x52, 0x42, 0x01 (RB1 protocol datagram identifier)' },
      { key: 'SAMPLE_COUNT', val: '960 samples @ 48kHz (exact 20.00ms audio slice)' },
      { key: 'CPU_PROFILER', val: 'C++ execution: 0.84ms · 0 heap allocations on hot path' }
    ]
  },
  3: {
    label: 'STAGE 03 // HARDWARE UDP MULTICAST BROADCASTER',
    sublabel: 'O(1) Bandwidth Distribution Plane over Local Wi-Fi & Hotspot',
    registers: [
      { key: 'MULTICAST_TX', val: 'Datagram destination: 239.255.42.99:4242 · Header 24B + Payload 312B = 336B' },
      { key: 'WIFI_LOCK', val: 'WifiManager.MulticastLock ACTIVE · High-performance low-latency lock held' },
      { key: 'FANOUT_BURDEN', val: 'O(1) complexity: single kernel sendto() serves 2 to 64 peers simultaneously' },
      { key: 'TX_CADENCE', val: '50.0 Hz datagram burst (every 20.00ms ± 0.05ms)' },
      { key: 'CLOUD_RELAYS', val: '0 cloud hops · 0 external internet dependency · 100% air-gapped LAN' }
    ]
  },
  4: {
    label: 'STAGE 04 // HARDWARE-SCHEDULED PLAYBACK & RESAMPLER',
    sublabel: 'Oboe AAudio MMAP Low-Latency Buffer & NDK Fractional Drift Slew',
    registers: [
      { key: 'OBOE_AAUDIO', val: 'AudioStreamCallback onExclusive MMAP buffer · Burst: 192 frames @ 48kHz' },
      { key: 'JITTER_BUFFER', val: 'Adaptive 60ms headroom · Native Opus Packet Loss Concealment (PLC)' },
      { key: 'RESAMPLER_LOCK', val: 'Catmull-Rom fractional speed adjustment ±0.04% · Phase drift: <0.38ms' },
      { key: 'OUTPUT_ECHO', val: '0ms audible acoustic chorus · Perfect constructive loudspeaker summing' },
      { key: 'MMAP_STATE', val: 'Exclusive hardware DMA access · Bypasses AudioFlinger mix overhead' }
    ]
  }
};

// ============================================================================
// 2. Aceternity Tracing Beam Controller
// ============================================================================
export function initTracingBeam() {
  const container = document.getElementById('architecture-blueprint-container');
  const beamSvg = document.getElementById('tracing-beam-svg');
  const beamPath = document.getElementById('tracing-beam-path');
  const beamTrack = document.getElementById('tracing-beam-track');
  const beamHead = document.getElementById('tracing-beam-head');
  const stagePorts = document.querySelectorAll('.tracing-stage-port');
  const stageCards = document.querySelectorAll('.blueprint-stage-card');

  if (!container || !beamSvg || !beamPath) return;

  const isReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Function to re-calculate SVG path height and points
  function updateBeamGeometry() {
    const containerRect = container.getBoundingClientRect();
    const portNodes = Array.from(stagePorts);

    if (portNodes.length === 0) return;

    const firstPortRect = portNodes[0].getBoundingClientRect();
    const lastPortRect = portNodes[portNodes.length - 1].getBoundingClientRect();

    const startY = (firstPortRect.top + firstPortRect.height / 2) - containerRect.top;
    const endY = (lastPortRect.top + lastPortRect.height / 2) - containerRect.top;
    const x = firstPortRect.left + firstPortRect.width / 2 - containerRect.left;

    // Build straight conduit path
    const pathData = `M ${x} ${startY} L ${x} ${endY}`;
    beamPath.setAttribute('d', pathData);
    if (beamTrack) beamTrack.setAttribute('d', pathData);

    const pathLength = beamPath.getTotalLength();
    beamPath.style.strokeDasharray = `${pathLength}`;
    
    return { pathLength, startY, endY, x };
  }

  const geom = updateBeamGeometry();
  if (!geom) return;

  if (isReducedMotion) {
    beamPath.style.strokeDashoffset = '0';
    if (beamHead) {
      const endPoint = beamPath.getPointAtLength(geom.pathLength);
      beamHead.setAttribute('cx', String(endPoint.x));
      beamHead.setAttribute('cy', String(endPoint.y));
    }
    stagePorts.forEach(port => port.classList.add('is-active'));
    stageCards.forEach(card => card.classList.add('is-active'));
    return;
  }

  // Initial state: hidden
  beamPath.style.strokeDashoffset = String(geom.pathLength);
  if (beamHead) {
    const startPoint = beamPath.getPointAtLength(0);
    beamHead.setAttribute('cx', String(startPoint.x));
    beamHead.setAttribute('cy', String(startPoint.y));
  }

  let activeStage = 1;

  function setActiveStage(stageNum) {
    if (activeStage === stageNum) return;
    activeStage = stageNum;

    stagePorts.forEach(port => {
      const pStage = Number(port.getAttribute('data-stage'));
      if (pStage <= stageNum) {
        port.classList.add('is-active');
      } else {
        port.classList.remove('is-active');
      }
    });

    stageCards.forEach(card => {
      const cStage = Number(card.getAttribute('data-stage'));
      if (cStage === stageNum) {
        card.classList.add('is-active');
      } else {
        card.classList.remove('is-active');
      }
    });

    // Notify terminal about active stage
    updateDiagnosticStage(stageNum);
  }

  // ScrollTrigger for Aceternity Tracing Beam Scrub
  const trigger = ScrollTrigger.create({
    trigger: '#architecture-blueprint-container',
    start: 'top 70%',
    end: 'bottom 80%',
    scrub: 0.35,
    onUpdate: (self) => {
      const currentLength = geom.pathLength;
      const progress = self.progress;
      const dashoffset = currentLength * (1 - progress);
      beamPath.style.strokeDashoffset = String(dashoffset);

      if (beamHead) {
        const point = beamPath.getPointAtLength(progress * currentLength);
        beamHead.setAttribute('cx', String(point.x));
        beamHead.setAttribute('cy', String(point.y));
        beamHead.style.opacity = progress > 0.01 ? '1' : '0';
      }

      // Compute which stage is illuminated
      if (progress < 0.25) {
        setActiveStage(1);
      } else if (progress < 0.50) {
        setActiveStage(2);
      } else if (progress < 0.75) {
        setActiveStage(3);
      } else {
        setActiveStage(4);
      }
    }
  });

  // Re-calculate on window resize
  window.addEventListener('resize', () => {
    updateBeamGeometry();
    trigger.refresh();
  });

  // Interactive Card Clicks
  stageCards.forEach(card => {
    card.addEventListener('click', () => {
      const s = Number(card.getAttribute('data-stage'));
      if (s) {
        setActiveStage(s);
        // Also scroll gently if needed or switch terminal
        updateDiagnosticStage(s);
      }
    });
  });
}

// ============================================================================
// 3. Magic UI Terminal & React Bits Decrypted Hex Dump Inspector
// ============================================================================
let packetSeq = 102220;
let isStreaming = true;
let streamInterval = null;

// Template 20ms Opus Packet Frame Structure
function generatePacketData() {
  packetSeq += 1;
  const nowNs = Math.floor(performance.now() * 1000000) + 1737458000000000;
  const targetNs = nowNs + 60000000; // +60ms lead time

  // Convert seq and target to hex
  const seqHex = packetSeq.toString(16).padStart(8, '0').toUpperCase();
  const targetHex = targetNs.toString(16).padStart(16, '0').toUpperCase();

  return {
    seq: packetSeq,
    seqHex: [seqHex.slice(0, 2), seqHex.slice(2, 4), seqHex.slice(4, 6), seqHex.slice(6, 8)],
    targetNs,
    targetHex: [
      targetHex.slice(0, 2), targetHex.slice(2, 4), targetHex.slice(4, 6), targetHex.slice(6, 8),
      targetHex.slice(8, 10), targetHex.slice(10, 12), targetHex.slice(12, 14), targetHex.slice(14, 16)
    ],
    sampleCount: 960,
    sampleCountHex: ['03', 'C0'],
    payloadLen: 312,
    payloadLenHex: ['01', '38']
  };
}

export function initPacketInspector() {
  const terminalBody = document.getElementById('terminal-hex-body');
  const btnToggleStream = document.getElementById('btn-toggle-stream');
  const btnNextPacket = document.getElementById('btn-next-packet');
  const btnCopyHex = document.getElementById('btn-copy-hex');
  const streamStatusLed = document.getElementById('stream-status-led');
  const streamStatusText = document.getElementById('stream-status-text');
  const packetMetaSeq = document.getElementById('packet-meta-seq');
  const packetMetaTime = document.getElementById('packet-meta-time');
  const tabHexStream = document.getElementById('tab-hex-stream');
  const tabStageRegs = document.getElementById('tab-stage-regs');
  const viewHexStream = document.getElementById('view-hex-stream');
  const viewStageRegs = document.getElementById('view-stage-regs');
  const fieldInspectorCard = document.getElementById('field-inspector-card');

  if (!terminalBody) return;

  // Decryption Scramble Helper (React Bits Decrypted Text style)
  function scrambleText(element, finalText, callback) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      element.textContent = finalText;
      if (callback) callback();
      return;
    }

    const hexChars = '0123456789ABCDEF';
    let frame = 0;
    const maxFrames = 10;

    const interval = setInterval(() => {
      frame++;
      if (frame >= maxFrames) {
        clearInterval(interval);
        element.textContent = finalText;
        if (callback) callback();
      } else {
        let scrambled = '';
        for (let i = 0; i < finalText.length; i++) {
          if (finalText[i] === ' ' || finalText[i] === '\n') {
            scrambled += finalText[i];
          } else {
            scrambled += hexChars[Math.floor(Math.random() * hexChars.length)];
          }
        }
        element.textContent = scrambled;
      }
    }, 28);
  }

  function renderPacket(animateScramble = false) {
    const data = generatePacketData();

    if (packetMetaSeq) packetMetaSeq.textContent = `#${data.seq.toLocaleString()}`;
    if (packetMetaTime) packetMetaTime.textContent = `${(data.targetNs / 1e9).toFixed(6)}s`;

    // Hex Rows Structure:
    // Offset 0000: [0x52 0x42 0x01 (Header)] [0x01 (Ver)] [seq (4B)] [t_target (8B)]
    // Offset 0010: [0x03 0xC0 (Samples)] [0x02 0x00 (2-Ch)] [0x01 0x38 (Len)] [Opus payload...]
    const row0 = [
      { hex: '52', field: 'header', label: 'Magic RB1 Header' },
      { hex: '42', field: 'header', label: 'Magic RB1 Header' },
      { hex: '01', field: 'header', label: 'Magic RB1 Header' },
      { hex: '00', field: 'proto', label: 'Protocol Flags' },
      { hex: data.seqHex[0], field: 'seq', label: `Sequence Counter (#${data.seq})` },
      { hex: data.seqHex[1], field: 'seq', label: `Sequence Counter (#${data.seq})` },
      { hex: data.seqHex[2], field: 'seq', label: `Sequence Counter (#${data.seq})` },
      { hex: data.seqHex[3], field: 'seq', label: `Sequence Counter (#${data.seq})` },
      { hex: data.targetHex[0], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[1], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[2], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[3], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[4], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[5], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[6], field: 'timestamp', label: 'T_target Monotonic Deadline' },
      { hex: data.targetHex[7], field: 'timestamp', label: 'T_target Monotonic Deadline' },
    ];

    const row1 = [
      { hex: data.sampleCountHex[0], field: 'samples', label: '960 Samples (20ms @ 48kHz)' },
      { hex: data.sampleCountHex[1], field: 'samples', label: '960 Samples (20ms @ 48kHz)' },
      { hex: '02', field: 'channels', label: '2-Channel Stereo' },
      { hex: '00', field: 'channels', label: 'Channel Flags' },
      { hex: data.payloadLenHex[0], field: 'length', label: 'Payload Length: 312 Bytes' },
      { hex: data.payloadLenHex[1], field: 'length', label: 'Payload Length: 312 Bytes' },
      { hex: 'F8', field: 'payload', label: 'Opus Bitstream (TOC Byte: Config 31)' },
      { hex: '7D', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '22', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'A1', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '8E', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '43', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'B9', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '10', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '4F', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'C7', field: 'payload', label: 'Opus Audio Payload' },
    ];

    const row2 = [
      { hex: '3A', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '91', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '00', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'E5', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '26', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '8A', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '52', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '09', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'DD', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '14', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '68', field: 'payload', label: 'Opus Audio Payload' },
      { hex: 'FE', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '99', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '42', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '71', field: 'payload', label: 'Opus Audio Payload' },
      { hex: '03', field: 'payload', label: 'Opus Audio Payload' },
    ];

    const rows = [
      { offset: '0000', bytes: row0, ascii: 'RB..seq..target..' },
      { offset: '0010', bytes: row1, ascii: '..stereo..Opus..' },
      { offset: '0020', bytes: row2, ascii: ':.%..R...h..Bq.' },
    ];

    terminalBody.innerHTML = '';

    rows.forEach(r => {
      const rowEl = document.createElement('div');
      rowEl.className = 'flex flex-wrap items-center gap-x-3 gap-y-1 hover:bg-[#13161C] px-2 py-1 rounded transition-colors';

      // Offset
      const offsetSpan = document.createElement('span');
      offsetSpan.className = 'text-text-dim w-12 shrink-0 select-none';
      offsetSpan.textContent = r.offset;
      rowEl.appendChild(offsetSpan);

      // Bytes container
      const bytesContainer = document.createElement('div');
      bytesContainer.className = 'flex items-center gap-1.5 flex-1 min-w-[280px] font-mono font-semibold';

      r.bytes.forEach(b => {
        const byteSpan = document.createElement('span');
        byteSpan.className = `byte-token px-1 py-0.5 rounded cursor-pointer transition-all duration-150 ${getFieldColor(b.field)}`;
        byteSpan.setAttribute('data-field', b.field);
        byteSpan.setAttribute('data-label', b.label);
        byteSpan.setAttribute('data-hex', b.hex);

        if (animateScramble) {
          scrambleText(byteSpan, b.hex);
        } else {
          byteSpan.textContent = b.hex;
        }

        // Hover / click to inspect field
        byteSpan.addEventListener('mouseenter', () => inspectField(b.field, b.label, b.hex));
        byteSpan.addEventListener('click', () => inspectField(b.field, b.label, b.hex));

        bytesContainer.appendChild(byteSpan);
      });
      rowEl.appendChild(bytesContainer);

      // ASCII Dump
      const asciiSpan = document.createElement('span');
      asciiSpan.className = 'text-text-dim/80 text-[11px] w-28 shrink-0 select-none hidden sm:inline-block border-l border-border-milled/60 pl-3 font-mono';
      asciiSpan.textContent = `|${r.ascii}|`;
      rowEl.appendChild(asciiSpan);

      terminalBody.appendChild(rowEl);
    });
  }

  function getFieldColor(field) {
    switch (field) {
      case 'header':
        return 'bg-sync-green/20 text-sync-green hover:bg-sync-green/30 border border-sync-green/40';
      case 'seq':
        return 'bg-network-cyan/20 text-network-cyan hover:bg-network-cyan/30 border border-network-cyan/40';
      case 'timestamp':
        return 'bg-sync-amber/20 text-sync-amber hover:bg-sync-amber/30 border border-sync-amber/40';
      case 'samples':
        return 'bg-signal-orange/20 text-signal-orange hover:bg-signal-orange/30 border border-signal-orange/40';
      case 'channels':
      case 'length':
        return 'bg-surface-elevated text-bone hover:border-border-active border border-border-milled';
      case 'payload':
      default:
        return 'text-text-muted hover:text-bone hover:bg-surface-elevated/60';
    }
  }

  function inspectField(field, label, hexVal) {
    if (!fieldInspectorCard) return;

    const descriptions = {
      header: {
        title: 'MAGIC HEADER [0x52, 0x42, 0x01]',
        desc: 'RoomBeat Datagram Protocol signature ("RB\\x01"). Evaluated in 3 CPU instructions; foreign UDP broadcasts are instantly dropped at socket ingress.',
        spec: 'Size: 3 Bytes · Value: ASCII "RB1" · Protocol v1'
      },
      seq: {
        title: 'SEQUENCE COUNTER (uint32_t)',
        desc: 'Monotonically incrementing packet index. Enables the receiver jitter buffer to detect dropped frames, trigger native Opus Packet Loss Concealment (PLC), and prevent out-of-order jitter.',
        spec: `Value: #${packetSeq.toLocaleString()} · Step: +1 per 20ms frame · 50 pkts/sec`
      },
      timestamp: {
        title: 'TARGET PRESENTATION CLOCK (uint64_t)',
        desc: 'Nanosecond monotonic presentation timestamp (T_target). Derived from CLOCK_MONOTONIC_RAW + 60.0ms scheduled buffer horizon. AAudio queues frame until audio clock matches target.',
        spec: 'Clock: CLOCK_MONOTONIC_RAW · Resolution: Nanoseconds · Phase lock: <0.5ms'
      },
      samples: {
        title: 'SAMPLE COUNT [0x03, 0xC0] = 960 SAMPLES',
        desc: 'Exactly 960 stereo PCM audio samples per channel at 48,000 Hz (20.00 milliseconds). Sized to match native Oboe AAudio MMAP hardware DMA burst bursts.',
        spec: 'Rate: 48 kHz · Duration: 20.0ms · Channels: 2 (Stereo)'
      },
      length: {
        title: 'PAYLOAD LENGTH [0x01, 0x38] = 312 BYTES',
        desc: 'Byte size of the variable-bitrate (VBR) Opus bitstream contained in this datagram. Total packet size = 24B header + 312B payload = 336B total.',
        spec: 'Header: 24B · Payload: 312B · Frame Bandwidth: ~124.8 kbps'
      },
      payload: {
        title: 'OPUS ENCODED AUDIO BITSTREAM',
        desc: 'High-fidelity audio stream compressed via native C++ libopus. Features Forward Error Correction (FEC) and Silk/Celt dual-mode compression.',
        spec: 'Codec: libopus 1.5.2 · Mode: Audio · CBR/VBR Hybrid'
      }
    };

    const d = descriptions[field] || {
      title: `${field.toUpperCase()} FIELD`,
      desc: label,
      spec: `Active Hex Byte: 0x${hexVal}`
    };

    fieldInspectorCard.innerHTML = `
      <div class="flex items-center justify-between border-b border-border-milled/70 pb-2 mb-2">
        <span class="font-mono text-xs font-bold text-bone flex items-center gap-2">
          <span class="w-1.5 h-1.5 rounded-full bg-sync-green animate-status-blink"></span>
          ${d.title}
        </span>
        <span class="font-mono text-[10px] text-sync-green bg-sync-green/10 border border-sync-green/30 px-1.5 py-0.5 rounded-[2px]">FIELD ACTIVE</span>
      </div>
      <p class="font-sans text-xs text-text-muted leading-relaxed mb-2">${d.desc}</p>
      <div class="font-mono text-[11px] text-text-dim bg-surface-recessed p-2 rounded border border-border-milled/60">${d.spec}</div>
    `;
  }

  // Stream Interval Controller
  function startStream() {
    if (streamInterval) clearInterval(streamInterval);
    isStreaming = true;
    if (streamStatusLed) {
      streamStatusLed.className = 'w-2 h-2 rounded-full bg-sync-green animate-status-blink';
    }
    if (streamStatusText) streamStatusText.textContent = '50.0 Hz RX (20ms)';
    if (btnToggleStream) btnToggleStream.textContent = 'PAUSE ||';

    streamInterval = setInterval(() => {
      renderPacket(false);
    }, 2000); // 2s periodic update in demo to avoid CPU drain
  }

  function pauseStream() {
    if (streamInterval) clearInterval(streamInterval);
    isStreaming = false;
    if (streamStatusLed) {
      streamStatusLed.className = 'w-2 h-2 rounded-full bg-sync-amber';
    }
    if (streamStatusText) streamStatusText.textContent = 'STREAM PAUSED';
    if (btnToggleStream) btnToggleStream.textContent = 'RESUME ▶';
  }

  btnToggleStream?.addEventListener('click', () => {
    if (isStreaming) {
      pauseStream();
    } else {
      startStream();
    }
  });

  btnNextPacket?.addEventListener('click', () => {
    pauseStream();
    renderPacket(true);
  });

  btnCopyHex?.addEventListener('click', async () => {
    const hexText = '52 42 01 00 00 01 8F 4C 00 00 01 94 88 3A 7F 00 03 C0 02 00 01 38 F8 7D 22 A1 8E 43 B9 10 4F C7 3A 91 00 E5 26 8A 52 09 DD 14 68 FE 99 42 71 03';
    try {
      await navigator.clipboard.writeText(hexText);
      const originalText = btnCopyHex.textContent;
      btnCopyHex.textContent = 'COPIED!';
      setTimeout(() => {
        btnCopyHex.textContent = originalText;
      }, 1800);
    } catch {
      // Fallback
    }
  });

  // Tab Switching: Hex Stream vs Stage Hot-Path Registers
  tabHexStream?.addEventListener('click', () => {
    tabHexStream.classList.add('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabHexStream.classList.remove('text-text-dim');
    tabStageRegs?.classList.remove('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabStageRegs?.classList.add('text-text-dim');
    viewHexStream?.classList.remove('hidden');
    viewStageRegs?.classList.add('hidden');
  });

  tabStageRegs?.addEventListener('click', () => {
    tabStageRegs.classList.add('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabStageRegs.classList.remove('text-text-dim');
    tabHexStream?.classList.remove('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabHexStream?.classList.add('text-text-dim');
    viewStageRegs?.classList.remove('hidden');
    viewHexStream?.classList.add('hidden');
  });

  // Initial render with scramble
  renderPacket(true);
  startStream();
}

export function updateDiagnosticStage(stageNum) {
  const data = STAGE_DATA[stageNum];
  if (!data) return;

  const titleEl = document.getElementById('registers-stage-title');
  const sublabelEl = document.getElementById('registers-stage-sublabel');
  const listEl = document.getElementById('registers-stage-list');

  if (titleEl) titleEl.textContent = data.label;
  if (sublabelEl) sublabelEl.textContent = data.sublabel;

  if (listEl) {
    listEl.innerHTML = '';
    data.registers.forEach(reg => {
      const row = document.createElement('div');
      row.className = 'flex flex-col sm:flex-row sm:items-center justify-between gap-1 p-2 rounded bg-surface-recessed border border-border-milled/60 font-mono text-xs';
      row.innerHTML = `
        <span class="text-signal-orange font-bold">${reg.key}:</span>
        <span class="text-bone">${reg.val}</span>
      `;
      listEl.appendChild(row);
    });
  }
}

// Support Astro view transitions / initial page load
export function initArchitectureModule() {
  initTracingBeam();
  initPacketInspector();
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initArchitectureModule);
  } else {
    initArchitectureModule();
  }
}
