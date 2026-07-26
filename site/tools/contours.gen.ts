/**
 * Bathymetric contour tile generator.
 *
 * Builds a periodic Perlin field, then traces iso-lines with marching squares.
 * Periodic lattice wrapping means the emitted tile repeats seamlessly, so the
 * hero can be any width without a visible seam. Output is a plain SVG of
 * polylines — no runtime cost, no JS, embeddable as a data URI.
 */

const TILE = 1200;      // tile size in px
const GRID = 300;       // sampling resolution
const LEVELS = 15;      // number of contour lines

// ---- periodic Perlin ------------------------------------------------------

function mulberry(seed: number) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function makeOctave(res: number, rnd: () => number) {
  // Unit gradients on a res×res lattice, wrapped so the field is periodic.
  const gx: number[] = [], gy: number[] = [];
  for (let i = 0; i < res * res; i++) {
    const a = rnd() * Math.PI * 2;
    gx.push(Math.cos(a)); gy.push(Math.sin(a));
  }
  const fade = (t: number) => t * t * t * (t * (t * 6 - 15) + 10);
  return (x: number, y: number) => {
    const xi = Math.floor(x), yi = Math.floor(y);
    const xf = x - xi, yf = y - yi;
    const at = (ix: number, iy: number, dx: number, dy: number) => {
      const k = (((iy % res) + res) % res) * res + (((ix % res) + res) % res);
      return gx[k] * dx + gy[k] * dy;
    };
    const u = fade(xf), v = fade(yf);
    const n00 = at(xi, yi, xf, yf);
    const n10 = at(xi + 1, yi, xf - 1, yf);
    const n01 = at(xi, yi + 1, xf, yf - 1);
    const n11 = at(xi + 1, yi + 1, xf - 1, yf - 1);
    const a = n00 + u * (n10 - n00);
    const b = n01 + u * (n11 - n01);
    return a + v * (b - a);
  };
}

const rnd = mulberry(20260725);
const octaves = [
  { res: 3, amp: 1.0 },
  { res: 6, amp: 0.5 },
  { res: 12, amp: 0.25 },
].map((o) => ({ ...o, fn: makeOctave(o.res, rnd) }));

function field(u: number, v: number) {
  let sum = 0, norm = 0;
  for (const o of octaves) {
    sum += o.fn(u * o.res, v * o.res) * o.amp;
    norm += o.amp;
  }
  return sum / norm;
}

// ---- sample ---------------------------------------------------------------

const vals = new Float64Array((GRID + 1) * (GRID + 1));
let lo = Infinity, hi = -Infinity;
for (let j = 0; j <= GRID; j++) {
  for (let i = 0; i <= GRID; i++) {
    const v = field(i / GRID, j / GRID);
    vals[j * (GRID + 1) + i] = v;
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
}
const at = (i: number, j: number) => vals[j * (GRID + 1) + i];

// ---- marching squares -----------------------------------------------------

type Pt = [number, number];
const key = (p: Pt) => `${p[0].toFixed(2)},${p[1].toFixed(2)}`;

function isoSegments(t: number): [Pt, Pt][] {
  const segs: [Pt, Pt][] = [];
  const lerp = (x1: number, y1: number, v1: number, x2: number, y2: number, v2: number): Pt => {
    const f = (t - v1) / (v2 - v1 || 1e-9);
    return [(x1 + (x2 - x1) * f) * (TILE / GRID), (y1 + (y2 - y1) * f) * (TILE / GRID)];
  };
  for (let j = 0; j < GRID; j++) {
    for (let i = 0; i < GRID; i++) {
      const v0 = at(i, j), v1 = at(i + 1, j), v2 = at(i + 1, j + 1), v3 = at(i, j + 1);
      const idx = (v0 > t ? 1 : 0) | (v1 > t ? 2 : 0) | (v2 > t ? 4 : 0) | (v3 > t ? 8 : 0);
      if (idx === 0 || idx === 15) continue;
      const top = () => lerp(i, j, v0, i + 1, j, v1);
      const right = () => lerp(i + 1, j, v1, i + 1, j + 1, v2);
      const bottom = () => lerp(i, j + 1, v3, i + 1, j + 1, v2);
      const left = () => lerp(i, j, v0, i, j + 1, v3);
      const push = (a: Pt, b: Pt) => segs.push([a, b]);
      switch (idx) {
        case 1: case 14: push(left(), top()); break;
        case 2: case 13: push(top(), right()); break;
        case 3: case 12: push(left(), right()); break;
        case 4: case 11: push(right(), bottom()); break;
        case 6: case 9:  push(top(), bottom()); break;
        case 7: case 8:  push(left(), bottom()); break;
        case 5:  push(left(), top()); push(right(), bottom()); break;
        case 10: push(top(), right()); push(left(), bottom()); break;
      }
    }
  }
  return segs;
}

// Chain segments into polylines so the SVG is a handful of paths, not 40k moves.
function chain(segs: [Pt, Pt][]): Pt[][] {
  const buckets = new Map<string, number[]>();
  segs.forEach(([a, b], i) => {
    for (const p of [a, b]) {
      const k = key(p);
      if (!buckets.has(k)) buckets.set(k, []);
      buckets.get(k)!.push(i);
    }
  });
  const used = new Set<number>();
  const out: Pt[][] = [];
  for (let i = 0; i < segs.length; i++) {
    if (used.has(i)) continue;
    used.add(i);
    const line: Pt[] = [segs[i][0], segs[i][1]];
    // extend forward, then backward
    for (const dir of [1, 0]) {
      for (;;) {
        const end = dir ? line[line.length - 1] : line[0];
        const cand = (buckets.get(key(end)) ?? []).find((s) => !used.has(s));
        if (cand === undefined) break;
        used.add(cand);
        const [a, b] = segs[cand];
        const next = key(a) === key(end) ? b : a;
        if (dir) line.push(next); else line.unshift(next);
      }
    }
    if (line.length > 3) out.push(line);
  }
  return out;
}

// Ramer–Douglas–Peucker. Marching squares emits a vertex per cell crossing;
// at 3% opacity almost all of them are redundant.
function simplify(pts: Pt[], eps: number): Pt[] {
  if (pts.length < 3) return pts;
  const [ax, ay] = pts[0], [bx, by] = pts[pts.length - 1];
  const dx = bx - ax, dy = by - ay;
  const len = Math.hypot(dx, dy) || 1e-9;
  let worst = 0, at = 0;
  for (let i = 1; i < pts.length - 1; i++) {
    const d = Math.abs(dy * pts[i][0] - dx * pts[i][1] + bx * ay - by * ax) / len;
    if (d > worst) { worst = d; at = i; }
  }
  if (worst <= eps) return [pts[0], pts[pts.length - 1]];
  return [...simplify(pts.slice(0, at + 1), eps).slice(0, -1), ...simplify(pts.slice(at), eps)];
}

const paths: string[] = [];
for (let k = 1; k < LEVELS; k++) {
  const t = lo + ((hi - lo) * k) / LEVELS;
  for (const line of chain(isoSegments(t))) {
    const pts = simplify(line, 1.2);
    paths.push("M" + pts.map((p) => `${Math.round(p[0])} ${Math.round(p[1])}`).join("L"));
  }
}

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${TILE}" height="${TILE}" viewBox="0 0 ${TILE} ${TILE}">
<g fill="none" stroke="#ffffff" stroke-width="1" stroke-linejoin="round" stroke-linecap="round">
<path d="${paths.join("")}"/>
</g>
</svg>`;

await Bun.write("contours.svg", svg);
console.log(`contours.svg — ${paths.length} polylines, ${(svg.length / 1024).toFixed(1)} KB`);
