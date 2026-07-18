/* Emit the final brand deliverables from the traced SVGs:
 * - assets/brand/*.svg (light + dark marks and lockups)
 * - modules/web/favicon.svg (prefers-color-scheme aware)
 * - modules/web/src/components/brand/BrandMark.tsx (theme-aware inline mark)
 */
import { readFileSync, writeFileSync } from "node:fs";

const NAVY = "#182B4D";
const BLUE = "#2563EB";

type Path = { fill: string; d: string };
type Box = { minX: number; minY: number; maxX: number; maxY: number };

function parse(file: string): { viewBox: string; paths: Path[] } {
  const svg = readFileSync(file, "utf8");
  const viewBox = /viewBox="([^"]+)"/.exec(svg)![1];
  const paths: Path[] = [];
  for (const m of svg.matchAll(/<path fill="(#[0-9A-Fa-f]{6})"[^>]*? d="([^"]+)"[^>]*?\/>/g)) {
    paths.push({ fill: m[1], d: m[2].trim() });
  }
  return { viewBox, paths };
}

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

// Navy paths fully inside the face box are facial features (stay navy in dark
// mode); every other navy path is line work that follows the theme.
function isFeature(p: Path, faceBox: [number, number, number, number]) {
  if (p.fill !== NAVY) return false;
  const b = bbox(p.d);
  const [x0, y0, x1, y1] = faceBox;
  return b.minX >= x0 && b.maxX <= x1 && b.minY >= y0 && b.maxY <= y1;
}

function plainSvg(file: string, label: string) {
  const { viewBox, paths } = parse(file);
  const body = paths
    .map((p) => `  <path fill="${p.fill}" stroke="${p.fill}" stroke-width="1" d="${p.d}"/>`)
    .join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" role="img" aria-label="${label}">\n${body}\n</svg>\n`;
}

const MARK_FACE_BOX: [number, number, number, number] = [95, 85, 290, 345];

writeFileSync("../statedb-mark.svg", plainSvg("traced-mark.svg", "state-db otter mark"));
writeFileSync("../statedb-mark-dark.svg", plainSvg("traced-mark-dark.svg", "state-db otter mark"));
writeFileSync("../statedb-logo.svg", plainSvg("traced-full.svg", "statedb — self-hosted state backend"));
writeFileSync("../statedb-logo-dark.svg", plainSvg("traced-full-dark.svg", "statedb — self-hosted state backend"));

// Favicon: one file, both themes, via CSS classes on the theme-following paths.
{
  const { viewBox, paths } = parse("traced-mark.svg");
  const body = paths
    .map((p) => {
      if (p.fill === BLUE) return `  <path class="wave" d="${p.d}"/>`;
      if (p.fill === NAVY && !isFeature(p, MARK_FACE_BOX)) return `  <path class="line" d="${p.d}"/>`;
      return `  <path fill="${p.fill}" stroke="${p.fill}" stroke-width="1" d="${p.d}"/>`;
    })
    .join("\n");
  const style = [
    "    .line { fill: #182B4D; stroke: #182B4D; stroke-width: 1; }",
    "    .wave { fill: #2563EB; stroke: #2563EB; stroke-width: 1; }",
    "    @media (prefers-color-scheme: dark) {",
    "      .line { fill: #DBE4F2; stroke: #DBE4F2; }",
    "      .wave { fill: #3B82F6; stroke: #3B82F6; }",
    "    }",
  ].join("\n");
  writeFileSync(
    "../../../modules/web/favicon.svg",
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}">\n  <style>\n${style}\n  </style>\n${body}\n</svg>\n`,
  );
}

// BrandMark component: line work follows currentColor, waves follow a
// dark-variant Tailwind class, features and face keep fixed fills.
{
  const { viewBox, paths } = parse("traced-mark.svg");
  const body = paths
    .map((p) => {
      if (p.fill === BLUE) {
        return `      <path className="fill-[#2563EB] stroke-[#2563EB] dark:fill-[#3B82F6] dark:stroke-[#3B82F6]" strokeWidth="1" d="${p.d}" />`;
      }
      if (p.fill === NAVY && !isFeature(p, MARK_FACE_BOX)) {
        return `      <path fill="currentColor" stroke="currentColor" strokeWidth="1" d="${p.d}" />`;
      }
      return `      <path fill="${p.fill}" stroke="${p.fill}" strokeWidth="1" d="${p.d}" />`;
    })
    .join("\n");
  const tsx = `import { cn } from "@/lib/utils";

/**
 * The state-db otter mark, auto-traced from the original illustration
 * (assets/brand/statedb-logo.png — see assets/brand/README.md). Line work
 * follows \`currentColor\` (navy in light mode, pale slate in dark) while the
 * face, its features, and the waves keep brand colors. Generated from
 * assets/brand/statedb-mark.svg — regenerate rather than editing paths by hand.
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="${viewBox}"
      aria-hidden="true"
      className={cn("text-[#182B4D] dark:text-[#DBE4F2]", className)}
    >
${body}
    </svg>
  );
}
`;
  writeFileSync("../../../modules/web/src/components/brand/BrandMark.tsx", tsx);
}

console.log("finalized");
