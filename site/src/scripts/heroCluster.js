/**
 * RoomBeat Hero Multicast Cluster & Kinetic Engine (v0.9.5)
 * 
 * Orchestrates:
 * 1. 4-node device cluster with animated SVG bezier beam wires (Magic UI Animated Beam via GSAP)
 * 2. Overhead industrial directional spotlight beam responsive to ambient cursor (Aceternity Spotlight New)
 * 3. Kinetic counter tickers on value pillars (Magic UI Number Ticker via GSAP)
 * 4. Terminal monospace decryption sequence on category stencil tag (React Bits Decrypted Text)
 * 5. Acoustic soundwave ripples radiating from phone speaker grilles on test beat (Magic UI Ripple)
 * 6. Tactile magnetic attraction on primary CTA button (Motion Primitives Magnetic via GSAP quickTo)
 */

import gsap from 'gsap';

// ============================================================================
// 1. Terminal Monospace Decryption Sequence (React Bits Decrypted Text)
// ============================================================================
export function initDecryptedText() {
  const el = document.getElementById('decrypted-stencil-tag');
  if (!el) return;

  const targetText = 'UDP MULTICAST CONTROL PLANE';

  // Respect reduced motion accessibility
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    el.textContent = targetText;
    return;
  }

  const glyphChars = '0123456789ABCDEF!#$*+-%<>_@&?~';
  let currentFrame = 0;
  const totalFrames = 32; // ~530ms at 60fps

  function scrambleStep() {
    currentFrame++;
    const progress = currentFrame / totalFrames;
    const revealedLength = Math.floor(progress * targetText.length);

    let output = '';
    for (let i = 0; i < targetText.length; i++) {
      if (targetText[i] === ' ') {
        output += ' ';
      } else if (i < revealedLength) {
        output += targetText[i];
      } else {
        output += glyphChars[Math.floor(Math.random() * glyphChars.length)];
      }
    }

    el.textContent = output;

    if (currentFrame < totalFrames) {
      requestAnimationFrame(scrambleStep);
    } else {
      el.textContent = targetText;
    }
  }

  // Slight initial pause for tactile arrival effect
  setTimeout(() => {
    requestAnimationFrame(scrambleStep);
  }, 120);
}

// ============================================================================
// 2. Kinetic Number Tickers (Magic UI Number Ticker via GSAP)
// ============================================================================
export function initKineticTickers() {
  const container = document.getElementById('hero-pillars');
  const pricingEl = document.getElementById('ticker-pricing');
  const capacityEl = document.getElementById('ticker-capacity');
  const offsetEl = document.getElementById('ticker-offset');

  if (!container || !pricingEl || !capacityEl || !offsetEl) return;

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    pricingEl.textContent = '$0';
    capacityEl.textContent = '32+';
    offsetEl.textContent = '< 0.2ms';
    return;
  }

  let hasAnimated = false;
  const executeTickers = () => {
    if (hasAnimated) return;
    hasAnimated = true;

    // Pillar 1: FOSS Pricing (countdown from proprietary $520/yr to $0)
    const priceState = { val: 520 };
    gsap.to(priceState, {
      val: 0,
      duration: 1.6,
      ease: 'power3.out',
      onUpdate: () => {
        pricingEl.textContent = `$${Math.round(priceState.val)}`;
      }
    });

    // Pillar 2: Device Capacity (countup from 0 to 32+ devices)
    const capacityState = { val: 0 };
    gsap.to(capacityState, {
      val: 32,
      duration: 1.5,
      ease: 'power2.out',
      onUpdate: () => {
        capacityEl.textContent = `${Math.round(capacityState.val)}+`;
      }
    });

    // Pillar 3: Clock Offset (countdown from standard 150.0ms Wi-Fi drift down to <0.2ms)
    const offsetState = { val: 150.0 };
    gsap.to(offsetState, {
      val: 0.2,
      duration: 1.8,
      ease: 'power3.out',
      onUpdate: () => {
        offsetEl.textContent = `< ${offsetState.val.toFixed(1)}ms`;
      }
    });
  };

  const observer = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting) {
        executeTickers();
        observer.disconnect();
      }
    },
    { threshold: 0.15 }
  );

  observer.observe(container);

  // Safety fallback
  setTimeout(() => {
    if (!hasAnimated) executeTickers();
  }, 400);
}

// ============================================================================
// 3. Overhead Directional Spotlight (Aceternity Spotlight New via GSAP)
// ============================================================================
export function initDirectionalSpotlight() {
  const spotlight = document.getElementById('hero-spotlight');
  const heroSection = document.getElementById('hero-section');
  if (!spotlight || !heroSection) return;

  const isFinePointer = window.matchMedia('(pointer: fine)').matches;
  if (!isFinePointer) return;

  const xTo = gsap.quickTo(spotlight, 'x', { duration: 0.7, ease: 'power2.out' });
  const rotTo = gsap.quickTo(spotlight, 'rotation', { duration: 0.9, ease: 'power2.out' });

  heroSection.addEventListener('mousemove', (e) => {
    const rect = heroSection.getBoundingClientRect();
    const relX = (e.clientX - (rect.left + rect.width / 2)) / (rect.width / 2);
    // Subtle physical tilt & pan (max ±45px, ±4.5deg)
    xTo(relX * 45);
    rotTo(relX * 4.5);
  });

  heroSection.addEventListener('mouseleave', () => {
    xTo(0);
    rotTo(0);
  });
}

// ============================================================================
// 4. Primary CTA Button Magnetic Attraction (Motion Primitives Magnetic)
// ============================================================================
export function initHeroMagneticCta() {
  const wrap = document.getElementById('hero-cta-magnetic-wrap');
  const btn = document.getElementById('hero-primary-cta');

  if (!wrap || !btn) return;

  const isFinePointer = window.matchMedia('(pointer: fine)').matches;
  if (!isFinePointer) return;

  const xTo = gsap.quickTo(btn, 'x', { duration: 0.35, ease: 'power2.out' });
  const yTo = gsap.quickTo(btn, 'y', { duration: 0.35, ease: 'power2.out' });

  wrap.addEventListener('mousemove', (e) => {
    const rect = wrap.getBoundingClientRect();
    const relX = e.clientX - (rect.left + rect.width / 2);
    const relY = e.clientY - (rect.top + rect.height / 2);

    const pullX = Math.max(-8, Math.min(8, relX * 0.22));
    const pullY = Math.max(-6, Math.min(6, relY * 0.22));

    xTo(pullX);
    yTo(pullY);
  });

  wrap.addEventListener('mouseleave', () => {
    xTo(0);
    yTo(0);
  });
}

// ============================================================================
// 5. 4-Node Multicast Cluster & Animated Beams (Magic UI Animated Beam via GSAP)
// ============================================================================
let wireResizeHandler = null;

export function initAnimatedCluster() {
  const stage = document.getElementById('cluster-stage');
  const hostPort = document.getElementById('host-tx-port');
  const p1Port = document.getElementById('peer-1-rx-port');
  const p2Port = document.getElementById('peer-2-rx-port');
  const p3Port = document.getElementById('peer-3-rx-port');

  if (!stage || !hostPort || !p1Port || !p2Port || !p3Port) return;

  const wires = [
    { bg: document.getElementById('wire-bg-1'), beam: document.getElementById('wire-beam-1'), bead: document.getElementById('wire-bead-1'), target: p1Port, delay: 0 },
    { bg: document.getElementById('wire-bg-2'), beam: document.getElementById('wire-beam-2'), bead: document.getElementById('wire-bead-2'), target: p2Port, delay: 0.2 },
    { bg: document.getElementById('wire-bg-3'), beam: document.getElementById('wire-beam-3'), bead: document.getElementById('wire-bead-3'), target: p3Port, delay: 0.4 }
  ];

  function computePortCoord(portEl, stageRect) {
    const r = portEl.getBoundingClientRect();
    return {
      x: r.left + r.width / 2 - stageRect.left,
      y: r.top + r.height / 2 - stageRect.top
    };
  }

  function updateWirePaths() {
    const stageRect = stage.getBoundingClientRect();
    if (stageRect.width === 0 || stageRect.height === 0) return;

    const hostCoord = computePortCoord(hostPort, stageRect);

    wires.forEach(({ bg, beam, target }) => {
      if (!bg || !beam || !target) return;
      const targetCoord = computePortCoord(target, stageRect);

      const dy = targetCoord.y - hostCoord.y;
      // Smooth cubic bezier conduit path
      const c1x = hostCoord.x;
      const c1y = hostCoord.y + Math.max(16, dy * 0.5);
      const c2x = targetCoord.x;
      const c2y = targetCoord.y - Math.max(16, dy * 0.5);

      const d = `M ${hostCoord.x.toFixed(1)},${hostCoord.y.toFixed(1)} C ${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${targetCoord.x.toFixed(1)},${targetCoord.y.toFixed(1)}`;
      bg.setAttribute('d', d);
      beam.setAttribute('d', d);
    });
  }

  // Initial calculation
  updateWirePaths();

  // Listen for resize and update dynamically
  if (wireResizeHandler) window.removeEventListener('resize', wireResizeHandler);
  wireResizeHandler = () => {
    requestAnimationFrame(updateWirePaths);
  };
  window.addEventListener('resize', wireResizeHandler, { passive: true });

  // Setup GSAP continuous animations for beams and beads
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return;
  }

  wires.forEach(({ beam, bead, delay }) => {
    if (!beam || !bead) return;

    // Stroke dashoffset animation (Magic UI Animated Beam)
    const len = beam.getTotalLength() || 240;
    beam.style.strokeDasharray = `38 ${Math.max(100, len - 38)}`;
    beam.style.strokeDashoffset = '0';

    gsap.to(beam, {
      strokeDashoffset: -len * 2,
      duration: 1.6,
      repeat: -1,
      delay: delay,
      ease: 'none'
    });

    // Traveling packet bead
    const beadState = { progress: 0 };
    gsap.to(beadState, {
      progress: 1,
      duration: 1.6,
      repeat: -1,
      delay: delay,
      ease: 'power1.inOut',
      onUpdate: () => {
        const totalLen = beam.getTotalLength();
        if (totalLen > 0) {
          const pt = beam.getPointAtLength(beadState.progress * totalLen);
          bead.setAttribute('cx', pt.x.toFixed(1));
          bead.setAttribute('cy', pt.y.toFixed(1));
          // Fade in and out
          const alpha = Math.sin(beadState.progress * Math.PI);
          bead.setAttribute('opacity', (alpha * 0.95).toFixed(2));
        }
      }
    });
  });
}

// ============================================================================
// 6. Acoustic Ripple Waves & Test Beat Trigger (Magic UI Ripple & Web Audio)
// ============================================================================
function triggerWebAudioImpulse() {
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;

    const ctx = new AudioCtx();
    if (ctx.state === 'suspended') {
      ctx.resume();
    }

    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    // 90Hz -> 45Hz sub-bass transient click
    osc.type = 'sine';
    osc.frequency.setValueAtTime(90, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(45, ctx.currentTime + 0.07);

    gain.gain.setValueAtTime(0.25, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.07);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.07);
  } catch (e) {
    // Non-fatal if audio context not permitted
  }
}

function emitSpeakerRipples(emitterEl, ringColor = '#00E599') {
  if (!emitterEl) return;

  for (let i = 0; i < 3; i++) {
    const ring = document.createElement('span');
    ring.className = 'absolute rounded-full pointer-events-none';
    ring.style.width = '14px';
    ring.style.height = '14px';
    ring.style.border = `1.5px solid ${ringColor}`;
    ring.style.boxShadow = `0 0 8px ${ringColor}`;
    emitterEl.appendChild(ring);

    gsap.fromTo(
      ring,
      { scale: 0.3, opacity: 0.95 },
      {
        scale: 4.8,
        opacity: 0,
        duration: 0.95 + i * 0.15,
        delay: i * 0.09,
        ease: 'power2.out',
        onComplete: () => {
          ring.remove();
        }
      }
    );
  }
}

export function initTestBeatTrigger() {
  const btn = document.getElementById('trigger-beat-btn');
  const radarStatus = document.getElementById('radar-status');
  const vuBars = document.querySelectorAll('.vu-bar');

  if (!btn) return;

  let isTriggering = false;

  btn.addEventListener('click', () => {
    if (isTriggering) return;
    isTriggering = true;

    // 1. Play tactile acoustic click via Web Audio
    triggerWebAudioImpulse();

    // 2. Emit acoustic ripples from Host and all 3 Peer speaker grilles
    const hostEmitter = document.getElementById('ripple-emitter-host');
    const p1Emitter = document.getElementById('ripple-emitter-peer-1');
    const p2Emitter = document.getElementById('ripple-emitter-peer-2');
    const p3Emitter = document.getElementById('ripple-emitter-peer-3');

    emitSpeakerRipples(hostEmitter, '#FF5500');
    emitSpeakerRipples(p1Emitter, '#00E599');
    emitSpeakerRipples(p2Emitter, '#00E599');
    emitSpeakerRipples(p3Emitter, '#00E599');

    // 3. Peak the VU meters
    vuBars.forEach((bar) => {
      const origHeight = bar.style.height;
      bar.style.height = '100%';
      setTimeout(() => {
        bar.style.height = origHeight;
      }, 350);
    });

    // 4. Update status telemetry
    if (radarStatus) {
      radarStatus.textContent = 'BEAT FIRED: 4/4 NODES SYNCED (< 0.2ms)';
      radarStatus.className = 'text-xs font-mono text-sync-green font-bold';
      setTimeout(() => {
        radarStatus.textContent = 'ACOUSTIC LOCK: 0.2ms DEVIATION';
        radarStatus.className = 'text-xs font-mono text-text-dim';
      }, 1600);
    }

    setTimeout(() => {
      isTriggering = false;
    }, 400);
  });
}

// ============================================================================
// 7. Interactive Network Jitter Simulation
// ============================================================================
export function initJitterSimulation() {
  const btn = document.getElementById('simulate-jitter-btn');
  const beacon = document.getElementById('console-beacon');
  const beaconText = document.getElementById('beacon-text');
  const radarStatus = document.getElementById('radar-status');
  const node1 = document.getElementById('drift-node-1');
  const node2 = document.getElementById('drift-node-2');
  const node3 = document.getElementById('drift-node-3');
  const icon = document.getElementById('simulate-icon');

  if (!btn || !beacon || !beaconText || !radarStatus || !node1 || !node2 || !node3) return;

  let isSimulating = false;

  btn.addEventListener('click', () => {
    if (isSimulating) return;
    isSimulating = true;

    if (icon) icon.classList.add('animate-spin');
    beacon.className = 'font-mono text-[11px] font-semibold text-[#FFB800] bg-[#FFB800]/10 border border-[#FFB800]/30 px-2 py-0.5 rounded-[3px] flex items-center gap-1.5';
    beaconText.textContent = 'DRIFT DETECTED · +4.8ms';
    radarStatus.textContent = 'RESAMPLER ENGAGED: SLEWING ±0.08%';
    radarStatus.className = 'text-xs font-mono text-[#FFB800] font-semibold';

    node1.textContent = '+4.8ms';
    node1.className = 'font-mono text-[11px] text-[#FFB800] font-semibold';
    node2.textContent = '-3.6ms';
    node2.className = 'font-mono text-[11px] text-[#FFB800] font-semibold';
    node3.textContent = '+5.2ms';
    node3.className = 'font-mono text-[11px] text-[#FFB800] font-semibold';

    // Step 1: Convergence
    setTimeout(() => {
      node1.textContent = '+1.4ms';
      node2.textContent = '-0.8ms';
      node3.textContent = '+1.1ms';
      beaconText.textContent = 'CONVERGING · +1.1ms';
    }, 600);

    // Step 2: Lock restored
    setTimeout(() => {
      if (icon) icon.classList.remove('animate-spin');
      beacon.className = 'font-mono text-[11px] font-semibold text-sync-green bg-[#00E599]/10 border border-[#00E599]/30 px-2 py-0.5 rounded-[3px] flex items-center gap-1.5';
      beaconText.textContent = 'LOCKED · 0.2ms';
      radarStatus.textContent = 'ACOUSTIC LOCK: 0.2ms DEVIATION';
      radarStatus.className = 'text-xs font-mono text-text-dim';

      node1.textContent = '0.0ms';
      node1.className = 'font-mono text-[11px] text-sync-green font-semibold';
      node2.textContent = '+0.1ms';
      node2.className = 'font-mono text-[11px] text-sync-green font-semibold';
      node3.textContent = '-0.1ms';
      node3.className = 'font-mono text-[11px] text-sync-green font-semibold';

      isSimulating = false;
    }, 1400);
  });
}

// ============================================================================
// Master Initialization Function
// ============================================================================
export function setupHeroCluster() {
  initDecryptedText();
  initKineticTickers();
  initDirectionalSpotlight();
  initHeroMagneticCta();
  initAnimatedCluster();
  initTestBeatTrigger();
  initJitterSimulation();
}

// Auto-run on DOM ready and Astro view transitions
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', setupHeroCluster);
} else {
  setupHeroCluster();
}

document.addEventListener('astro:page-load', setupHeroCluster);
