/**
 * Regenerates the landing page's test wall from the compatibility suite.
 *
 *   ./bin/in-container bash -lc 'cd /workspaces/state-db/site && bun run generate:test-wall'
 *
 * Every chip on the wall is a real name with a real source path, so the wall
 * can only ever show what the suite actually contains. Re-run after adding or
 * renaming a recorded session, test class, or scenario method.
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const out = join(repoRoot, "site/src/data/test-wall.json");

const contractTest = "modules/app/src/test/java/app/statedb/app/contract/ContractReplayTest.java";
const smokeDir = "modules/pulumi-cli-smoke/src/test/java/app/statedb/pulumiclismoke";
const faultDir = `${smokeDir}/faults`;

const read = (path) => readFileSync(join(repoRoot, path), "utf8");

const javaFiles = (dir, suffix) =>
  readdirSync(join(repoRoot, dir))
    .filter((f) => f.endsWith(`${suffix}.java`))
    .sort()
    .map((f) => `${dir}/${f}`);

const className = (path) => path.split("/").pop().replace(".java", "");

/** Test methods are `@Test` (or `@ParameterizedTest`) followed by the declaration. */
const testMethods = (path) => {
  const names = [];
  let annotated = false;
  for (const line of read(path).split("\n")) {
    if (/^\s*@(Parameterized)?Test\b/.test(line)) annotated = true;
    else if (annotated) {
      const method = line.match(/\bvoid\s+(\w+)\s*\(/);
      if (method) {
        names.push(method[1]);
        annotated = false;
      }
    }
  }
  return names;
};

// Recorded sessions are the fixture directory names listed in the replay enum.
const sessions = [...new Set(read(contractTest).match(/"\d{2}[a-z]?-[a-z0-9-]+"/g) ?? [])].map(
  (quoted) => ({ label: quoted.slice(1, -1), path: contractTest }),
);

const classPaths = [contractTest, ...javaFiles(smokeDir, "SmokeIT"), ...javaFiles(faultDir, "FaultIT")];
const classes = classPaths.map((path) => ({ label: className(path), path }));

// Faults lead: they are the scenarios a state backend is judged on.
const scenarioPaths = [...javaFiles(faultDir, "FaultIT"), ...javaFiles(smokeDir, "SmokeIT")];
const scenarios = scenarioPaths.flatMap((path) =>
  testMethods(path).map((label) => ({ label, path })),
);

for (const [name, rows] of Object.entries({ sessions, classes, scenarios })) {
  if (rows.length === 0) throw new Error(`test wall: found no ${name} — check the suite paths`);
}

writeFileSync(out, `${JSON.stringify({ sessions, classes, scenarios }, null, 2)}\n`);
console.log(
  `${relative(repoRoot, out)}: ${sessions.length} sessions, ${classes.length} classes, ${scenarios.length} scenarios`,
);
