// Manual visual test patterns (no phone needed). Each draws into a canvas sized to the screen.
export const PATTERNS = [
  { name: 'Gamma check: stripes should match the solid patch at the 2.2 column', draw: gammaCheck },
  { name: 'Black level: all 20 dark squares should be distinguishable', draw: blackLevel },
  { name: 'White saturation: all 20 bright squares should be distinguishable', draw: whiteSat },
  { name: 'Gradient: look for banding or colour tint', draw: gradient },
  { name: 'Contrast checkerboard (ANSI-style)', draw: checker },
  { name: 'Full white', draw: (ctx, w, h) => { ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h); } },
  { name: 'Full black', draw: (ctx, w, h) => { ctx.fillStyle = '#000'; ctx.fillRect(0, 0, w, h); } },
];

function gammaCheck(ctx, w, h) {
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, w, h);
  const gammas = [1.8, 2.0, 2.2, 2.4, 2.6];
  const colW = w / gammas.length;
  gammas.forEach((g, i) => {
    const x = i * colW;
    // Alternating black/white lines average to 50% luminance; the solid patch is the level whose luminance is 0.5 at gamma g.
    const lvl = Math.round(255 * 0.5 ** (1 / g));
    for (let y = 0; y < h; y += 2) { ctx.fillStyle = '#fff'; ctx.fillRect(x, y, colW, 1); }
    ctx.fillStyle = `rgb(${lvl},${lvl},${lvl})`;
    ctx.fillRect(x + colW * 0.25, h * 0.3, colW * 0.5, h * 0.4);
    ctx.fillStyle = '#888'; ctx.font = '18px system-ui'; ctx.textAlign = 'center'; ctx.fillText(g.toFixed(1), x + colW / 2, h * 0.25);
  });
}
function blackLevel(ctx, w, h) {
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, w, h);
  steps(ctx, w, h, (i) => i + 1);
}
function whiteSat(ctx, w, h) {
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h);
  steps(ctx, w, h, (i) => 254 - i);
}
function steps(ctx, w, h, lvl) {
  const cols = 10, rows = 2, s = Math.min(w / (cols + 1), h / 4);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const i = r * cols + c; const v = lvl(i);
    ctx.fillStyle = `rgb(${v},${v},${v})`;
    ctx.fillRect((c + 0.5) * s, h / 2 - s + r * (s + 8), s * 0.9, s * 0.9);
  }
}
function gradient(ctx, w, h) {
  const g = ctx.createLinearGradient(0, 0, w, 0); g.addColorStop(0, '#000'); g.addColorStop(1, '#fff');
  ctx.fillStyle = g; ctx.fillRect(0, 0, w, h / 2);
  for (let i = 0; i < 256; i++) { ctx.fillStyle = `rgb(${i},${i},${i})`; ctx.fillRect(i * w / 256, h / 2, w / 256 + 1, h / 2); }
}
function checker(ctx, w, h) {
  const cols = 4, rows = 4;
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { ctx.fillStyle = (r + c) % 2 ? '#fff' : '#000'; ctx.fillRect(c * w / cols, r * h / rows, w / cols + 1, h / rows + 1); }
}
