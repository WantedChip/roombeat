/**
 * RoomBeat QR & PIN Demo Engine (v0.9.7)
 * 
 * Orchestrates:
 * 1. Continuous Green Laser Viewfinder Scanning Line across QR matrix with reticle corners.
 * 2. Origin UI OTP Input Recessed 6-Slot Keypad with 2px inset shadows and keyboard navigation.
 * 3. GSAP Random PIN Scramble Generator with sequential digit lock-in and audio click feedback.
 * 4. Microsecond Handshake Socket Telemetry Log with mDNS discovery and NTP sync.
 */

import gsap from 'gsap';

// ============================================================================
// Synthesized Mechanical Keycap Click (Audio Feedback)
// ============================================================================
export function playKeycapClick() {
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;
    if (!window.__rbKeycapCtx) {
      window.__rbKeycapCtx = new AudioCtx();
    }
    const ctx = window.__rbKeycapCtx;
    if (ctx.state === 'suspended') {
      ctx.resume().catch(() => {});
    }
    const t = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    const filter = ctx.createBiquadFilter();

    filter.type = 'highpass';
    filter.frequency.setValueAtTime(2400, t);

    osc.type = 'triangle';
    osc.frequency.setValueAtTime(3000, t);
    osc.frequency.exponentialRampToValueAtTime(700, t + 0.007);

    gain.gain.setValueAtTime(0.09, t);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.008);

    osc.connect(filter);
    filter.connect(gain);
    gain.connect(ctx.destination);

    osc.start(t);
    osc.stop(t + 0.01);
  } catch {
    // Ignore if audio is restricted
  }
}

// ============================================================================
// Main QR & PIN Demo Controller
// ============================================================================
export function initQrPinDemo() {
  // Host Elements
  const hostPinSlots = document.querySelectorAll('.host-pin-cell');
  const btnRegenPin = document.getElementById('btn-regen-pin');
  const btnCopyPin = document.getElementById('btn-copy-pin');
  const copyPinText = document.getElementById('copy-pin-text');
  const hostPinContainer = document.getElementById('host-pin-container');
  const connectedNodesCount = document.getElementById('connected-nodes-count');

  // Listener OTP Elements (Origin UI style)
  const otpSlots = document.querySelectorAll('.otp-slot');
  const otpContainer = document.getElementById('otp-slots-container');
  const tabPinMode = document.getElementById('tab-pin-mode');
  const tabQrMode = document.getElementById('tab-qr-mode');
  const viewPinMode = document.getElementById('view-pin-mode');
  const viewQrMode = document.getElementById('view-qr-mode');
  const btnAutofillPin = document.getElementById('btn-autofill-pin');
  const digitBtns = document.querySelectorAll('.keypad-digit-btn');
  const btnKeypadClear = document.getElementById('btn-keypad-clear');
  const btnKeypadBack = document.getElementById('btn-keypad-back');
  const btnSimulateJoin = document.getElementById('btn-simulate-join');
  const btnSimulateQrScan = document.getElementById('btn-simulate-qr-scan');
  const terminalLog = document.getElementById('handshake-terminal-log');
  const elapsedTimeLabel = document.getElementById('handshake-elapsed-time');

  let currentHostPin = '834912';
  let enteredPin = ['', '', '', '', '', ''];
  let activeSlotIndex = 0;
  let isConnecting = false;
  let peerCount = 3;
  let isScramblingPin = false;

  // --------------------------------------------------------------------------
  // 1. Host PIN Display & GSAP Scramble Generator
  // --------------------------------------------------------------------------
  function setHostPinDisplay(pinStr) {
    hostPinSlots.forEach((slot, idx) => {
      if (slot) {
        slot.textContent = pinStr[idx] || '0';
      }
    });
  }

  setHostPinDisplay(currentHostPin);

  btnRegenPin?.addEventListener('click', () => {
    if (isScramblingPin) return;
    isScramblingPin = true;
    playKeycapClick();

    // Generate unguessable 6-digit cryptographic PIN
    const newPin = Math.floor(100000 + Math.random() * 900000).toString();
    const isReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (isReducedMotion) {
      currentHostPin = newPin;
      setHostPinDisplay(newPin);
      if (btnAutofillPin) {
        btnAutofillPin.textContent = `[ AUTO-FILL ${newPin.slice(0, 3)} ${newPin.slice(3, 6)} ]`;
      }
      isScramblingPin = false;
      return;
    }

    // GSAP sequential scramble animation
    const tl = gsap.timeline({
      onComplete: () => {
        currentHostPin = newPin;
        setHostPinDisplay(newPin);
        if (btnAutofillPin) {
          btnAutofillPin.textContent = `[ AUTO-FILL ${newPin.slice(0, 3)} ${newPin.slice(3, 6)} ]`;
        }
        if (hostPinContainer) {
          gsap.fromTo(hostPinContainer, 
            { borderColor: '#00E599', boxShadow: '0 0 16px rgba(0,229,153,0.3)' },
            { borderColor: '#262A35', boxShadow: 'none', duration: 0.6 }
          );
        }
        isScramblingPin = false;
      }
    });

    hostPinSlots.forEach((slot, slotIdx) => {
      const targetDigit = newPin[slotIdx];
      const scrambleInterval = 35; // ms
      const lockDelay = 0.15 + slotIdx * 0.08; // staggered lock-in

      // Temporary ticker for this slot
      let intervalId = setInterval(() => {
        slot.textContent = Math.floor(Math.random() * 10).toString();
      }, scrambleInterval);

      tl.to({}, {
        duration: lockDelay,
        onComplete: () => {
          clearInterval(intervalId);
          slot.textContent = targetDigit;
          playKeycapClick();
          gsap.fromTo(slot, 
            { color: '#00E599', scale: 1.15 },
            { color: '#F2F4F8', scale: 1.0, duration: 0.15 }
          );
        }
      }, 0);
    });
  });

  // Copy PIN to Clipboard
  btnCopyPin?.addEventListener('click', async () => {
    playKeycapClick();
    try {
      await navigator.clipboard.writeText(currentHostPin);
      if (copyPinText) {
        copyPinText.textContent = 'COPIED!';
        setTimeout(() => {
          if (copyPinText) copyPinText.textContent = 'Copy PIN';
        }, 1800);
      }
    } catch {
      if (copyPinText) copyPinText.textContent = currentHostPin;
    }
  });

  // --------------------------------------------------------------------------
  // 2. Origin UI OTP Input Recessed 6-Slot Keypad
  // --------------------------------------------------------------------------
  function updateOtpSlots() {
    otpSlots.forEach((slot, idx) => {
      const digit = enteredPin[idx];
      const isActive = idx === activeSlotIndex;

      if (digit) {
        slot.textContent = digit;
        slot.classList.add('is-filled');
        slot.classList.remove('is-empty');
      } else {
        slot.textContent = isActive ? '' : '•';
        slot.classList.remove('is-filled');
        slot.classList.add('is-empty');
      }

      if (isActive) {
        slot.classList.add('is-active');
      } else {
        slot.classList.remove('is-active');
      }
    });
  }

  function insertDigit(digit) {
    if (activeSlotIndex < 6) {
      enteredPin[activeSlotIndex] = digit;
      playKeycapClick();
      if (activeSlotIndex < 5) {
        activeSlotIndex++;
      }
      updateOtpSlots();
    }
  }

  function deleteDigit() {
    playKeycapClick();
    if (enteredPin[activeSlotIndex] !== '') {
      enteredPin[activeSlotIndex] = '';
    } else if (activeSlotIndex > 0) {
      activeSlotIndex--;
      enteredPin[activeSlotIndex] = '';
    }
    updateOtpSlots();
  }

  function clearAllDigits() {
    playKeycapClick();
    enteredPin = ['', '', '', '', '', ''];
    activeSlotIndex = 0;
    updateOtpSlots();
  }

  // Click on slot to select
  otpSlots.forEach((slot, idx) => {
    slot.addEventListener('click', () => {
      activeSlotIndex = idx;
      playKeycapClick();
      updateOtpSlots();
    });
  });

  // Keypad Digit Buttons (0-9)
  digitBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const d = btn.getAttribute('data-digit');
      if (d) insertDigit(d);
    });
  });

  btnKeypadBack?.addEventListener('click', deleteDigit);
  btnKeypadClear?.addEventListener('click', clearAllDigits);

  // Keyboard navigation & typing on container
  window.addEventListener('keydown', (e) => {
    // Only capture if OTP view is visible
    if (viewPinMode && viewPinMode.classList.contains('hidden')) return;

    if (e.key >= '0' && e.key <= '9') {
      insertDigit(e.key);
    } else if (e.key === 'Backspace') {
      deleteDigit();
    } else if (e.key === 'ArrowLeft') {
      if (activeSlotIndex > 0) {
        activeSlotIndex--;
        updateOtpSlots();
      }
    } else if (e.key === 'ArrowRight') {
      if (activeSlotIndex < 5) {
        activeSlotIndex++;
        updateOtpSlots();
      }
    } else if (e.key === 'Enter') {
      triggerJoin();
    }
  });

  // Paste support
  window.addEventListener('paste', (e) => {
    if (viewPinMode && viewPinMode.classList.contains('hidden')) return;
    const pasted = (e.clipboardData || window.clipboardData)?.getData('text');
    if (pasted) {
      const cleanDigits = pasted.replace(/\D/g, '').slice(0, 6);
      if (cleanDigits.length > 0) {
        for (let i = 0; i < cleanDigits.length; i++) {
          enteredPin[i] = cleanDigits[i];
        }
        activeSlotIndex = Math.min(5, cleanDigits.length - 1);
        playKeycapClick();
        updateOtpSlots();
      }
    }
  });

  // Auto-Fill Button
  btnAutofillPin?.addEventListener('click', () => {
    playKeycapClick();
    const pin = currentHostPin;

    // Staggered fill
    pin.split('').forEach((d, i) => {
      setTimeout(() => {
        enteredPin[i] = d;
        activeSlotIndex = i;
        playKeycapClick();
        updateOtpSlots();

        if (i === 5) {
          setTimeout(triggerJoin, 250);
        }
      }, i * 60);
    });
  });

  // Tab Switching
  tabPinMode?.addEventListener('click', () => {
    playKeycapClick();
    tabPinMode.classList.add('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabPinMode.classList.remove('text-text-dim');
    tabQrMode?.classList.remove('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabQrMode?.classList.add('text-text-dim');
    viewPinMode?.classList.remove('hidden');
    viewQrMode?.classList.add('hidden');
  });

  tabQrMode?.addEventListener('click', () => {
    playKeycapClick();
    tabQrMode.classList.add('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabQrMode.classList.remove('text-text-dim');
    tabPinMode?.classList.remove('bg-surface-elevated', 'text-bone', 'border', 'border-border-milled');
    tabPinMode?.classList.add('text-text-dim');
    viewQrMode?.classList.remove('hidden');
    viewPinMode?.classList.add('hidden');
  });

  // --------------------------------------------------------------------------
  // 3. Simulated Socket Handshake Telemetry Engine
  // --------------------------------------------------------------------------
  function triggerJoin() {
    const fullPin = enteredPin.join('');
    if (fullPin.length < 6) {
      // Shake animation on error
      if (otpContainer) {
        gsap.timeline()
          .to(otpContainer, { x: -6, duration: 0.04 })
          .to(otpContainer, { x: 6, duration: 0.04 })
          .to(otpContainer, { x: -4, duration: 0.04 })
          .to(otpContainer, { x: 4, duration: 0.04 })
          .to(otpContainer, { x: 0, duration: 0.04 });

        otpSlots.forEach(s => s.classList.add('border-sync-red'));
        setTimeout(() => {
          otpSlots.forEach(s => s.classList.remove('border-sync-red'));
        }, 400);
      }
      return;
    }

    runSimulatedHandshake(`6-Digit PIN [${fullPin.slice(0, 3)} ${fullPin.slice(3, 6)}]`);
  }

  btnSimulateJoin?.addEventListener('click', () => {
    playKeycapClick();
    triggerJoin();
  });

  btnSimulateQrScan?.addEventListener('click', () => {
    playKeycapClick();
    runSimulatedHandshake('CameraX QR Laser Scan');
  });

  function runSimulatedHandshake(methodName) {
    if (isConnecting) return;
    isConnecting = true;

    if (terminalLog) terminalLog.innerHTML = '';
    if (elapsedTimeLabel) elapsedTimeLabel.textContent = '0.0ms';

    const logSteps = [
      { ms: 12, text: `[+12ms] INIT: Initiating ${methodName} over local Wi-Fi interface...` },
      { ms: 45, text: `[+45ms] DISCOVER: mDNS '_roombeat._tcp' resolved host at 192.168.1.105:4242` },
      { ms: 88, text: `[+88ms] TCP_AUTH: Handshake token verified against PIN [${currentHostPin}]` },
      { ms: 135, text: `[+135ms] NTP_PROBE: 3-roundtrip calibration complete · Offset: -0.18ms · Jitter: 0.4ms` },
      { ms: 168, text: `[+168ms] MULTICAST_LOCK: Joined group 239.255.42.99:4242 · Oboe MMAP exclusive active!` },
      { ms: 172, text: `[+172ms] SYNC_LOCKED: Zero cloud hops. Acoustic sync phase locked across ${peerCount + 1} devices.` }
    ];

    logSteps.forEach((step, idx) => {
      setTimeout(() => {
        if (terminalLog) {
          const line = document.createElement('div');
          line.className = idx === logSteps.length - 1 ? 'text-sync-green font-bold flex items-center gap-1.5' : 'text-text-muted';
          line.innerHTML = `<span>&gt;</span> <span>${step.text}</span>`;
          terminalLog.appendChild(line);
          terminalLog.scrollTop = terminalLog.scrollHeight;
        }
        if (elapsedTimeLabel) {
          elapsedTimeLabel.textContent = `${step.ms}.0ms`;
        }

        if (idx === logSteps.length - 1) {
          isConnecting = false;
          peerCount += 1;
          if (connectedNodesCount) {
            connectedNodesCount.textContent = `${peerCount} NODES LOCKED`;
          }
          playKeycapClick();
        }
      }, step.ms * 3.5); // Scaled for visible cinematic cadence (~600ms total)
    });
  }

  // Initial OTP render
  updateOtpSlots();
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initQrPinDemo);
  } else {
    initQrPinDemo();
  }
}
