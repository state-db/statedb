/**
 * Production build script.
 * Uses Bun.build() JS API so we can pass the Tailwind plugin —
 * the `bun build` CLI doesn't support plugins yet. Output in dist/ is copied
 * into the Quarkus app's META-INF/resources by the Maven build (see app/pom.xml).
 */
import { readdir, rm } from "node:fs/promises";
import tailwind from "bun-plugin-tailwind";

// Bun.build does not clear outdir, so wipe it first — otherwise obsolete hashed
// chunks from a prior build linger and get copied into the release artifact.
await rm("./dist", { recursive: true, force: true });

// Bun defaults NODE_ENV to development. Set it both for Bun's JSX transform and
// for React's bundled production branches.
process.env.NODE_ENV = "production";

const result = await Bun.build({
  entrypoints: ["./index.html"],
  outdir: "./dist",
  publicPath: "/",
  minify: true,
  define: { "process.env.NODE_ENV": JSON.stringify("production") },
  plugins: [tailwind],
});

if (!result.success) {
  console.error("Build failed:");
  for (const log of result.logs) {
    console.error(log);
  }
  process.exit(1);
}

// Dev React renders successfully, so guard the emitted bundle directly.
const DEV_BUILD_MARKERS = ["jsxDEV", "react-jsx-dev-runtime"];
const emitted = await readdir("./dist");
for (const file of emitted.filter((name) => name.endsWith(".js"))) {
  const source = await Bun.file(`./dist/${file}`).text();
  const found = DEV_BUILD_MARKERS.filter((marker) => source.includes(marker));
  if (found.length > 0) {
    console.error(
      `Build failed: dist/${file} carries development React markers (${found.join(", ")}). ` +
        "The bundle must be built with NODE_ENV=production.",
    );
    process.exit(1);
  }
}

console.log("Build complete → dist/");
