/**
 * Production build script.
 * Uses Bun.build() JS API so we can pass the Tailwind plugin —
 * the `bun build` CLI doesn't support plugins yet. Output in dist/ is copied
 * into the Quarkus app's META-INF/resources by the Maven build (see app/pom.xml).
 */
import { rm } from "node:fs/promises";
import tailwind from "bun-plugin-tailwind";

// Bun.build does not clear outdir, so wipe it first — otherwise obsolete hashed
// chunks from a prior build linger and get copied into the release artifact.
await rm("./dist", { recursive: true, force: true });

const result = await Bun.build({
  entrypoints: ["./index.html"],
  outdir: "./dist",
  publicPath: "/",
  minify: true,
  plugins: [tailwind],
});

if (!result.success) {
  console.error("Build failed:");
  for (const log of result.logs) {
    console.error(log);
  }
  process.exit(1);
}

console.log("Build complete → dist/");
