// Pure analysis of phone measurements. No DOM here so it can be unit tested in Node.

export const SRGB = { r: [0.64, 0.33], g: [0.30, 0.60], b: [0.15, 0.06], w: [0.3127, 0.3290] };
export const P3 = { r: [0.680, 0.320], g: [0.265, 0.690], b: [0.150, 0.060], w: [0.3127, 0.3290] };

export function cct(x, y) {
  const n = (x - 0.3320) / (0.1858 - y);
  return 449 * n ** 3 + 3525 * n ** 2 + 6823.3 * n + 5520.33;
}

export function uv(x, y) {
  const d = -2 * x + 12 * y + 3;
  return d === 0 ? [0, 0] : [4 * x / d, 9 * y / d];
}

export function deltaUv(a, b) {
  const [ua, va] = uv(a[0], a[1]);
  const [ub, vb] = uv(b[0], b[1]);
  return Math.hypot(ua - ub, va - vb);
}

export function srgbToLinear(v) { return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }

/** Area of a convex polygon given as [[x,y],...]. */
export function polyArea(p) {
  let a = 0;
  for (let i = 0; i < p.length; i++) { const [x1, y1] = p[i]; const [x2, y2] = p[(i + 1) % p.length]; a += x1 * y2 - x2 * y1; }
  return Math.abs(a) / 2;
}

/** Sutherland-Hodgman clip of convex polygon `subject` by convex polygon `clip`. */
export function clipPolygon(subject, clip) {
  let output = subject.slice();
  const ccw = polyArea(clip) > 0 && signedArea(clip) > 0 ? clip : clip.slice().reverse();
  for (let i = 0; i < ccw.length; i++) {
    const a = ccw[i], b = ccw[(i + 1) % ccw.length];
    const input = output; output = [];
    if (input.length === 0) break;
    const inside = (p) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0;
    const intersect = (p, q) => {
      const d1 = [p[0] - q[0], p[1] - q[1]], d2 = [a[0] - b[0], a[1] - b[1]];
      const den = d1[0] * d2[1] - d1[1] * d2[0];
      if (Math.abs(den) < 1e-12) return p;
      const n1 = p[0] * q[1] - p[1] * q[0], n2 = a[0] * b[1] - a[1] * b[0];
      return [(n1 * d2[0] - d1[0] * n2) / den, (n1 * d2[1] - d1[1] * n2) / den];
    };
    for (let j = 0; j < input.length; j++) {
      const cur = input[j], prev = input[(j + input.length - 1) % input.length];
      if (inside(cur)) { if (!inside(prev)) output.push(intersect(prev, cur)); output.push(cur); }
      else if (inside(prev)) output.push(intersect(prev, cur));
    }
  }
  return output;
}
function signedArea(p) { let a = 0; for (let i = 0; i < p.length; i++) { const [x1, y1] = p[i]; const [x2, y2] = p[(i + 1) % p.length]; a += x1 * y2 - x2 * y1; } return a / 2; }

/** Fraction of the reference gamut triangle covered by the measured triangle (0..1), plus measured/reference area ratio. */
export function gamutCoverage(measured, reference) {
  const ref = [reference.r, reference.g, reference.b];
  const inter = clipPolygon(measured, ref);
  const refArea = polyArea(ref);
  return { coverage: refArea > 0 ? polyArea(inter) / refArea : 0, areaRatio: refArea > 0 ? polyArea(measured) / refArea : 0 };
}

export function summarize(ms, nitsWhite) {
  const byId = Object.fromEntries(ms.map((m) => [m.id, m]));
  const white = byId.white, black = byId.black;
  const wy = white?.Y || 0;
  const scale = nitsWhite && wy > 0 ? nitsWhite / wy : null;

  const greys = ms.filter((m) => m.group === 'grey' && !m.saturated && !m.atNoiseFloor && wy > 0 && m.Y > 0);
  const gammaPoints = greys.map((m) => ({ level: m.rgb[0], rel: m.Y / wy, srgb: srgbToLinear(m.rgb[0] / 255), g22: (m.rgb[0] / 255) ** 2.2 }));
  let num = 0, den = 0;
  for (const p of gammaPoints) { const lx = Math.log(p.level / 255), ly = Math.log(p.rel); num += lx * ly; den += lx * lx; }
  const gamma = den > 0 ? num / den : null;
  // Per-step gamma, useful to see where a panel departs from the curve.
  for (const p of gammaPoints) p.stepGamma = Math.log(p.rel) / Math.log(p.level / 255);

  let contrast = null, contrastLowerBound = false;
  if (white && black) {
    if (black.Y > 0 && !black.atNoiseFloor) contrast = wy / black.Y;
    else { contrastLowerBound = true; contrast = black.Y > 0 ? wy / black.Y : null; }
  }

  const whiteXy = white ? [white.x, white.y_] : null;
  const greyTracking = greys.filter((m) => m.rgb[0] >= 16).map((m) => ({ level: m.rgb[0], duv: whiteXy ? deltaUv([m.x, m.y_], whiteXy) : null, x: m.x, y: m.y_ }));
  const maxDuv = greyTracking.length ? Math.max(...greyTracking.map((g) => g.duv)) : null;

  const prim = ['red', 'green', 'blue'].map((id) => byId[id]).filter(Boolean).map((m) => [m.x, m.y_]);
  const primaries = ms.filter((m) => m.group === 'primary' || m.group === 'primary50').map((m) => ({ id: m.id, x: m.x, y: m.y_, rgb: m.rgb }));
  const gamut = prim.length === 3 ? { srgb: gamutCoverage(prim, SRGB), p3: gamutCoverage(prim, P3) } : null;

  const zones = ms.filter((m) => m.group === 'uniformity');
  const centre = zones.find((z) => z.zone === 5)?.Y;
  const uniformity = zones.length ? Array.from({ length: 9 }, (_, i) => { const z = zones.find((m) => m.zone === i + 1); return z && centre ? z.Y / centre : null; }) : null;

  const flick = ms.find((m) => m.group === 'flicker');

  return {
    gamma, gammaPoints, contrast, contrastLowerBound,
    white: whiteXy ? { x: whiteXy[0], y: whiteXy[1], cct: cct(whiteXy[0], whiteXy[1]), duvFromD65: deltaUv(whiteXy, SRGB.w) } : null,
    greyTracking, maxDuv, primaries, gamut, uniformity,
    flickerIndex: flick ? flick.flickerIndex : null,
    rowModulation: flick ? flick.rowModulation : null,
    nits: scale ? { white: nitsWhite, black: black ? black.Y * scale : null, scale } : null,
    isRaw: ms.length ? ms.every((m) => m.isRaw) : null,
  };
}
