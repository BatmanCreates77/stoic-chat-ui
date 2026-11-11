import { test } from 'node:test';
import assert from 'node:assert/strict';
import { summarize, gamutCoverage, SRGB, cct, deltaUv } from './analysis.js';

function synth(gamma) {
  const ms = [];
  const push = (id, group, rgb, Y, x = 0.3127, y = 0.3290, extra = {}) => ms.push({ id, group, zone: 0, rgb, Y, x, y_: y, saturated: false, atNoiseFloor: false, isRaw: true, ...extra });
  push('white', 'reference', [255, 255, 255], 1.0);
  push('black', 'reference', [0, 0, 0], 0.001);
  for (const l of [16, 32, 64, 96, 128, 160, 192, 224, 240]) push(`grey_${l}`, 'grey', [l, l, l], (l / 255) ** gamma);
  push('red', 'primary', [255, 0, 0], 0.2126, 0.64, 0.33);
  push('green', 'primary', [0, 255, 0], 0.7152, 0.30, 0.60);
  push('blue', 'primary', [0, 0, 255], 0.0722, 0.15, 0.06);
  push('flicker', 'flicker', [255, 255, 255], 1.0, 0.3127, 0.3290, { flickerIndex: 0.01, rowModulation: 0.004 });
  return ms;
}

test('recovers the gamma of a synthetic panel', () => {
  const s = summarize(synth(2.2));
  assert.ok(Math.abs(s.gamma - 2.2) < 1e-6, `gamma ${s.gamma}`);
  assert.ok(Math.abs(s.contrast - 1000) < 1e-6);
  assert.equal(s.contrastLowerBound, false);
});

test('perfect sRGB primaries cover 100% of sRGB and less of P3', () => {
  const s = summarize(synth(2.4));
  assert.ok(Math.abs(s.gamut.srgb.coverage - 1) < 1e-6);
  assert.ok(s.gamut.p3.coverage < 1 && s.gamut.p3.coverage > 0.7);
  assert.ok(Math.abs(s.white.cct - 6500) < 60, `cct ${s.white.cct}`);
  assert.ok(s.maxDuv < 1e-9);
});

test('gamut coverage handles a smaller triangle', () => {
  const half = [[0.5, 0.33], [0.3, 0.5], [0.2, 0.1]];
  const g = gamutCoverage(half, SRGB);
  assert.ok(g.coverage > 0.3 && g.coverage < 0.8, `coverage ${g.coverage}`);
});

test('helpers behave', () => {
  assert.ok(Math.abs(cct(0.3127, 0.3290) - 6500) < 60);
  assert.ok(deltaUv([0.3127, 0.3290], [0.3127, 0.3290]) === 0);
});
