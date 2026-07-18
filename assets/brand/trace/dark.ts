/* Derive the dark-background variant of a traced SVG by recoloring paths
 * based on their bounding boxes:
 * - navy paths fully inside the face box keep navy (eyes/nose/mouth on cream);
 *   other navy paths (outline, ears, whiskers, wordmark) go light
 * - in the lockup, wordmark navy goes near-white and the lower tagline goes
 *   muted slate
 * - blue brightens slightly
 */
import { readFileSync, writeFileSync } from "node:fs";

const [, , mode, input, output] = process.argv;
const debug = mode === "debug";

type Box = { minX: number; minY: number; maxX: number; maxY: number };

function bbox(d: string): Box {
  const nums = d.match(/-?\d+(\.\d+)?/g)!.map(Number);
  const box = { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity };
  for (let i = 0; i + 1 < nums.length; i += 2) {
    box.minX = Math.min(box.minX, nums[i]);
    box.maxX = Math.max(box.maxX, nums[i]);
    box.minY = Math.min(box.minY, nums[i + 1]);
    box.maxY = Math.max(box.maxY, nums[i + 1]);
  }
  return box;
}

const inside = (b: Box, x0: number, y0: number, x1: number, y1: number) =>
  b.minX >= x0 && b.maxX <= x1 && b.minY >= y0 && b.maxY <= y1;

let svg = readFileSync(input, "utf8");

svg = svg.replace(
  /<path([^>]*?)d="([^"]+)"([^>]*?)\/>/g,
  (whole, pre, d, post) => {
    const attrs = pre + post;
    const fillMatch = /fill="(#[0-9A-Fa-f]{6})"/.exec(attrs);
    if (!fillMatch) return whole;
    const fill = fillMatch[1];
    const b = bbox(d);
    if (debug) {
      console.log(
        `${fill} x ${b.minX.toFixed(0)}-${b.maxX.toFixed(0)} y ${b.minY.toFixed(0)}-${b.maxY.toFixed(0)} dlen ${d.length}`,
      );
      return whole;
    }
    let next = fill;
    if (fill === "#FFFFFF" && mode === "full" && b.minX >= 385) {
      // wordmark/tagline counters: drop the white overlay so the page
      // background shows through the letter holes
      return "";
    }
    if (fill === "#2563EB") {
      next = "#3B82F6";
    } else if (fill === "#182B4D") {
      if (mode === "mark") {
        next = inside(b, 95, 85, 290, 345) ? "#182B4D" : "#DBE4F2";
      } else {
        // lockup: otter on the left, wordmark + tagline on the right
        if (b.minX >= 385) {
          next = b.minY >= 270 ? "#B7C3D8" : "#F2F5FA";
        } else {
          next = inside(b, 105, 95, 300, 350) ? "#182B4D" : "#DBE4F2";
        }
      }
    }
    return next === fill
      ? whole
      : whole.replaceAll(`"${fill}"`, `"${next}"`);
  },
);

if (!debug) {
  writeFileSync(output, svg);
  console.log(`${output}: ${(svg.length / 1024).toFixed(1)} KB`);
}
