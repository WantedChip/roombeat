/**
 * RoomBeat Comparison Matrix & Cost Ticker Engine (v0.9.7)
 * 
 * Orchestrates:
 * 1. Magic UI Number Ticker via GSAP for cumulative subscription toll calculations.
 * 2. 1, 2, and 3-year time-horizon slider and quick presets ($9.99/wk -> $519.48/yr -> $1,558.44/3-yr).
 * 3. Dynamic savings calculation: $0.00 RoomBeat FOSS vs proprietary subscription trap.
 */

import gsap from 'gsap';
import { playKeycapClick } from './qrPinDemo.js';

export function initCostCalculatorTicker() {
  const slider = document.getElementById('cost-duration-slider');
  const btn1Year = document.getElementById('btn-cost-1yr');
  const btn2Years = document.getElementById('btn-cost-2yr');
  const btn3Years = document.getElementById('btn-cost-3yr');
  const commercialCostTicker = document.getElementById('commercial-cost-ticker');
  const netSavingsTicker = document.getElementById('net-savings-ticker');
  const weeksCountLabel = document.getElementById('cost-weeks-count');
  const durationLabel = document.getElementById('cost-duration-label');

  if (!slider || !commercialCostTicker || !netSavingsTicker) return;

  const WEEKLY_RATE = 9.99; // AmpMe VIP subscription rate
  let currentYears = 1;

  // Animated values proxy for GSAP Number Ticker
  const tickerProxy = {
    commercial: 519.48,
    savings: 519.48
  };

  function updateCost(years, animate = true) {
    currentYears = years;
    const weeks = years * 52;
    const targetCommercial = Number((weeks * WEEKLY_RATE).toFixed(2));
    const targetSavings = targetCommercial; // RoomBeat is $0.00 Forever

    if (slider) slider.value = String(years);
    if (weeksCountLabel) weeksCountLabel.textContent = `${weeks} weeks`;
    if (durationLabel) durationLabel.textContent = `${years} Year${years > 1 ? 's' : ''}`;

    // Highlight active preset button
    const presetBtns = [
      { btn: btn1Year, val: 1 },
      { btn: btn2Years, val: 2 },
      { btn: btn3Years, val: 3 },
    ];

    presetBtns.forEach(({ btn, val }) => {
      if (btn) {
        if (val === years) {
          btn.classList.add('bg-sync-green/20', 'text-sync-green', 'border-sync-green/60');
          btn.classList.remove('text-text-muted', 'border-border-milled');
        } else {
          btn.classList.remove('bg-sync-green/20', 'text-sync-green', 'border-sync-green/60');
          btn.classList.add('text-text-muted', 'border-border-milled');
        }
      }
    });

    const isReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (!animate || isReducedMotion) {
      tickerProxy.commercial = targetCommercial;
      tickerProxy.savings = targetSavings;
      commercialCostTicker.textContent = `$${targetCommercial.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      netSavingsTicker.textContent = `+$${targetSavings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} POCKETED`;
      return;
    }

    // GSAP Number Ticker Animation
    gsap.to(tickerProxy, {
      commercial: targetCommercial,
      savings: targetSavings,
      duration: 0.45,
      ease: 'power2.out',
      onUpdate: () => {
        commercialCostTicker.textContent = `$${tickerProxy.commercial.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
        netSavingsTicker.textContent = `+$${tickerProxy.savings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} POCKETED`;
      }
    });
  }

  // Slider Input Listener
  slider.addEventListener('input', (e) => {
    const val = Number(e.target.value);
    playKeycapClick();
    updateCost(val, true);
  });

  // Preset Buttons Listeners
  btn1Year?.addEventListener('click', () => {
    playKeycapClick();
    updateCost(1, true);
  });

  btn2Years?.addEventListener('click', () => {
    playKeycapClick();
    updateCost(2, true);
  });

  btn3Years?.addEventListener('click', () => {
    playKeycapClick();
    updateCost(3, true);
  });

  // Initial calculation
  updateCost(1, false);
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCostCalculatorTicker);
  } else {
    initCostCalculatorTicker();
  }
}
