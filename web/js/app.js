import { summarize, SRGB, P3 } from './analysis.js';
import { drawGamma, drawGreyTracking, drawXy } from './charts.js';
import { PATTERNS } from './patterns.js';

const $ = (id) => document.getElementById(id);
const STORE_KEY = 'screenmeter.measurements';

const state = {
  code: null,
  ws: null,
  relayOk: false,
  phone: false,
  camera: null,
  measurements: loadMeasurements(),
  mode: 'home',
  manualIndex: 0,
  running: false,
};

// ---------- session code ----------
function makeCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  const buf = new Uint8Array(5);
  crypto.getRandomValues(buf);
  for (const b of buf) s += alphabet[b % alphabet.length];
  return s;
}
const params = new URLSearchParams(location.search);
state.code = (params.get('code') || sessionStorage.getItem('screenmeter.code') || makeCode()).toUpperCase();
sessionStorage.setItem('screenmeter.code', state.code);
$('code').textContent = state.code;
$('relayUrl').textContent = location.origin;
// When the phone itself serves this page there is nothing to pair: the app is already on the other end.
fetch('/api/health').then((r) => r.json()).then((h) => {
  if (h && h.mode === 'phone') {
    state.phoneHosted = true;
    $('code').textContent = 'direct';
    $('code').style.fontSize = '28px';
    $('relayUrl').parentElement.textContent = 'Served by the phone app over Wi-Fi. No code needed.';
  }
}).catch(() => {});
$('screenInfo').textContent = `${screen.width}x${screen.height} css px, dpr ${devicePixelRatio}, ${screen.colorDepth}-bit`;

// ---------- websocket ----------
function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws?code=${state.code}&role=display`);
  state.ws = ws;
  ws.onopen = () => {
    state.relayOk = true; setDot('relayDot', 'ok'); $('relayStatus').textContent = 'relay connected';
    send({ type: 'hello', role: 'display', screen: { w: screen.width, h: screen.height, dpr: devicePixelRatio, depth: screen.colorDepth } });
  };
  ws.onclose = () => { state.relayOk = false; state.phone = false; setDot('relayDot', ''); $('relayStatus').textContent = 'relay disconnected, retrying'; setPhone(false); setTimeout(connect, 2000); };
  ws.onerror = () => ws.close();
  ws.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch { return; } onMessage(m); };
}
function send(obj) { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify(obj)); }
function setDot(id, cls) { $(id).className = 'dot ' + cls; }
function setPhone(on) {
  state.phone = on; setDot('phoneDot', on ? 'ok' : '');
  $('phoneStatus').textContent = on ? 'phone connected' : 'no phone yet';
  for (const id of ['btnStartFull', 'btnStartQuick']) $(id).disabled = !on || state.running;
}

function onMessage(m) {
  switch (m.type) {
    case 'hello': if (m.role === 'meter') { setPhone(true); send({ type: 'hello', role: 'display', screen: { w: screen.width, h: screen.height, dpr: devicePixelRatio, depth: screen.colorDepth } }); } break;
    case 'peer_present': if (m.role === 'meter') setPhone(true); break;
    case 'peer_left': if (m.role === 'meter') { setPhone(false); state.camera = null; $('cameraInfo').textContent = ''; } break;
    case 'camera': state.camera = m; $('cameraInfo').textContent = describeCamera(m); break;
    case 'run_start': state.measurements = []; saveMeasurements(); state.running = true; setPhone(true); hud(`Run started: ${m.total} patches`); if (state.mode !== 'display') enterDisplay(); break;
    case 'show': showPatch(m); break;
    case 'measure': state.measurements.push(m); saveMeasurements(); hud(`${m.id}: Y ${fmt(m.Y, 3)} x ${fmt(m.x, 3)} y ${fmt(m.y_, 3)}`); updateReportButtons(); break;
    case 'run_done': state.running = false; setPhone(true); hud('Run complete. Press Esc to see the report.'); updateReportButtons(); break;
    case 'status': hud(m.text); break;
    case 'live': hud(`live ${fmt(m.nits, 1)} nits  x ${fmt(m.x, 3)}  y ${fmt(m.y_, 3)}${m.lux != null ? `  light sensor ${fmt(m.lux, 0)} lux` : ''}`); break;
  }
}

function describeCamera(c) {
  const ns = (v) => (v < 0 ? 'n/a' : v >= 1e6 ? (v / 1e6).toFixed(1) + ' ms' : (v / 1e3).toFixed(0) + ' us');
  return `${c.device}\nformat ${c.format}  cfa ${c.cfa}\nRAW ${c.raw ? 'yes' : 'no'}  manual exposure ${c.manual ? 'yes' : 'no'}  colour matrix ${c.colorMatrix ? 'yes' : 'no'}\nexposure ${ns(c.exposureMin)} to ${ns(c.exposureMax)}  ISO ${c.isoMin} to ${c.isoMax}`;
}

// ---------- display mode ----------
function enterDisplay() {
  state.mode = 'display';
  $('home').classList.add('hidden'); $('report').classList.add('hidden'); $('manual').classList.add('hidden');
  $('display').classList.remove('hidden');
  document.documentElement.requestFullscreen?.().catch(() => {});
  showPatch({ id: 'idle', rgb: [24, 24, 24], zone: 0, label: 'Waiting for the phone' });
  hud(`Code ${state.code}. Waiting for the phone to start. Esc exits.`);
}
function leaveDisplay() {
  state.mode = 'home';
  if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  $('display').classList.add('hidden'); $('manual').classList.add('hidden');
  $('home').classList.remove('hidden');
}

function showPatch(m) {
  const [r, g, b] = m.rgb;
  $('patch').style.background = `rgb(${r},${g},${b})`;
  const ring = $('zoneRing');
  if (m.zone && m.zone > 0) {
    const col = (m.zone - 1) % 3, row = Math.floor((m.zone - 1) / 3);
    ring.style.left = `${(col + 0.5) * 100 / 3}%`; ring.style.top = `${(row + 0.5) * 100 / 3}%`;
    ring.classList.remove('hidden');
    hud(`Place the phone inside the ring (zone ${m.zone})`);
  } else {
    ring.classList.add('hidden');
    // Keep the HUD off while a measurement patch is up so it cannot scatter light into the lens.
    $('hud').classList.toggle('off', m.id !== 'idle');
  }
  // Two animation frames guarantee the new colour has been composited before we acknowledge.
  requestAnimationFrame(() => requestAnimationFrame(() => send({ type: 'shown', id: m.id, t: performance.now() })));
}
function hud(text) { const h = $('hud'); h.textContent = text; if (state.mode === 'display') h.classList.remove('off'); }

// ---------- manual patterns ----------
function enterManual() {
  state.mode = 'manual';
  $('home').classList.add('hidden'); $('manual').classList.remove('hidden');
  document.documentElement.requestFullscreen?.().catch(() => {});
  drawManual();
}
function drawManual() {
  const cv = $('manualCanvas');
  cv.width = innerWidth; cv.height = innerHeight;
  const p = PATTERNS[state.manualIndex];
  p.draw(cv.getContext('2d'), cv.width, cv.height);
  $('manualHud').textContent = `${state.manualIndex + 1}/${PATTERNS.length}  ${p.name}   (← → change, Esc exit)`;
}

// ---------- report ----------
function updateReportButtons() {
  const has = state.measurements.length > 0;
  $('btnReport').disabled = !has; $('btnExport').disabled = !has;
}
function renderReport() {
  state.mode = 'report';
  $('home').classList.add('hidden'); $('report').classList.remove('hidden');
  const nits = Number($('nitsInput').value) || null;
  const s = summarize(state.measurements, nits);
  const tiles = [];
  const tile = (k, v, n, cls = '') => tiles.push(`<div class="tile ${cls}"><div class="k">${k}</div><div class="v">${v}</div><div class="n">${n}</div></div>`);
  if (s.nits) tile('White luminance', `${s.nits.white.toFixed(0)} nits`, s.nits.source === 'entered' ? 'from the value you entered' : s.nits.calibration === 1 ? 'phone exposure equation, uncalibrated (±35 %)' : `phone, calibrated ×${s.nits.calibration.toFixed(3)}`, 'good');
  if (s.nits && s.nits.black != null) tile('Black luminance', `${s.nits.black.toFixed(3)} nits`, s.contrastLowerBound ? 'at camera noise floor, true value is lower' : 'scaled from white', s.nits.black < 0.5 ? 'good' : 'warn');
  if (s.gamma != null) tile('Gamma', s.gamma.toFixed(2), 'target 2.2 (sRGB ≈ 2.2)', Math.abs(s.gamma - 2.2) < 0.1 ? 'good' : Math.abs(s.gamma - 2.2) < 0.25 ? 'warn' : 'bad');
  if (s.contrast != null) tile('Contrast', `${s.contrastLowerBound ? '>' : ''}${Math.round(s.contrast)}:1`, s.contrastLowerBound ? 'black at camera noise floor' : 'white / black', s.contrast > 800 ? 'good' : 'warn');
  if (s.white) tile('White point', `${Math.round(s.white.cct)} K`, `x ${s.white.x.toFixed(4)} y ${s.white.y.toFixed(4)}, Δu'v' ${s.white.duvFromD65.toFixed(4)} from D65`, s.white.duvFromD65 < 0.006 ? 'good' : s.white.duvFromD65 < 0.012 ? 'warn' : 'bad');
  if (s.maxDuv != null) tile('Grey tracking', s.maxDuv.toFixed(4), "max Δu'v' across grey ramp", s.maxDuv < 0.004 ? 'good' : s.maxDuv < 0.01 ? 'warn' : 'bad');
  if (s.gamut) tile('sRGB coverage', `${Math.round(s.gamut.srgb.coverage * 100)}%`, `P3 ${Math.round(s.gamut.p3.coverage * 100)}%, area ${Math.round(s.gamut.srgb.areaRatio * 100)}% of sRGB`, s.gamut.srgb.coverage > 0.95 ? 'good' : 'warn');
  if (s.flickerIndex != null) tile('Flicker', s.flickerIndex.toFixed(3), s.flickerIndex > 0.05 ? 'PWM dimming likely' : 'flicker-free at this brightness', s.flickerIndex > 0.05 ? 'warn' : 'good');
  if (s.uniformity) { const mn = Math.min(...s.uniformity.filter((v) => v != null)); tile('Uniformity', `${Math.round(mn * 100)}%`, 'darkest zone vs centre', mn > 0.9 ? 'good' : mn > 0.8 ? 'warn' : 'bad'); }
  if (s.isRaw === false) tile('Caution', 'YUV', 'phone had no RAW: values are approximate', 'bad');
  $('tiles').innerHTML = tiles.join('') || '<p class="muted">No measurements yet.</p>';

  drawGamma($('chartGamma'), s.gammaPoints, s.gamma);
  $('gammaNote').textContent = s.gammaPoints.length ? `Per-step gamma from ${Math.min(...s.gammaPoints.map((p) => p.stepGamma)).toFixed(2)} to ${Math.max(...s.gammaPoints.map((p) => p.stepGamma)).toFixed(2)}. Clipped or noise-floor patches are excluded.` : '';
  drawGreyTracking($('chartGrey'), s.greyTracking);
  drawXy($('chartXy'), s.primaries, s.white, { srgb: SRGB, p3: P3 });
  $('gamutNote').textContent = s.gamut ? 'Chromaticities come from the phone camera colour matrix; treat coverage as indicative, not certified.' : 'Run the full or quick test to measure primaries.';

  const uni = $('uniformity');
  uni.innerHTML = '';
  if (s.uniformity) s.uniformity.forEach((v) => { const d = document.createElement('div'); const g = v == null ? 40 : Math.round(60 + 195 * Math.max(0, Math.min(1, v))); d.style.background = `rgb(${g},${g},${g})`; d.textContent = v == null ? '-' : `${Math.round(v * 100)}%`; uni.appendChild(d); });
  else uni.innerHTML = '<p class="muted" style="grid-column: span 3">Uniformity not run. Tick the option in the app before a full test.</p>';
  $('flickerNote').textContent = s.flickerIndex != null ? `Flicker index ${s.flickerIndex.toFixed(3)} (frame spread or row banding at ~1 ms exposure). Row modulation ${fmt(s.rowModulation, 3)}. Values above 0.05 suggest PWM dimming at the current brightness.` : '';

  const rows = state.measurements.map((m) => `<tr><td><span class="sw" style="background: rgb(${m.rgb.join(',')})"></span>${m.id}</td><td>${m.group}</td><td>${m.nits != null ? fmt(m.nits, m.nits < 10 ? 3 : 1) : '-'}</td><td>${fmt(m.Y, 5)}</td><td>${fmt(m.x, 4)}</td><td>${fmt(m.y_, 4)}</td><td>${m.exposureNs >= 1e6 ? (m.exposureNs / 1e6).toFixed(2) + ' ms' : (m.exposureNs / 1e3).toFixed(0) + ' us'}</td><td>${m.iso}</td><td>${m.frames}</td><td>${fmt(m.snr, 0)}</td><td>${m.saturated ? 'clip' : ''}${m.atNoiseFloor ? ' noise' : ''}${m.isRaw ? '' : ' yuv'}</td></tr>`);
  $('table').innerHTML = `<tr><th>Patch</th><th>Group</th><th>nits</th><th>Y rel</th><th>x</th><th>y</th><th>Exposure</th><th>ISO</th><th>Frames</th><th>SNR</th><th>Flags</th></tr>${rows.join('')}`;
}

// ---------- storage ----------
function loadMeasurements() { try { return JSON.parse(localStorage.getItem(STORE_KEY) || '[]'); } catch { return []; } }
function saveMeasurements() { try { localStorage.setItem(STORE_KEY, JSON.stringify(state.measurements)); } catch { /* private mode */ } }
function fmt(v, d) { return v == null || Number.isNaN(v) ? '-' : Number(v).toFixed(d); }

// ---------- wiring ----------
$('btnDisplay').onclick = enterDisplay;
$('btnManual').onclick = enterManual;
$('btnStartFull').onclick = () => { enterDisplay(); send({ type: 'start', suite: 'full' }); };
$('btnStartQuick').onclick = () => { enterDisplay(); send({ type: 'start', suite: 'quick' }); };
$('btnReport').onclick = renderReport;
$('btnBack').onclick = () => { state.mode = 'home'; $('report').classList.add('hidden'); $('home').classList.remove('hidden'); };
$('nitsInput').oninput = () => { if (state.mode === 'report') renderReport(); };
$('btnExport').onclick = () => {
  const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), screen: { w: screen.width, h: screen.height, dpr: devicePixelRatio }, camera: state.camera, measurements: state.measurements, summary: summarize(state.measurements, Number($('nitsInput').value) || null) }, null, 2)], { type: 'application/json' });
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `screenmeter-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.json`; a.click();
};
$('btnClear').onclick = () => { if (confirm('Delete stored measurements?')) { state.measurements = []; saveMeasurements(); updateReportButtons(); } };

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { if (state.mode === 'display' || state.mode === 'manual') { leaveDisplay(); if (state.measurements.length) renderReport(); } }
  if (state.mode === 'manual') {
    if (e.key === 'ArrowRight') { state.manualIndex = (state.manualIndex + 1) % PATTERNS.length; drawManual(); }
    if (e.key === 'ArrowLeft') { state.manualIndex = (state.manualIndex + PATTERNS.length - 1) % PATTERNS.length; drawManual(); }
  }
});
document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && (state.mode === 'display' || state.mode === 'manual') && !state.running) leaveDisplay(); });
addEventListener('resize', () => { if (state.mode === 'manual') drawManual(); });

updateReportButtons();
connect();
