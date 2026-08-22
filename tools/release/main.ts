#!/usr/bin/env bun
import { parseOptions, RecoveryRequired, ReleaseError, runRelease, UsageError } from "./release";
import { systemEnvironment } from "./system";

const USAGE = "usage: release [--version X.Y.Z] [--dry-run | --rehearse]";

try {
  const options = parseOptions(process.argv.slice(2));
  const result = runRelease(options, systemEnvironment());
  if (result.published) {
    console.log(`\n\x1b[32mreleased ${result.version}\x1b[0m`);
    console.log(`    next release will be ${result.nextVersion}`);
  } else if (options.mode === "rehearse") {
    console.log(`\n\x1b[33mrehearsal complete for ${result.version}; nothing was published\x1b[0m`);
  } else {
    console.log(`\n\x1b[33mdry run complete for ${result.version}; nothing was published\x1b[0m`);
  }
} catch (error) {
  if (error instanceof UsageError) {
    console.error(`${USAGE}\n${error.message}`);
    process.exit(2);
  }
  const message = error instanceof Error ? error.message : String(error);
  const prefix = error instanceof RecoveryRequired ? "release requires recovery" : "release aborted";
  console.error(`\x1b[31m${prefix}: ${message}\x1b[0m`);
  if (!(error instanceof ReleaseError)) console.error(error);
  process.exit(1);
}
