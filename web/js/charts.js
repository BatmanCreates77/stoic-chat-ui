// Small canvas charts. Colours match the page theme; no library.
const C = { bg: '#0e1216', grid: '#263039', text: '#93a1ae', accent: '#4fd1c5', ref: '#6b7c8c', ref2: '#b48cff', warn: '#ffb454', bad: '#ff7a7a', good: '#7ee787' };

function frame(canvas, pad = { l: 56, r: 16, t: 16, b: 40 }) {
  const ctx = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  ctx.fillStyle = C.bg; ctx.fillRect(0, 0, W, H);
  return { ctx, W, H, pad, pw: W - pad.l - pad.r, ph: H - pad.t - pad.b };
}

function axis(f, xl, yl) {
  const { ctx, pad, W, H } = f;
  ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(pad.l, pad.t); ctx.lineTo(pad.l, H - pad.b); ctx.lineTo(W - pad.r, H - pad.b); ctx.stroke();
  ctx.fillStyle = C.text; ctx.font = '12px system-ui, sans-serif'; ctx.textAlign = 'center';
  ctx.fillText(xl, pad.l + f.pw / 2, H - 8);
  ctx.save(); ctx.translate(14, pad.t + f.ph / 2); ctx.rotate(-Math.PI / 2); ctx.fillText(yl, 0, 0); ctx.restore();
}

function legend(f, items) {
  const { ctx, pad } = f;
  let x = pad.l + 10;
  ctx.font = '12px system-ui, sans-serif'; ctx.textAlign = 'left';
  for (const [label, color] of items) {
    ctx.fillStyle = color; ctx.fillRect(x, pad.t + 6, 12, 3);
    ctx.fillStyle = C.text; ctx.fillText(label, x + 16, pad.t + 11);
    x += ctx.measureText(label).width + 34;
  }
}

export function drawGamma(canvas, points, gammaFit) {
  const f = frame(canvas);
  const { ctx, pad, ph, pw, H } = f;
  const X = (lvl) => pad.l + (lvl / 255) * pw;
  const Y = (rel) => H - pad.b - rel * ph;
  axis(f, 'Input level (0-255)', 'Relative luminance');
  ctx.strokeStyle = C.grid; ctx.setLineDash([3, 4]);
  for (let i = 1; i <= 4; i++) { const y = Y(i / 4); ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(pad.l + pw, y); ctx.stroke(); ctx.fillStyle = C.text; ctx.textAlign = 'right'; ctx.fillText((i / 4).toFixed(2), pad.l - 6, y + 4); }
  ctx.setLineDash([]);
  const curve = (fn, color) => { ctx.strokeStyle = color; ctx.lineWidth = 1.5; ctx.beginPath(); for (let l = 0; l <= 255; l++) { const p = [X(l), Y(fn(l / 255))]; l === 0 ? ctx.moveTo(...p) : ctx.lineTo(...p); } ctx.stroke(); };
  curve((v) => v ** 2.2, C.ref);
  curve((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4), C.ref2);
  if (gammaFit) curve((v) => v ** gammaFit, C.accent);
  ctx.fillStyle = C.warn;
  for (const p of points) { ctx.beginPath(); ctx.arc(X(p.level), Y(Math.min(1.05, p.rel)), 4, 0, Math.PI * 2); ctx.fill(); }
  legend(f, [['Measured', C.warn], [`Fit ${gammaFit ? gammaFit.toFixed(2) : '-'}`, C.accent], ['Gamma 2.2', C.ref], ['sRGB', C.ref2]]);
}

export function drawGreyTracking(canvas, tracking) {
  const f = frame(canvas);
  const { ctx, pad, ph, pw, H } = f;
  const maxV = Math.max(0.012, ...tracking.map((t) => t.duv || 0)) * 1.1;
  const X = (lvl) => pad.l + (lvl / 255) * pw;
  const Y = (v) => H - pad.b - (v / maxV) * ph;
  axis(f, 'Input level (0-255)', "Δu'v' from white");
  ctx.setLineDash([3, 4]);
  for (const [v, color, label] of [[0.0033, C.good, '1 JND'], [0.01, C.bad, 'visible']]) {
    if (v > maxV) continue;
    ctx.strokeStyle = color; ctx.beginPath(); ctx.moveTo(pad.l, Y(v)); ctx.lineTo(pad.l + pw, Y(v)); ctx.stroke();
    ctx.fillStyle = color; ctx.textAlign = 'left'; ctx.fillText(label, pad.l + pw - 50, Y(v) - 4);
  }
  ctx.setLineDash([]);
  ctx.fillStyle = C.text; ctx.textAlign = 'right';
  for (let i = 0; i <= 4; i++) ctx.fillText((maxV * i / 4).toFixed(4), pad.l - 6, Y(maxV * i / 4) + 4);
  ctx.strokeStyle = C.accent; ctx.lineWidth = 1.5; ctx.beginPath();
  tracking.forEach((t, i) => { const p = [X(t.level), Y(t.duv)]; i === 0 ? ctx.moveTo(...p) : ctx.lineTo(...p); });
  ctx.stroke();
  ctx.fillStyle = C.accent;
  for (const t of tracking) { ctx.beginPath(); ctx.arc(X(t.level), Y(t.duv), 4, 0, Math.PI * 2); ctx.fill(); }
}

// Coarse CIE 1931 spectral locus (wavelength nm: x, y).
const LOCUS = [[0.1741, 0.0050], [0.1738, 0.0049], [0.1733, 0.0048], [0.1726, 0.0048], [0.1714, 0.0051], [0.1689, 0.0069], [0.1644, 0.0109], [0.1566, 0.0177], [0.1440, 0.0297], [0.1241, 0.0578], [0.0913, 0.1327], [0.0454, 0.2950], [0.0082, 0.5384], [0.0139, 0.7502], [0.0743, 0.8338], [0.1547, 0.8059], [0.2296, 0.7543], [0.3016, 0.6923], [0.3731, 0.6245], [0.4441, 0.5547], [0.5125, 0.4866], [0.5752, 0.4242], [0.6270, 0.3725], [0.6658, 0.3340], [0.6915, 0.3083], [0.7079, 0.2920], [0.7190, 0.2809], [0.7260, 0.2740], [0.7300, 0.2700], [0.7334, 0.2666], [0.7347, 0.2653]];

export function drawXy(canvas, primaries, white, refs) {
  const f = frame(canvas, { l: 48, r: 16, t: 16, b: 36 });
  const { ctx, pad, ph, pw, H } = f;
  const X = (x) => pad.l + (x / 0.8) * pw;
  const Y = (y) => H - pad.b - (y / 0.9) * ph;
  axis(f, 'x', 'y');
  ctx.strokeStyle = C.grid; ctx.setLineDash([3, 4]);
  for (let i = 1; i <= 8; i++) { ctx.beginPath(); ctx.moveTo(X(i / 10), pad.t); ctx.lineTo(X(i / 10), H - pad.b); ctx.stroke(); }
  for (let i = 1; i <= 9; i++) { ctx.beginPath(); ctx.moveTo(pad.l, Y(i / 10)); ctx.lineTo(pad.l + pw, Y(i / 10)); ctx.stroke(); }
  ctx.setLineDash([]);
  ctx.strokeStyle = '#3a4653'; ctx.lineWidth = 1.5; ctx.beginPath();
  LOCUS.forEach(([x, y], i) => (i === 0 ? ctx.moveTo(X(x), Y(y)) : ctx.lineTo(X(x), Y(y)))); ctx.closePath(); ctx.stroke();
  const tri = (g, color, label) => {
    ctx.strokeStyle = color; ctx.lineWidth = 1.5; ctx.beginPath();
    ctx.moveTo(X(g.r[0]), Y(g.r[1])); ctx.lineTo(X(g.g[0]), Y(g.g[1])); ctx.lineTo(X(g.b[0]), Y(g.b[1])); ctx.closePath(); ctx.stroke();
    ctx.fillStyle = color; ctx.font = '11px system-ui'; ctx.textAlign = 'left'; ctx.fillText(label, X(g.g[0]) + 6, Y(g.g[1]) - 4);
  };
  tri(refs.srgb, C.ref, 'sRGB'); tri(refs.p3, C.ref2, 'P3');
  const meas = ['red', 'green', 'blue'].map((id) => primaries.find((p) => p.id === id)).filter(Boolean);
  if (meas.length === 3) { ctx.strokeStyle = C.accent; ctx.lineWidth = 2; ctx.beginPath(); meas.forEach((p, i) => (i === 0 ? ctx.moveTo(X(p.x), Y(p.y)) : ctx.lineTo(X(p.x), Y(p.y)))); ctx.closePath(); ctx.stroke(); }
  for (const p of primaries) { ctx.fillStyle = `rgb(${p.rgb.join(',')})`; ctx.strokeStyle = '#fff'; ctx.beginPath(); ctx.arc(X(p.x), Y(p.y), 5, 0, Math.PI * 2); ctx.fill(); ctx.stroke(); }
  if (white) { ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(X(white.x), Y(white.y), 5, 0, Math.PI * 2); ctx.fill(); ctx.strokeStyle = C.warn; ctx.beginPath(); ctx.arc(X(refs.srgb.w[0]), Y(refs.srgb.w[1]), 7, 0, Math.PI * 2); ctx.stroke(); }
  legend(f, [['Measured', C.accent], ['D65 target', C.warn]]);
}
