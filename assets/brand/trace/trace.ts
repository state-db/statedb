/* One-off asset pipeline: trace the original raster logo into flat-color SVG.
 *
 * 1. Snap every pixel to blue / navy / "light" with explicit rules (nearest-
 *    neighbor misassigns anti-aliased edge pixels).
 * 2. Flood-fill light pixels from the borders: that region is the true
 *    background and becomes transparent.
 * 3. Each remaining enclosed light region becomes cream or white based on its
 *    average warmth (the face is warm cream; eye glints and letter counters
 *    are neutral white). Per-pixel thresholds mottle here because the source
 *    is AI-generated and noisy.
 * 4. Trace the clean 4-color bitmap and normalize fills to brand hexes.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { PNG } from "pngjs";

const ImageTracer = require("imagetracerjs");

const NAVY = { r: 24, g: 43, b: 77 };
const BLUE = { r: 37, g: 99, b: 235 };
const CREAM = { r: 247, g: 243, b: 232 };
const WHITE = { r: 255, g: 255, b: 255 };
const SENTINEL = { r: 255, g: 0, b: 255 }; // border-connected background

const PALETTE = [SENTINEL, WHITE, CREAM, NAVY, BLUE].map((c) => ({
  ...c,
  a: 255,
}));

const HEX: Record<string, string> = {
  "rgb(255,0,255)": "DROP",
  "rgb(255,255,255)": "#FFFFFF",
  "rgb(247,243,232)": "#F7F3E8",
  "rgb(24,43,77)": "#182B4D",
  "rgb(37,99,235)": "#2563EB",
};

const [, , input, output, ltresArg, qtresArg, pathomitArg] = process.argv;
const png = PNG.sync.read(readFileSync(input));
const { width, height, data } = png;
const size = width * height;
const orig = Buffer.from(data);

// 0 = navy/blue (set below), 1 = light, 2 = background, 3+ = region ids
const label = new Int32Array(size);
for (let i = 0; i < size; i++) {
  const r = orig[i * 4];
  const g = orig[i * 4 + 1];
  const b = orig[i * 4 + 2];
  let c;
  if (b >= 150 && b - r >= 50) c = BLUE;
  else if ((r + g + b) / 3 < 200) c = NAVY;
  else {
    label[i] = 1;
    continue;
  }
  data[i * 4] = c.r;
  data[i * 4 + 1] = c.g;
  data[i * 4 + 2] = c.b;
  data[i * 4 + 3] = 255;
}

function flood(start: number, from: number, to: number): number[] {
  const queue = [start];
  const members = [start];
  label[start] = to;
  while (queue.length > 0) {
    const i = queue.pop()!;
    const x = i % width;
    for (const n of [
      x > 0 ? i - 1 : -1,
      x < width - 1 ? i + 1 : -1,
      i >= width ? i - width : -1,
      i < size - width ? i + width : -1,
    ]) {
      if (n >= 0 && label[n] === from) {
        label[n] = to;
        queue.push(n);
        members.push(n);
      }
    }
  }
  return members;
}

for (let x = 0; x < width; x++) {
  for (const i of [x, size - width + x]) {
    if (label[i] === 1) flood(i, 1, 2);
  }
}
for (let y = 0; y < height; y++) {
  for (const i of [y * width, y * width + width - 1]) {
    if (label[i] === 1) flood(i, 1, 2);
  }
}

let nextRegion = 3;
for (let i = 0; i < size; i++) {
  if (label[i] !== 1) continue;
  const members = flood(i, 1, nextRegion++);
  let warmth = 0;
  for (const m of members) {
    warmth += orig[m * 4] - orig[m * 4 + 2];
  }
  const c = warmth / members.length >= 5 ? CREAM : WHITE;
  for (const m of members) {
    data[m * 4] = c.r;
    data[m * 4 + 1] = c.g;
    data[m * 4 + 2] = c.b;
    data[m * 4 + 3] = 255;
  }
}
for (let i = 0; i < size; i++) {
  if (label[i] === 2) {
    data[i * 4] = SENTINEL.r;
    data[i * 4 + 1] = SENTINEL.g;
    data[i * 4 + 2] = SENTINEL.b;
    data[i * 4 + 3] = 255;
  }
}

const options = {
  pal: PALETTE,
  colorsampling: 0,
  numberofcolors: PALETTE.length,
  colorquantcycles: 1,
  ltres: Number(ltresArg ?? 1),
  qtres: Number(qtresArg ?? 1),
  pathomit: Number(pathomitArg ?? 8),
  strokewidth: 1,
  roundcoords: 1,
  linefilter: false,
  viewbox: true,
};

let svg: string = ImageTracer.imagedataToSVG({ width, height, data }, options);

svg = svg.replace(/<path[^>]*?\/>/g, (path) => {
  const m = /fill="(rgb\(\d+,\d+,\d+\))"/.exec(path);
  if (!m) return path;
  const hex = HEX[m[1]];
  if (hex === "DROP") return "";
  if (!hex) return path;
  return path
    .replace(`fill="${m[1]}"`, `fill="${hex}"`)
    .replace(`stroke="${m[1]}"`, `stroke="${hex}"`);
});

writeFileSync(output, svg);
console.log(`${output}: ${(svg.length / 1024).toFixed(1)} KB`);
