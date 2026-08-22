/**
 * Emits license attributions for the console's production dependency tree.
 *
 * Walks package.json dependencies (plus resolvable optional and peer
 * dependencies) through node_modules, and prints one plain-text section per
 * name@version with its declared license id and the verbatim text of any
 * LICENSE/LICENCE/COPYING/NOTICE files in the package. Output is sorted and
 * timestamp-free so `./dev check` can compare it with the committed
 * THIRD-PARTY-NOTICES.txt. Regenerate via `./dev notices`.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const webRoot = resolve(import.meta.dir, "..");

type PackageJson = {
  name?: string;
  version?: string;
  license?: string | { type?: string };
  licenses?: Array<{ type?: string }>;
  homepage?: string;
  dependencies?: Record<string, string>;
  optionalDependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
};

function readPackageJson(dir: string): PackageJson | null {
  const path = join(dir, "package.json");
  if (!existsSync(path)) return null;
  return JSON.parse(readFileSync(path, "utf8")) as PackageJson;
}

/** Node-style resolution of a package directory, without touching `exports`. */
function findPackageDir(name: string, fromDir: string): string | null {
  let dir = fromDir;
  while (true) {
    const candidate = join(dir, "node_modules", name);
    if (existsSync(join(candidate, "package.json"))) return candidate;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

function licenseId(pkg: PackageJson): string {
  if (typeof pkg.license === "string") return pkg.license;
  if (pkg.license?.type) return pkg.license.type;
  if (pkg.licenses?.length) {
    return pkg.licenses.map((entry) => entry.type ?? "unknown").join(" OR ");
  }
  return "unknown — see package contents";
}

function licenseFiles(dir: string): string[] {
  return readdirSync(dir)
    .filter((name) => /^(license|licence|copying|notice)/i.test(name))
    .sort();
}

type Entry = {
  name: string;
  version: string;
  license: string;
  homepage?: string;
  texts: string[];
};

const rootPkg = readPackageJson(webRoot);
if (!rootPkg) {
  console.error(`no package.json under ${webRoot}`);
  process.exit(1);
}

const entries = new Map<string, Entry>();
const queue: Array<{ name: string; fromDir: string }> = Object.keys(
  rootPkg.dependencies ?? {},
).map((name) => ({ name, fromDir: webRoot }));
const missing: string[] = [];

while (queue.length > 0) {
  const { name, fromDir } = queue.shift()!;
  const dir = findPackageDir(name, fromDir);
  if (!dir) {
    missing.push(name);
    continue;
  }
  const pkg = readPackageJson(dir)!;
  const key = `${name}@${pkg.version ?? "unknown"}`;
  if (entries.has(key)) continue;
  entries.set(key, {
    name,
    version: pkg.version ?? "unknown",
    license: licenseId(pkg),
    homepage: pkg.homepage,
    texts: licenseFiles(dir).map((file) =>
      readFileSync(join(dir, file), "utf8").trim(),
    ),
  });
  for (const dep of [
    ...Object.keys(pkg.dependencies ?? {}),
    ...Object.keys(pkg.optionalDependencies ?? {}),
    ...Object.keys(pkg.peerDependencies ?? {}),
  ]) {
    queue.push({ name: dep, fromDir: dir });
  }
}

// Peer/optional dependencies may legitimately be absent; direct dependencies
// may not. Fail loudly rather than silently under-attributing.
const direct = new Set(Object.keys(rootPkg.dependencies ?? {}));
const missingDirect = missing.filter((name) => direct.has(name));
if (missingDirect.length > 0) {
  console.error(
    `unresolvable direct dependencies (run bun install?): ${missingDirect.join(", ")}`,
  );
  process.exit(1);
}

const sorted = [...entries.values()].sort(
  (a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version),
);

const rule = "-".repeat(72);
const sections = sorted.map((entry) => {
  const lines = [rule, `${entry.name}@${entry.version}`, `License: ${entry.license}`];
  if (entry.homepage) lines.push(`Homepage: ${entry.homepage}`);
  if (entry.texts.length > 0) {
    lines.push("", entry.texts.join("\n\n"));
  } else {
    lines.push("", "(no license file shipped in the package; see the license id above)");
  }
  return lines.join("\n");
});

console.log(`${sorted.length} packages.\n`);
console.log(sections.join("\n\n"));
