/**
 * RoomBeat Tactile Acoustic Industrial Radar / Phase Oscilloscope Canvas
 * Renders high-precision polar radar sweep, node target vectors, and Lissajous phase alignment.
 */

export class RadarCanvas {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {import('./audioSimulator.js').AudioSimulator} audioSimulator
   */
  constructor(canvas, audioSimulator) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.audioSim = audioSimulator;

    this.rafId = null;
    this.sweepAngle = 0;
    this.sweepSpeed = 0.025; // radians per frame (~2.5s per revolution)
    this.dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
    this.width = 0;
    this.height = 0;
    this.centerX = 0;
    this.centerY = 0;
    this.maxRadius = 0;

    // Smoothed visual positions for smooth animation of delays
    this.visualDelays = [0, 0, 0, 0];
    this.lockPulsePhase = 0;

    // Node angles on polar grid
    this.nodeAngles = [
      0,                  // Host: center
      (Math.PI / 4),      // Peer A: 45 deg
      (Math.PI * 0.9),    // Peer B: 162 deg
      (Math.PI * 1.55),   // Peer C: 279 deg
    ];

    this.resizeObserver = null;
    this._handleResize = this._handleResize.bind(this);
    this._render = this._render.bind(this);
  }

  init() {
    this._handleResize();
    if (typeof window !== 'undefined' && 'ResizeObserver' in window) {
      this.resizeObserver = new ResizeObserver(() => this._handleResize());
      this.resizeObserver.observe(this.canvas.parentElement || this.canvas);
    } else if (typeof window !== 'undefined') {
      window.addEventListener('resize', this._handleResize);
    }
    this.start();
  }

  destroy() {
    this.stop();
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    } else if (typeof window !== 'undefined') {
      window.removeEventListener('resize', this._handleResize);
    }
  }

  start() {
    if (this.rafId) return;
    this.rafId = requestAnimationFrame(this._render);
  }

  stop() {
    if (this.rafId) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  _handleResize() {
    if (!this.canvas) return;
    const rect = this.canvas.getBoundingClientRect();
    this.dpr = window.devicePixelRatio || 1;
    this.width = Math.floor(rect.width);
    this.height = Math.floor(rect.height);

    if (this.width <= 0 || this.height <= 0) return;

    this.canvas.width = Math.floor(this.width * this.dpr);
    this.canvas.height = Math.floor(this.height * this.dpr);

    this.ctx.resetTransform?.();
    this.ctx.scale(this.dpr, this.dpr);

    this.centerX = this.width / 2;
    this.centerY = this.height / 2;
    // Leave margin for labels and outer tick bezel
    this.maxRadius = Math.max(20, Math.min(this.centerX, this.centerY) - 28);
  }

  _render(timestamp) {
    this.rafId = requestAnimationFrame(this._render);

    if (!this.ctx || this.width <= 0 || this.height <= 0) return;

    const telemetry = this.audioSim.getTelemetry();
    const isPlaying = telemetry.isPlaying;
    const isLocked = telemetry.isLocked;

    // Advance sweep angle
    this.sweepAngle = (this.sweepAngle + this.sweepSpeed) % (Math.PI * 2);
    this.lockPulsePhase = (this.lockPulsePhase + 0.04) % (Math.PI * 2);

    // Smooth visual delays toward target
    telemetry.channels.forEach((ch, idx) => {
      const target = ch.delayMs;
      this.visualDelays[idx] += (target - this.visualDelays[idx]) * 0.15;
    });

    // Clear background: Recessed deep obsidian cavity
    this.ctx.fillStyle = '#07080A';
    this.ctx.fillRect(0, 0, this.width, this.height);

    // 1. Draw subtle background coordinate grid
    this._drawGrid();

    // 2. Draw Polar Range Rings and Angular Bezel
    this._drawPolarReticle(isLocked);

    // 3. Draw Oscilloscope / Lissajous Phase Core
    this._drawPhaseScope(telemetry);

    // 4. Draw Radar Sweep and Phosphor Trail
    this._drawRadarSweep(isLocked, isPlaying);

    // 5. Draw 4 Node Blips, Vectors, and Telemetry Tags
    this._drawNodes(telemetry, isLocked);

    // 6. Draw HUD Readout & Status Stamp
    this._drawHudOverlays(telemetry, isLocked);
  }

  _drawGrid() {
    this.ctx.save();
    this.ctx.strokeStyle = 'rgba(38, 42, 53, 0.4)';
    this.ctx.lineWidth = 1;

    // Center Crosshairs
    this.ctx.setLineDash([3, 4]);
    this.ctx.beginPath();
    this.ctx.moveTo(this.centerX - this.maxRadius - 10, this.centerY);
    this.ctx.lineTo(this.centerX + this.maxRadius + 10, this.centerY);
    this.ctx.moveTo(this.centerX, this.centerY - this.maxRadius - 10);
    this.ctx.lineTo(this.centerX, this.centerY + this.maxRadius + 10);
    this.ctx.stroke();
    this.ctx.setLineDash([]);

    // 45-degree diagonal guide lines
    this.ctx.strokeStyle = 'rgba(38, 42, 53, 0.25)';
    this.ctx.beginPath();
    const d = this.maxRadius * 0.9;
    this.ctx.moveTo(this.centerX - d, this.centerY - d);
    this.ctx.lineTo(this.centerX + d, this.centerY + d);
    this.ctx.moveTo(this.centerX - d, this.centerY + d);
    this.ctx.lineTo(this.centerX + d, this.centerY - d);
    this.ctx.stroke();

    this.ctx.restore();
  }

  _drawPolarReticle(isLocked) {
    const ctx = this.ctx;
    ctx.save();

    // Concentric range rings: 5ms, 15ms, 25ms, 40ms
    const ranges = [
      { ms: 5, r: this.maxRadius * 0.25 },
      { ms: 15, r: this.maxRadius * 0.50 },
      { ms: 25, r: this.maxRadius * 0.75 },
      { ms: 40, r: this.maxRadius * 1.00 },
    ];

    ranges.forEach((range, idx) => {
      ctx.beginPath();
      ctx.arc(this.centerX, this.centerY, range.r, 0, Math.PI * 2);

      if (idx === 0) {
        // Inner 5ms Lock Boundary Zone
        ctx.strokeStyle = isLocked ? 'rgba(0, 229, 153, 0.45)' : 'rgba(255, 184, 0, 0.35)';
        ctx.lineWidth = 1.5;
      } else {
        ctx.strokeStyle = 'rgba(38, 42, 53, 0.65)';
        ctx.lineWidth = 1;
      }
      ctx.stroke();

      // Range text labels
      ctx.fillStyle = idx === 0 && isLocked ? '#00E599' : '#5D6475';
      ctx.font = '9px "JetBrains Mono", monospace';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'bottom';
      ctx.fillText(`${range.ms}ms`, this.centerX + 4, this.centerY - range.r + 11);
    });

    // Outer Degree Bezel with Tick Marks (every 15 degrees)
    const outerR = this.maxRadius + 4;
    for (let deg = 0; deg < 360; deg += 15) {
      const rad = (deg * Math.PI) / 180;
      const isMajor = deg % 45 === 0;
      const tickLen = isMajor ? 6 : 3;

      const x1 = this.centerX + Math.cos(rad) * outerR;
      const y1 = this.centerY + Math.sin(rad) * outerR;
      const x2 = this.centerX + Math.cos(rad) * (outerR + tickLen);
      const y2 = this.centerY + Math.sin(rad) * (outerR + tickLen);

      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.strokeStyle = isMajor ? 'rgba(157, 165, 180, 0.6)' : 'rgba(62, 68, 84, 0.5)';
      ctx.lineWidth = 1;
      ctx.stroke();

      if (isMajor && this.width > 340) {
        const textR = outerR + 13;
        const tx = this.centerX + Math.cos(rad) * textR;
        const ty = this.centerY + Math.sin(rad) * textR;
        ctx.font = '8px "JetBrains Mono", monospace';
        ctx.fillStyle = '#5D6475';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(`${deg}°`, tx, ty);
      }
    }

    ctx.restore();
  }

  _drawPhaseScope(telemetry) {
    if (!telemetry.isPlaying) return;

    const ctx = this.ctx;
    ctx.save();

    // Use Host analyser data for acoustic phase waveform trace
    const host = telemetry.channels[0];
    const peerA = telemetry.channels[1];
    if (!host.analyser) {
      ctx.restore();
      return;
    }

    const hostData = host.timeData;
    const peerData = peerA ? peerA.timeData : hostData;
    const isLocked = telemetry.isLocked;

    // Draw Lissajous X-Y phase correlation figure inside center zone
    ctx.beginPath();
    const scopeRadius = this.maxRadius * 0.22;
    const step = 4;

    for (let i = 0; i < hostData.length; i += step) {
      const xVal = hostData[i];
      const yVal = peerData[i] || hostData[i];

      const px = this.centerX + xVal * scopeRadius * 1.5;
      const py = this.centerY + yVal * scopeRadius * 1.5;

      if (i === 0) {
        ctx.moveTo(px, py);
      } else {
        ctx.lineTo(px, py);
      }
    }

    ctx.strokeStyle = isLocked ? 'rgba(0, 229, 153, 0.55)' : 'rgba(255, 85, 0, 0.55)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.restore();
  }

  _drawRadarSweep(isLocked, isPlaying) {
    const ctx = this.ctx;
    ctx.save();

    const sweepColor = isLocked ? '#00E599' : '#FFB800';
    const arcTrail = Math.PI * 0.28; // ~50 degrees trail

    // Phosphor Trail Gradient
    const gradient = ctx.createRadialGradient(
      this.centerX,
      this.centerY,
      0,
      this.centerX,
      this.centerY,
      this.maxRadius
    );

    if (isLocked) {
      gradient.addColorStop(0, 'rgba(0, 229, 153, 0.18)');
      gradient.addColorStop(1, 'rgba(0, 229, 153, 0.02)');
    } else {
      gradient.addColorStop(0, 'rgba(255, 184, 0, 0.18)');
      gradient.addColorStop(1, 'rgba(255, 184, 0, 0.02)');
    }

    // Draw sweep sector trail
    ctx.beginPath();
    ctx.moveTo(this.centerX, this.centerY);
    ctx.arc(
      this.centerX,
      this.centerY,
      this.maxRadius,
      this.sweepAngle - arcTrail,
      this.sweepAngle
    );
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();

    // High-voltage Sweep Arm Line
    const sweepX = this.centerX + Math.cos(this.sweepAngle) * this.maxRadius;
    const sweepY = this.centerY + Math.sin(this.sweepAngle) * this.maxRadius;

    ctx.beginPath();
    ctx.moveTo(this.centerX, this.centerY);
    ctx.lineTo(sweepX, sweepY);
    ctx.strokeStyle = sweepColor;
    ctx.lineWidth = 1.75;
    ctx.shadowColor = sweepColor;
    ctx.shadowBlur = 6;
    ctx.stroke();

    ctx.restore();
  }

  _drawNodes(telemetry, isLocked) {
    const ctx = this.ctx;
    ctx.save();

    // 1. Host Node: Anchored at (centerX, centerY)
    const host = telemetry.channels[0];
    const hostRms = host ? host.rms : 0;
    const hostPulse = 4 + hostRms * 8;

    // Glowing target center reticle
    ctx.beginPath();
    ctx.arc(this.centerX, this.centerY, hostPulse, 0, Math.PI * 2);
    ctx.fillStyle = isLocked ? 'rgba(0, 229, 153, 0.2)' : 'rgba(255, 85, 0, 0.2)';
    ctx.fill();

    ctx.beginPath();
    ctx.arc(this.centerX, this.centerY, 3.5, 0, Math.PI * 2);
    ctx.fillStyle = isLocked ? '#00E599' : '#FF5500';
    ctx.shadowColor = ctx.fillStyle;
    ctx.shadowBlur = 8;
    ctx.fill();
    ctx.shadowBlur = 0;

    // Host Label
    ctx.font = 'bold 9px "JetBrains Mono", monospace';
    ctx.fillStyle = '#F2F4F8';
    ctx.textAlign = 'center';
    ctx.fillText('HOST [REF]', this.centerX, this.centerY + 16);

    // If locked, draw pulsating concentric lock brackets
    if (isLocked) {
      const lockRadius = 14 + Math.sin(this.lockPulsePhase) * 2;
      ctx.strokeStyle = '#00E599';
      ctx.lineWidth = 1.5;

      // 4 corner reticle brackets
      const bLen = 4;
      // Top-left
      ctx.beginPath();
      ctx.moveTo(this.centerX - lockRadius, this.centerY - lockRadius + bLen);
      ctx.lineTo(this.centerX - lockRadius, this.centerY - lockRadius);
      ctx.lineTo(this.centerX - lockRadius + bLen, this.centerY - lockRadius);
      // Top-right
      ctx.moveTo(this.centerX + lockRadius - bLen, this.centerY - lockRadius);
      ctx.lineTo(this.centerX + lockRadius, this.centerY - lockRadius);
      ctx.lineTo(this.centerX + lockRadius, this.centerY - lockRadius + bLen);
      // Bottom-right
      ctx.moveTo(this.centerX + lockRadius, this.centerY + lockRadius - bLen);
      ctx.lineTo(this.centerX + lockRadius, this.centerY + lockRadius);
      ctx.lineTo(this.centerX + lockRadius - bLen, this.centerY + lockRadius);
      // Bottom-left
      ctx.moveTo(this.centerX - lockRadius + bLen, this.centerY + lockRadius);
      ctx.lineTo(this.centerX - lockRadius, this.centerY + lockRadius);
      ctx.lineTo(this.centerX - lockRadius, this.centerY + lockRadius - bLen);
      ctx.stroke();
    }

    // 2. Peer Nodes (Peer A, Peer B, Peer C)
    const maxDelayScale = 40.0; // 40ms maps to maxRadius

    for (let i = 1; i < 4; i++) {
      const ch = telemetry.channels[i];
      if (!ch) continue;

      const angle = this.nodeAngles[i];
      const smoothDelay = this.visualDelays[i];
      // Radius proportional to delay: clamped between 0 and maxRadius
      const r = Math.min(this.maxRadius, (smoothDelay / maxDelayScale) * this.maxRadius);

      const nx = this.centerX + Math.cos(angle) * r;
      const ny = this.centerY + Math.sin(angle) * r;

      const isNodeLocked = smoothDelay < 0.5;
      const nodeColor = isNodeLocked ? '#00E599' : smoothDelay > 20 ? '#FF334B' : '#FFB800';

      // Vector displacement line from Host to Peer
      if (r > 4) {
        ctx.beginPath();
        ctx.moveTo(this.centerX, this.centerY);
        ctx.lineTo(nx, ny);
        ctx.strokeStyle = isNodeLocked ? 'rgba(0, 229, 153, 0.25)' : 'rgba(255, 184, 0, 0.4)';
        ctx.lineWidth = 1;
        ctx.setLineDash([2, 3]);
        ctx.stroke();
        ctx.setLineDash([]);
      }

      // Node Blip Target
      ctx.beginPath();
      ctx.arc(nx, ny, 4, 0, Math.PI * 2);
      ctx.fillStyle = nodeColor;
      ctx.shadowColor = nodeColor;
      ctx.shadowBlur = 6;
      ctx.fill();
      ctx.shadowBlur = 0;

      // Small outer ring
      ctx.beginPath();
      ctx.arc(nx, ny, 7, 0, Math.PI * 2);
      ctx.strokeStyle = nodeColor;
      ctx.lineWidth = 1;
      ctx.stroke();

      // Node Monospaced Badge
      const delayText = isNodeLocked ? '0.0ms' : `+${smoothDelay.toFixed(1)}ms`;
      const labelText = `${ch.role.replace('PEER ', 'P-0')} [${delayText}]`;

      ctx.font = '8.5px "JetBrains Mono", monospace';
      ctx.fillStyle = nodeColor;
      ctx.textAlign = nx > this.centerX ? 'left' : 'right';
      const textOffsetX = nx > this.centerX ? 10 : -10;
      ctx.fillText(labelText, nx + textOffsetX, ny + 3);
    }

    ctx.restore();
  }

  _drawHudOverlays(telemetry, isLocked) {
    const ctx = this.ctx;
    ctx.save();

    // Top-left HUD badge
    ctx.font = '9px "JetBrains Mono", monospace';
    ctx.fillStyle = '#9DA5B4';
    ctx.textAlign = 'left';
    ctx.fillText('POLAR PHASE SCOPE // CH: 4', 12, 18);

    // Top-right Status beacon
    ctx.textAlign = 'right';
    if (isLocked) {
      ctx.fillStyle = '#00E599';
      ctx.fillText('LOCK: <0.5ms (IN PHASE)', this.width - 12, 18);
    } else {
      ctx.fillStyle = '#FFB800';
      ctx.fillText(`JITTER DRIFT: +${telemetry.maxDriftMs.toFixed(1)}ms`, this.width - 12, 18);
    }

    // Bottom-left info
    ctx.textAlign = 'left';
    ctx.fillStyle = '#5D6475';
    ctx.fillText('CLOCK: 48,000 Hz MONOTONIC', 12, this.height - 12);

    // Bottom-right sweep mode
    ctx.textAlign = 'right';
    ctx.fillText('OBOE MMAP / OPUS 20ms', this.width - 12, this.height - 12);

    ctx.restore();
  }
}
