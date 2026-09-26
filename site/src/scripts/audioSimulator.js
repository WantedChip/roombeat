/**
 * RoomBeat WebAudio Multi-Node Acoustic Simulator
 * Pure vanilla WebAudio API synthesis engine for 4 simulated phone nodes.
 *
 * Implements:
 * - 4-voice rhythmic synthesizer (Kick, Snare, Bassline, Synth Stabs)
 * - Lookahead timing scheduler (sub-millisecond WebAudio clock accuracy)
 * - 4 independent phone channel strips (Host + 3 Peers) with DelayNodes
 * - Wi-Fi jitter injection (5–40ms drift with acoustic comb filtering)
 * - RoomBeat phase-lock convergence slewing (simulating NDK Catmull-Rom resampler)
 * - Real-time channel telemetry (RMS, peak, phase offset, FFT analyser nodes)
 */

export class AudioSimulator {
  /**
   * Logarithmic audio fader gain mapping (-inf to +3dB).
   * Maps fader position [0..100] to gain amplitude:
   *   pos = 0   => -inf dB (gain = 0.0)
   *   pos = 25  => -12 dB  (gain = 0.2512)
   *   pos = 50  => -6 dB   (gain = 0.5012)
   *   pos = 75  => 0 dB    (gain = 1.0000, unity center detent)
   *   pos = 100 => +3 dB   (gain = 1.4125)
   */
  static faderPosToGain(pos) {
    if (pos <= 0) return 0.0;
    // Magnetic center detent at 0 dB (pos 75)
    if (pos >= 73 && pos <= 77) return 1.0;

    let db = 0;
    if (pos <= 25) {
      const u = pos / 25.0;
      db = -48.0 + u * 36.0; // -48dB to -12dB
    } else if (pos <= 50) {
      const u = (pos - 25) / 25.0;
      db = -12.0 + u * 6.0; // -12dB to -6dB
    } else if (pos <= 75) {
      const u = (pos - 50) / 25.0;
      db = -6.0 + u * 6.0; // -6dB to 0dB
    } else {
      const u = (pos - 75) / 25.0;
      db = u * 3.0; // 0dB to +3dB
    }
    return Math.pow(10, db / 20.0);
  }

  static faderPosToDb(pos) {
    if (pos <= 0) return '-inf dB';
    if (pos >= 73 && pos <= 77) return '0.0 dB';

    let db = 0;
    if (pos <= 25) {
      const u = pos / 25.0;
      db = -48.0 + u * 36.0;
    } else if (pos <= 50) {
      const u = (pos - 25) / 25.0;
      db = -12.0 + u * 6.0;
    } else if (pos <= 75) {
      const u = (pos - 50) / 25.0;
      db = -6.0 + u * 6.0;
    } else {
      const u = (pos - 75) / 25.0;
      db = u * 3.0;
    }

    if (db > 0) {
      return `+${db.toFixed(1)} dB`;
    }
    return `${db.toFixed(1)} dB`;
  }

  constructor() {
    this.ctx = null;
    this.isPlaying = false;
    this.isLocked = true;
    this.isJittered = false;
    this.tempoBpm = 124;
    this.lookaheadMs = 25;
    this.scheduleAheadTime = 0.1;
    this.currentStep = 0;
    this.nextStepTime = 0;
    this.timerId = null;

    // 4 channel nodes data (Host + 3 Peers)
    this.channels = [
      {
        id: 'host',
        name: 'Pixel 8 Pro',
        role: 'HOST (REF)',
        delayMs: 0.0,
        targetDelayMs: 0.0,
        faderPos: 75,
        volume: 1.0,
        dbText: '0.0 dB',
        muted: false,
        solo: false,
        delayNode: null,
        gainNode: null,
        analyser: null,
        timeData: new Float32Array(256),
        rms: 0,
        peak: 0,
      },
      {
        id: 'peer-a',
        name: 'Galaxy S24',
        role: 'PEER A',
        delayMs: 0.0,
        targetDelayMs: 0.0,
        faderPos: 75,
        volume: 1.0,
        dbText: '0.0 dB',
        muted: false,
        solo: false,
        delayNode: null,
        gainNode: null,
        analyser: null,
        timeData: new Float32Array(256),
        rms: 0,
        peak: 0,
      },
      {
        id: 'peer-b',
        name: 'Xperia 1 V',
        role: 'PEER B',
        delayMs: 0.0,
        targetDelayMs: 0.0,
        faderPos: 75,
        volume: 1.0,
        dbText: '0.0 dB',
        muted: false,
        solo: false,
        delayNode: null,
        gainNode: null,
        analyser: null,
        timeData: new Float32Array(256),
        rms: 0,
        peak: 0,
      },
      {
        id: 'peer-c',
        name: 'Nothing Phone 2',
        role: 'PEER C',
        delayMs: 0.0,
        targetDelayMs: 0.0,
        faderPos: 75,
        volume: 1.0,
        dbText: '0.0 dB',
        muted: false,
        solo: false,
        delayNode: null,
        gainNode: null,
        analyser: null,
        timeData: new Float32Array(256),
        rms: 0,
        peak: 0,
      },
    ];

    // Master nodes
    this.synthBus = null;
    this.masterGain = null;
    this.limiter = null;
    this.noiseBuffer = null;

    // Callbacks
    this.listeners = new Set();
  }

  /**
   * Initializes the AudioContext cleanly on user gesture.
   */
  async init() {
    if (this.ctx && this.ctx.state !== 'closed') {
      if (this.ctx.state === 'suspended') {
        await this.ctx.resume();
      }
      return;
    }

    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
      console.warn('WebAudio API is not supported in this browser.');
      return;
    }

    this.ctx = new AudioContextClass({
      latencyHint: 'interactive',
      sampleRate: 48000,
    });

    if (this.ctx.state === 'suspended') {
      await this.ctx.resume();
    }

    // Build audio graph
    this._buildGraph();
    this._generateNoiseBuffer();
  }

  /**
   * Builds the internal WebAudio routing graph.
   * Synth Bus -> 4 x (DelayNode -> GainNode -> AnalyserNode) -> Master Gain -> Limiter -> Destination
   */
  _buildGraph() {
    if (!this.ctx) return;

    this.synthBus = this.ctx.createGain();
    this.synthBus.gain.setValueAtTime(0.7, this.ctx.currentTime);

    this.masterGain = this.ctx.createGain();
    this.masterGain.gain.setValueAtTime(0.85, this.ctx.currentTime);

    // Dynamics compressor / brickwall limiter to ensure 0dB ceiling when summing 4 nodes
    this.limiter = this.ctx.createDynamicsCompressor();
    this.limiter.threshold.setValueAtTime(-2.0, this.ctx.currentTime);
    this.limiter.knee.setValueAtTime(4.0, this.ctx.currentTime);
    this.limiter.ratio.setValueAtTime(12.0, this.ctx.currentTime);
    this.limiter.attack.setValueAtTime(0.003, this.ctx.currentTime);
    this.limiter.release.setValueAtTime(0.08, this.ctx.currentTime);

    this.masterGain.connect(this.limiter);
    this.limiter.connect(this.ctx.destination);

    // Initialize 4 channels
    this.channels.forEach((ch, idx) => {
      // DelayNode max 0.5s
      const delayNode = this.ctx.createDelay(0.5);
      delayNode.delayTime.setValueAtTime(ch.delayMs / 1000.0, this.ctx.currentTime);

      const gainNode = this.ctx.createGain();
      gainNode.gain.setValueAtTime(ch.volume, this.ctx.currentTime);

      const analyser = this.ctx.createAnalyser();
      analyser.fftSize = 256;
      analyser.smoothingTimeConstant = 0.5;

      // Connect: SynthBus -> Delay -> Gain -> Analyser -> MasterGain
      this.synthBus.connect(delayNode);
      delayNode.connect(gainNode);
      gainNode.connect(analyser);
      analyser.connect(this.masterGain);

      ch.delayNode = delayNode;
      ch.gainNode = gainNode;
      ch.analyser = analyser;
    });

    this._updateGains();
  }

  /**
   * Generates a 1-second white noise buffer for snare and transient generation.
   */
  _generateNoiseBuffer() {
    if (!this.ctx) return;
    const bufferSize = this.ctx.sampleRate;
    const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
    const output = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      output[i] = Math.random() * 2 - 1;
    }
    this.noiseBuffer = buffer;
  }

  /**
   * Starts rhythmic playback loop.
   */
  async start() {
    await this.init();
    if (!this.ctx || this.isPlaying) return;

    this.isPlaying = true;
    this.currentStep = 0;
    this.nextStepTime = this.ctx.currentTime + 0.05;

    this.timerId = setInterval(() => {
      this._scheduler();
    }, this.lookaheadMs);

    this._notify();
  }

  /**
   * Stops playback.
   */
  stop() {
    if (!this.isPlaying) return;
    this.isPlaying = false;
    if (this.timerId) {
      clearInterval(this.timerId);
      this.timerId = null;
    }
    this._notify();
  }

  /**
   * Toggles start/stop.
   */
  async toggle() {
    if (this.isPlaying) {
      this.stop();
    } else {
      await this.start();
    }
  }

  /**
   * Precision Lookahead Scheduler.
   */
  _scheduler() {
    if (!this.ctx || !this.isPlaying) return;

    while (this.nextStepTime < this.ctx.currentTime + this.scheduleAheadTime) {
      this._scheduleStep(this.currentStep, this.nextStepTime);
      this._advanceStep();
    }
  }

  _advanceStep() {
    const secondsPerBeat = 60.0 / this.tempoBpm;
    const stepDuration = secondsPerBeat / 4.0; // 16th note
    this.nextStepTime += stepDuration;
    this.currentStep = (this.currentStep + 1) % 16;
  }

  /**
   * Schedules note events for step [0..15].
   */
  _scheduleStep(step, time) {
    // Four-on-the-floor Kick: 0, 4, 8, 12
    if (step % 4 === 0) {
      this._playKick(time);
    }

    // Snare / Clap: steps 4, 12 (with ghost on 14)
    if (step === 4 || step === 12) {
      this._playSnare(time, 0.85);
    } else if (step === 14) {
      this._playSnare(time, 0.35); // ghost note
    }

    // Hi-hat shaker: 16th notes with accent on off-beats
    const hatVolume = step % 2 === 1 ? 0.35 : 0.18;
    this._playHat(time, hatVolume);

    // Syncopated Bassline (D minor groove)
    // Notes: D1 (36.7Hz), F1 (43.6Hz), G1 (49.0Hz), A1 (55.0Hz), C2 (65.4Hz)
    const bassSteps = {
      0: 36.71,  // D1
      2: 36.71,  // D1
      3: 43.65,  // F1
      6: 49.00,  // G1
      8: 36.71,  // D1
      10: 55.00, // A1
      11: 43.65, // F1
      14: 65.41, // C2
    };

    if (bassSteps[step]) {
      this._playBass(time, bassSteps[step]);
    }

    // Synth Chord Stabs (D minor 9 groove on off-beats: 2, 6, 11)
    if (step === 2 || step === 6 || step === 11) {
      this._playChord(time, step === 11 ? [174.61, 220.00, 261.63, 329.63] : [146.83, 174.61, 220.00, 261.63]);
    }
  }

  /**
   * Synthesized Kick Drum: sweeping sine oscillator + punchy envelope.
   */
  _playKick(time) {
    if (!this.ctx || !this.synthBus) return;

    const osc = this.ctx.createOscillator();
    const gain = this.ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(140, time);
    osc.frequency.exponentialRampToValueAtTime(38, time + 0.08);

    gain.gain.setValueAtTime(1.0, time);
    gain.gain.exponentialRampToValueAtTime(0.001, time + 0.28);

    osc.connect(gain);
    gain.connect(this.synthBus);

    osc.start(time);
    osc.stop(time + 0.3);
  }

  /**
   * Synthesized Snare Drum: filtered noise + body oscillator.
   */
  _playSnare(time, intensity = 0.8) {
    if (!this.ctx || !this.synthBus || !this.noiseBuffer) return;

    // Noise burst
    const noiseSource = this.ctx.createBufferSource();
    noiseSource.buffer = this.noiseBuffer;

    const noiseFilter = this.ctx.createBiquadFilter();
    noiseFilter.type = 'bandpass';
    noiseFilter.frequency.setValueAtTime(1400, time);
    noiseFilter.Q.setValueAtTime(1.2, time);

    const noiseGain = this.ctx.createGain();
    noiseGain.gain.setValueAtTime(intensity * 0.9, time);
    noiseGain.gain.exponentialRampToValueAtTime(0.001, time + 0.18);

    noiseSource.connect(noiseFilter);
    noiseFilter.connect(noiseGain);
    noiseGain.connect(this.synthBus);

    noiseSource.start(time);
    noiseSource.stop(time + 0.2);

    // Tonal body
    const bodyOsc = this.ctx.createOscillator();
    const bodyGain = this.ctx.createGain();

    bodyOsc.type = 'triangle';
    bodyOsc.frequency.setValueAtTime(180, time);
    bodyOsc.frequency.exponentialRampToValueAtTime(80, time + 0.08);

    bodyGain.gain.setValueAtTime(intensity * 0.6, time);
    bodyGain.gain.exponentialRampToValueAtTime(0.001, time + 0.12);

    bodyOsc.connect(bodyGain);
    bodyGain.connect(this.synthBus);

    bodyOsc.start(time);
    bodyOsc.stop(time + 0.15);
  }

  /**
   * Synthesized Hi-Hat: high-passed white noise.
   */
  _playHat(time, intensity = 0.2) {
    if (!this.ctx || !this.synthBus || !this.noiseBuffer) return;

    const noiseSource = this.ctx.createBufferSource();
    noiseSource.buffer = this.noiseBuffer;

    const filter = this.ctx.createBiquadFilter();
    filter.type = 'highpass';
    filter.frequency.setValueAtTime(7500, time);

    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(intensity, time);
    gain.gain.exponentialRampToValueAtTime(0.001, time + 0.045);

    noiseSource.connect(filter);
    filter.connect(gain);
    gain.connect(this.synthBus);

    noiseSource.start(time);
    noiseSource.stop(time + 0.05);
  }

  /**
   * Synthesized Resonant Bassline: Sawtooth into sweeping low-pass filter.
   */
  _playBass(time, freq) {
    if (!this.ctx || !this.synthBus) return;

    const osc = this.ctx.createOscillator();
    const filter = this.ctx.createBiquadFilter();
    const gain = this.ctx.createGain();

    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(freq, time);

    filter.type = 'lowpass';
    filter.frequency.setValueAtTime(850, time);
    filter.frequency.exponentialRampToValueAtTime(130, time + 0.14);
    filter.Q.setValueAtTime(3.5, time);

    gain.gain.setValueAtTime(0.65, time);
    gain.gain.exponentialRampToValueAtTime(0.001, time + 0.18);

    osc.connect(filter);
    filter.connect(gain);
    gain.connect(this.synthBus);

    osc.start(time);
    osc.stop(time + 0.2);
  }

  /**
   * Synthesized Chord Stabs: Detuned polyphonic oscillators with filter sweep.
   */
  _playChord(time, freqs) {
    if (!this.ctx || !this.synthBus) return;

    const chordGain = this.ctx.createGain();
    chordGain.gain.setValueAtTime(0.45, time);
    chordGain.gain.exponentialRampToValueAtTime(0.001, time + 0.16);

    const filter = this.ctx.createBiquadFilter();
    filter.type = 'bandpass';
    filter.frequency.setValueAtTime(1200, time);
    filter.Q.setValueAtTime(2.0, time);

    freqs.forEach((f) => {
      const osc = this.ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(f, time);
      osc.connect(filter);
      osc.start(time);
      osc.stop(time + 0.18);
    });

    filter.connect(chordGain);
    chordGain.connect(this.synthBus);
  }

  /**
   * Inject Wi-Fi Jitter.
   * Introduces 5–40ms random delay shifts across Peer A, Peer B, and Peer C,
   * creating audible acoustic comb filtering and phase cancellation.
   * @param {number} [customJitterMs] Optional fixed jitter target in ms
   */
  injectJitter(customJitterMs) {
    if (!this.ctx) return;

    this.isLocked = false;
    this.isJittered = true;

    // Reference Host stays 0.0ms
    this.channels[0].targetDelayMs = 0.0;
    this.channels[0].delayMs = 0.0;

    // Define realistic Wi-Fi multi-path jitter offsets (5–40ms range)
    const jitterValues = customJitterMs !== undefined
      ? [
          customJitterMs,
          Math.min(40, customJitterMs * 1.5),
          Math.max(5, customJitterMs * 0.7),
        ]
      : [
          14.0 + Math.random() * 8.0,  // Peer A: ~14-22ms
          26.0 + Math.random() * 12.0, // Peer B: ~26-38ms
          8.0 + Math.random() * 7.0,   // Peer C: ~8-15ms
        ];

    for (let i = 1; i < 4; i++) {
      const ch = this.channels[i];
      const targetDelay = jitterValues[i - 1];
      ch.targetDelayMs = Number(targetDelay.toFixed(1));

      if (ch.delayNode) {
        // Smooth slew over 60ms to avoid harsh audio pops while creating Doppler warble
        const currentTarget = ch.targetDelayMs / 1000.0;
        ch.delayNode.delayTime.setTargetAtTime(currentTarget, this.ctx.currentTime, 0.04);
      }
    }

    this._notify();
  }

  /**
   * Engage RoomBeat Sync.
   * Smoothly slews peer delay times back to 0.0ms over ~0.8s,
   * simulating RoomBeat's NDK Catmull-Rom fractional resampler phase-locking.
   */
  engageSync() {
    if (!this.ctx) return;

    this.isLocked = true;
    this.isJittered = false;

    for (let i = 0; i < 4; i++) {
      const ch = this.channels[i];
      ch.targetDelayMs = 0.0;

      if (ch.delayNode) {
        // Smoothly slew back to 0.0ms over ~0.8s time constant
        ch.delayNode.delayTime.setTargetAtTime(0.0, this.ctx.currentTime, 0.22);
      }
    }

    this._notify();
  }

  /**
   * Sets channel volume using fader position [0..100] with logarithmic dB mapping.
   * Snaps magnetic center detent at 0 dB (pos 75).
   * @param {number} idx Channel index (0..3)
   * @param {number} faderPos Fader position (0..100)
   */
  setChannelVolume(idx, faderPos) {
    if (idx < 0 || idx >= this.channels.length) return;
    let pos = Math.max(0, Math.min(100, Math.round(faderPos)));
    if (pos >= 73 && pos <= 77) {
      pos = 75; // Magnetic detent snap at 0 dB
    }

    const ch = this.channels[idx];
    ch.faderPos = pos;
    ch.volume = AudioSimulator.faderPosToGain(pos);
    ch.dbText = AudioSimulator.faderPosToDb(pos);

    this._updateGains();
    this._notify();
    return { faderPos: pos, volume: ch.volume, dbText: ch.dbText };
  }

  /**
   * Sets master transport tempo in BPM (80..160).
   * @param {number} bpm
   */
  setTempo(bpm) {
    this.tempoBpm = Math.max(80, Math.min(160, Math.round(bpm)));
    this._notify();
    return this.tempoBpm;
  }

  /**
   * Synthesizes subtle tactile audio click feedback on physical keycap button presses.
   */
  playKeycapClick() {
    if (!this.ctx) return;
    try {
      const t = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      const filter = this.ctx.createBiquadFilter();

      filter.type = 'highpass';
      filter.frequency.setValueAtTime(2200, t);

      osc.type = 'triangle';
      osc.frequency.setValueAtTime(2800, t);
      osc.frequency.exponentialRampToValueAtTime(700, t + 0.007);

      gain.gain.setValueAtTime(0.08, t);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.008);

      osc.connect(filter);
      filter.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start(t);
      osc.stop(t + 0.01);
    } catch {
      // Ignore if AudioContext is blocked
    }
  }

  /**
   * Toggles mute state for a channel.
   */
  toggleMute(idx) {
    if (idx < 0 || idx >= this.channels.length) return;
    this.channels[idx].muted = !this.channels[idx].muted;
    this._updateGains();
    this._notify();
    return this.channels[idx].muted;
  }

  /**
   * Toggles solo state for a channel.
   */
  toggleSolo(idx) {
    if (idx < 0 || idx >= this.channels.length) return;
    this.channels[idx].solo = !this.channels[idx].solo;
    this._updateGains();
    this._notify();
    return this.channels[idx].solo;
  }

  /**
   * Computes active gain based on volume, mute, and solo across all 4 channels.
   * - If ANY channel is soloed: only soloed channels that are not muted pass audio.
   * - If NO channels are soloed: all unmuted channels pass audio at their fader gain.
   */
  _updateGains() {
    if (!this.ctx) return;

    const hasAnySolo = this.channels.some((c) => c.solo);

    this.channels.forEach((ch) => {
      if (!ch.gainNode) return;

      let effectiveGain = 0;
      if (hasAnySolo) {
        effectiveGain = ch.solo && !ch.muted ? ch.volume : 0;
      } else {
        effectiveGain = ch.muted ? 0 : ch.volume;
      }

      ch.gainNode.gain.setTargetAtTime(effectiveGain, this.ctx.currentTime, 0.015);
    });
  }

  /**
   * Polls telemetry for visualizers (VU meters, radar canvas, diagnostic dials).
   * Call inside requestAnimationFrame loop.
   */
  getTelemetry() {
    let maxDriftMs = 0;

    this.channels.forEach((ch, idx) => {
      // Update actual delay time reading from AudioParam if available
      if (ch.delayNode) {
        ch.delayMs = Number((ch.delayNode.delayTime.value * 1000).toFixed(1));
      }
      const reportedDrift = Math.max(ch.delayMs, ch.targetDelayMs);
      if (idx > 0 && reportedDrift > maxDriftMs) {
        maxDriftMs = reportedDrift;
      }

      // Calculate instantaneous RMS & peak
      if (ch.analyser && this.isPlaying) {
        ch.analyser.getFloatTimeDomainData(ch.timeData);
        let sum = 0;
        let peak = 0;
        for (let i = 0; i < ch.timeData.length; i++) {
          const val = Math.abs(ch.timeData[i]);
          if (val > peak) peak = val;
          sum += val * val;
        }
        const rms = Math.sqrt(sum / ch.timeData.length);
        ch.rms = rms;
        ch.peak = peak;
      } else {
        ch.rms = 0;
        ch.peak = 0;
      }
    });

    // Check if effectively locked (< 0.4ms drift on all channels)
    const allWithinLockZone = this.channels.every((c) => c.delayMs < 0.4 && c.targetDelayMs < 0.4);
    this.isLocked = allWithinLockZone;
    this.isJittered = !allWithinLockZone;

    // Simulate real-time packet loss and jitter buffer depth for Bay 04 dials
    let packetLossPct = 0.0;
    let bufferDepthMs = 20;

    if (!allWithinLockZone) {
      packetLossPct = Number(Math.min(12.0, (maxDriftMs * 0.14) + 0.6).toFixed(1));
      bufferDepthMs = Math.min(60, Math.round(20 + maxDriftMs * 0.7));
    }

    return {
      isPlaying: this.isPlaying,
      isLocked: this.isLocked,
      isJittered: this.isJittered,
      maxDriftMs: maxDriftMs,
      packetLossPct: packetLossPct,
      bufferDepthMs: bufferDepthMs,
      tempoBpm: this.tempoBpm,
      currentStep: this.currentStep,
      channels: this.channels,
    };
  }

  /**
   * Registers state change subscriber.
   */
  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  _notify() {
    const telemetry = this.getTelemetry();
    this.listeners.forEach((fn) => fn(telemetry));
  }
}
