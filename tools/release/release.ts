#!/usr/bin/env bun
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

export type Mode = "release" | "dry-run" | "rehearse";
export type Options = { mode: Mode; version?: string };
const ROOT = resolve(import.meta.dir, "../..");
const REGISTRY = process.env.STATEDB_REGISTRY ?? "ghcr.io/state-db/statedb";
const CANDIDATES = process.env.STATEDB_CANDIDATE_REGISTRY ?? `${REGISTRY}-candidates`;
const CFN_BUCKET = process.env.STATEDB_CFN_BUCKET;
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const DIGEST = /^sha256:[0-9a-f]{64}$/;
export class ReleaseError extends Error {}
export class UsageError extends Error {}

type CommandOptions = { capture?: boolean; allowFailure?: boolean; input?: string };
// Every external action remains visible at its call site; this only removes spawnSync boilerplate.
function command(program: string, args: string[] = [], options: CommandOptions = {}) {
  const capture = options.capture ?? false;
  const result = spawnSync(program, args, {
    cwd: ROOT,
    encoding: "utf8",
    input: options.input,
    stdio: capture
      ? [options.input === undefined ? "ignore" : "pipe", "pipe", "pipe"]
      : [options.input === undefined ? "inherit" : "pipe", "inherit", "inherit"],
  });
  if (result.error) {
    throw new ReleaseError(`could not run ${program}`, { cause: result.error });
  }
  const status = result.status ?? 1;
  if (status !== 0 && !options.allowFailure) {
    const detail = capture && result.stderr?.trim() ? `: ${result.stderr.trim()}` : "";
    throw new ReleaseError(`${program} ${args.join(" ")} failed${detail}`);
  }
  return { status, stdout: capture ? (result.stdout ?? "").trim() : "" };
}
const capture = (program: string, args: string[] = []) =>
  command(program, args, { capture: true }).stdout;
const step = (message: string) => console.log(`\n\x1b[1m==> ${message}\x1b[0m`);
const note = (message: string) => console.log(`    ${message}`);
const warn = (message: string) => console.log(`\x1b[33m    ${message}\x1b[0m`);

export function parseOptions(args: string[]): Options {
  let mode: Mode = "release";
  let version: string | undefined;
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--version") {
      version = args[++index];
      if (!version || version.startsWith("--")) {
        throw new UsageError("--version needs X.Y.Z");
      }
    } else if (args[index] === "--dry-run" || args[index] === "--rehearse") {
      const next = args[index] === "--rehearse" ? "rehearse" : "dry-run";
      if (mode !== "release" && mode !== next) {
        throw new UsageError("--dry-run and --rehearse are mutually exclusive");
      }
      mode = next;
    } else {
      throw new UsageError(`unknown argument: ${args[index]}`);
    }
  }
  return { mode, version };
}
function versionParts(version: string): [bigint, bigint, bigint] {
  const match = SEMVER.exec(version);
  if (!match) {
    throw new ReleaseError(`version '${version}' is not X.Y.Z`);
  }
  return [BigInt(match[1]), BigInt(match[2]), BigInt(match[3])];
}
export function compareVersions(left: string, right: string): number {
  const leftParts = versionParts(left);
  const rightParts = versionParts(right);
  for (let index = 0; index < 3; index += 1) {
    if (leftParts[index] !== rightParts[index]) {
      return leftParts[index] > rightParts[index] ? 1 : -1;
    }
  }
  return 0;
}
export function rollChangelog(changelog: string, version: string, date: string): string {
  if (!/^## Unreleased$/m.test(changelog)) {
    throw new ReleaseError("CHANGELOG.md is missing '## Unreleased'");
  }
  return changelog.replace(/^## Unreleased$/m, `## Unreleased\n\n## ${version} — ${date}`);
}

export function versionedCloudFormationPutArgs(
  bucket: string,
  version: string,
  artifact: string,
): string[] {
  versionParts(version);
  return [
    "s3api",
    "put-object",
    "--bucket",
    bucket,
    "--key",
    `releases/${version}/cloudformation.yaml`,
    "--body",
    artifact,
    "--content-type",
    "application/yaml",
    "--if-none-match",
    "*",
  ];
}
function changelogNotes(): string {
  const lines = readFileSync(resolve(ROOT, "CHANGELOG.md"), "utf8").split("\n");
  const start = lines.indexOf("## Unreleased");
  if (start < 0) {
    throw new ReleaseError("CHANGELOG.md is missing '## Unreleased'");
  }
  const offset = lines.slice(start + 1).findIndex((line) => line.startsWith("## "));
  return lines
    .slice(start + 1, offset < 0 ? lines.length : start + 1 + offset)
    .join("\n")
    .trim();
}
function imageDigest(reference: string, allowMissing = false): string | undefined {
  const result = command(
    "docker",
    ["buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest.Digest}}"],
    { capture: true, allowFailure: allowMissing },
  );
  if (allowMissing && result.status !== 0) {
    return undefined;
  }
  const digest = result.stdout.replaceAll('"', "");
  if (!DIGEST.test(digest)) {
    throw new ReleaseError(`invalid digest for ${reference}: ${digest}`);
  }
  return digest;
}
function gate(reference: string, commit: string, version: string, previous?: string) {
  const args = [reference, "--expect-commit", commit, "--expect-version", version];
  if (previous) {
    args.push("--previous", previous);
  }
  command("bin/release-gate", args);
}
function ensureSourceUnchanged(commit: string) {
  if (capture("git", ["rev-parse", "HEAD"]) !== commit) {
    throw new ReleaseError("HEAD changed during release verification");
  }
  if (capture("git", ["status", "--porcelain"])) {
    throw new ReleaseError("working tree changed during release verification");
  }
  command("git", ["fetch", "--quiet", "origin", "main"]);
  if (capture("git", ["rev-parse", "origin/main"]) !== commit) {
    throw new ReleaseError("origin/main changed during release verification");
  }
}
function filesIn(directory: string) {
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => resolve(directory, entry.name));
}

export function release(options: Options) {
  const dryRun = options.mode !== "release";
  const rehearse = options.mode === "rehearse";
  let localTagCreated = false;
  let tagPublished = false;
  let completedPhase = "preflight";
  let releaseArtifacts: string | undefined;
  let version = options.version ?? "unknown";
  let tag = "";

  // Rehearsal relaxes environmental preconditions. Release invariants still fail hard.
  const require = (condition: boolean, message: string) => {
    if (condition) {
      return true;
    }
    if (rehearse) {
      warn(`[rehearsal] would have failed: ${message}`);
      return false;
    }
    throw new ReleaseError(message);
  };

  try {
    step("Preflight");
    for (const tool of ["docker", "git", "gh", "cosign"]) {
      if (!Bun.which(tool)) {
        throw new ReleaseError(`${tool} is not on PATH`);
      }
    }
    if (!dryRun) {
      if (!Bun.which("aws")) {
        throw new ReleaseError("aws is not on PATH");
      }
      if (!CFN_BUCKET) {
        throw new ReleaseError("STATEDB_CFN_BUCKET is required for CloudFormation publishing");
      }
    }
    command("docker", ["buildx", "version"], { capture: true });
    require(capture("git", ["rev-parse", "--abbrev-ref", "HEAD"]) === "main", "not on main");
    require(!capture("git", ["status", "--porcelain"]), "working tree is dirty");
    const fetchResult = command("git", ["fetch", "--quiet", "origin", "main"], {
      allowFailure: rehearse,
    });
    const fetched = fetchResult.status === 0;
    require(fetched, "could not fetch origin/main");
    if (fetched) {
      require(
        capture("git", ["rev-parse", "HEAD"]) === capture("git", ["rev-parse", "origin/main"]),
        "HEAD is not origin/main",
      );
    }

    version = options.version ?? readFileSync(resolve(ROOT, ".version"), "utf8").trim();
    versionParts(version);
    tag = `v${version}`;
    const localTag = command("git", ["rev-parse", "-q", "--verify", `refs/tags/${tag}`], {
      capture: true,
      allowFailure: true,
    });
    if (localTag.status === 0) {
      throw new ReleaseError(`${tag} already exists locally`);
    }
    const remoteTags = command("git", ["ls-remote", "--tags", "--refs", "origin", "v*"], {
      capture: true,
      allowFailure: rehearse,
    });
    require(remoteTags.status === 0, "could not list release tags on origin");
    const remoteVersions = remoteTags.stdout.split("\n")
      .map((line) => line.match(/refs\/tags\/v(.+)$/)?.[1])
      .filter((candidate): candidate is string => Boolean(candidate && SEMVER.test(candidate)));
    if (remoteVersions.includes(version)) {
      throw new ReleaseError(`${tag} already exists on origin`);
    }
    const latestVersion = remoteVersions.sort(compareVersions).at(-1);
    if (latestVersion && compareVersions(version, latestVersion) <= 0) {
      throw new ReleaseError(
        `version ${version} must be newer than the latest release, ${latestVersion}`,
      );
    }

    const commitFull = capture("git", ["rev-parse", "HEAD"]);
    const commit = commitFull.slice(0, 7);
    const minor = version.slice(0, version.lastIndexOf("."));
    const notes = changelogNotes();
    if (!notes) {
      throw new ReleaseError("CHANGELOG.md has an empty '## Unreleased' section");
    }
    note(`releasing ${commit} as ${version}`);

    step(`Gate: what verification has already proved about ${commit}`);
    const statusResult = command("gh", ["api", `repos/:owner/:repo/commits/${commitFull}/status`], {
      capture: true,
      allowFailure: true,
    });
    const statuses = statusResult.status === 0 ? JSON.parse(statusResult.stdout).statuses ?? [] : [];
    const signedOff = statuses.some(
      (status: { context: string; state: string }) =>
        status.context === "signoff/ci" && status.state === "success",
    );
    require(signedOff, `local CI has not signed off on ${commit}`);
    const runsResult = command(
      "gh",
      [
        "run",
        "list",
        "--workflow",
        "native.yml",
        "--commit",
        commitFull,
        "--limit",
        "1",
        "--json",
        "databaseId,conclusion",
      ],
      { capture: true, allowFailure: true },
    );
    const nativeRun =
      runsResult.status === 0 && runsResult.stdout ? JSON.parse(runsResult.stdout)[0] : undefined;
    const hasEvidence = require(
      nativeRun?.conclusion === "success",
      `native.yml has not succeeded on ${commit}`,
    );
    if (hasEvidence) {
      note("release build green: Pulumi compatibility plus native amd64 and arm64");
    }

    let evidence: { amd64: string; arm64: string; javaSbom: string; assets: string[] } | undefined;
    if (hasEvidence) {
      const evidenceRoot = resolve(ROOT, "artifacts/native-release-evidence");
      const javaRoot = resolve(ROOT, "artifacts/ci-java-sbom");
      rmSync(evidenceRoot, { recursive: true, force: true });
      rmSync(javaRoot, { recursive: true, force: true });
      command("gh", [
        "run",
        "download",
        String(nativeRun.databaseId),
        "-n",
        "native-build-amd64",
        "-D",
        resolve(evidenceRoot, "amd64"),
      ]);
      command("gh", [
        "run",
        "download",
        String(nativeRun.databaseId),
        "-n",
        "native-build-arm64",
        "-D",
        resolve(evidenceRoot, "arm64"),
      ]);
      command("gh", [
        "run",
        "download",
        String(nativeRun.databaseId),
        "-n",
        "java-sbom",
        "-D",
        javaRoot,
      ]);
      const readDigest = (arch: "amd64" | "arm64") =>
        readFileSync(resolve(evidenceRoot, arch, `native-candidate-${arch}.digest`), "utf8").trim();
      const javaSbom = resolve(javaRoot, "state-db-java.cdx.json");
      if (!existsSync(javaSbom)) {
        throw new ReleaseError("native.yml did not produce the scanned Java SBOM");
      }
      evidence = {
        amd64: readDigest("amd64"),
        arm64: readDigest("arm64"),
        javaSbom,
        assets: [
          ...filesIn(resolve(evidenceRoot, "amd64")),
          ...filesIn(resolve(evidenceRoot, "arm64")),
        ],
      };
    }

    step("Tagging locally");
    command("git", ["tag", "-a", tag, "-m", version]);
    localTagCreated = true;
    const builtVersion = capture("bin/build-version");
    if (builtVersion !== version && !(rehearse && builtVersion === `${version}+dirty`)) {
      throw new ReleaseError(`build identity resolved to '${builtVersion}', expected '${version}'`);
    }
    note(`build identity: ${builtVersion}`);

    step("Building and gating the local native candidate");
    const localCandidate = `state-db:${version}`;
    command("bin/build-native-app-image", [localCandidate, "--version", version]);
    const previous = latestVersion ? `${REGISTRY}:${latestVersion}` : undefined;
    if (latestVersion) {
      note(`upgrading from v${latestVersion}`);
    }
    gate(localCandidate, commit, version, previous);

    step("Checking the immutable CI candidates");
    const candidateReferences: string[] = [];
    if (!evidence) {
      warn("[rehearsal] final manifest inspection skipped without successful native.yml evidence");
    } else {
      for (const arch of ["amd64", "arm64"] as const) {
        const digest = evidence[arch];
        if (!DIGEST.test(digest)) {
          throw new ReleaseError(`invalid ${arch} candidate digest: ${digest}`);
        }
        const reference = `${CANDIDATES}@${digest}`;
        command("docker", ["buildx", "imagetools", "inspect", reference], { capture: true });
        candidateReferences.push(reference);
      }
    }
    if (dryRun) {
      note(
        "[dry-run] would assemble and gate the manifest, then publish the tag, image, " +
          "signature, aliases, GitHub Release, and version bump",
      );
      return { version, published: false };
    }
    if (!evidence) {
      throw new ReleaseError("native.yml evidence is required to publish");
    }

    step("Assembling and gating the multi-architecture manifest");
    ensureSourceUnchanged(commitFull);
    const candidateManifest = `${CANDIDATES}:${commitFull}`;
    command("docker", [
      "buildx",
      "imagetools",
      "create",
      "-t",
      candidateManifest,
      ...candidateReferences,
    ]);
    command("docker", ["pull", candidateManifest]);
    gate(candidateManifest, commit, version, previous);

    step("Publishing the release");
    ensureSourceUnchanged(commitFull);
    const digest = imageDigest(candidateManifest)!;
    const versionReference = `${REGISTRY}:${version}`;
    const existingDigest = imageDigest(versionReference, true);
    if (existingDigest && existingDigest !== digest) {
      throw new ReleaseError(
        `${versionReference} already points at a different digest, ${existingDigest}`,
      );
    }
    note(`digest ${digest}`);
    releaseArtifacts = mkdtempSync(resolve(tmpdir(), "statedb-release-artifacts-"));
    const cloudFormationArtifact = resolve(
      releaseArtifacts,
      `state-db-${version}-cloudformation.yaml`,
    );
    command("bin/package-aws-installer", [version, digest, cloudFormationArtifact]);
    // Atomic means main advancing also prevents the release tag from appearing remotely.
    command("git", ["push", "--atomic", "origin", tag, `${commitFull}:refs/heads/main`]);
    tagPublished = true;
    completedPhase = "Git tag publication";

    if (!existingDigest) {
      command("docker", [
        "buildx",
        "imagetools",
        "create",
        "-t",
        versionReference,
        candidateManifest,
      ]);
    }
    completedPhase = "immutable image publication";

    command("cosign", ["sign", "--yes", `${REGISTRY}@${digest}`]);
    completedPhase = "image signing";

    command("aws", versionedCloudFormationPutArgs(CFN_BUCKET!, version, cloudFormationArtifact));
    completedPhase = "versioned CloudFormation publication";

    command("docker", [
      "buildx",
      "imagetools",
      "create",
      "-t",
      `${REGISTRY}:${minor}`,
      "-t",
      `${REGISTRY}:latest`,
      `${REGISTRY}@${digest}`,
    ]);
    completedPhase = "floating tag publication";

    command(
      "gh",
      [
        "release",
        "create",
        tag,
        "--title",
        version,
        "--notes-file",
        "-",
        "--verify-tag",
        evidence.javaSbom,
        cloudFormationArtifact,
        ...evidence.assets,
      ],
      { input: `${notes}\n` },
    );
    completedPhase = "GitHub Release publication";

    command("aws", [
      "s3",
      "cp",
      `s3://${CFN_BUCKET}/releases/${version}/cloudformation.yaml`,
      `s3://${CFN_BUCKET}/releases/latest/cloudformation.yaml`,
    ]);
    completedPhase = "latest CloudFormation promotion";

    step("Rolling the changelog and .version");
    const [major, minorNumber] = minor.split(".").map(BigInt);
    const nextVersion = `${major}.${minorNumber + 1n}.0`;
    const changelogPath = resolve(ROOT, "CHANGELOG.md");
    const temporaryPath = `${changelogPath}.tmp`;
    const changelog = readFileSync(changelogPath, "utf8");
    const today = new Date().toISOString().slice(0, 10);
    writeFileSync(temporaryPath, rollChangelog(changelog, version, today));
    renameSync(temporaryPath, changelogPath);
    writeFileSync(resolve(ROOT, ".version"), `${nextVersion}\n`);
    command("git", ["add", "CHANGELOG.md", ".version"]);
    command("git", ["commit", "-m", `Release ${version}`]);
    command("git", ["push", "origin", "main"]);
    completedPhase = "repository rollover";
    return { version, nextVersion, digest, published: true };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    if (tagPublished) {
      throw new ReleaseError(
        `release ${version} is partially published after ${completedPhase}: ${detail}. ` +
          "Inspect the matching Git tag and image digest, then reconcile the remaining steps manually.",
        { cause: error },
      );
    }
    if (error instanceof ReleaseError || error instanceof UsageError) {
      throw error;
    }
    throw new ReleaseError(detail, { cause: error });
  } finally {
    if (releaseArtifacts) {
      rmSync(releaseArtifacts, { recursive: true, force: true });
    }
    if (localTagCreated && !tagPublished) {
      command("git", ["tag", "-d", tag], { capture: true, allowFailure: true });
    }
  }
}

if (import.meta.main) {
  const usage = "usage: release [--version X.Y.Z] [--dry-run | --rehearse]";
  try {
    const options = parseOptions(process.argv.slice(2));
    const result = release(options);
    if (result.published) {
      console.log(`\n\x1b[32mreleased ${result.version}\x1b[0m`);
      note(`next release will be ${result.nextVersion}`);
    } else {
      const label = options.mode === "rehearse" ? "rehearsal" : "dry run";
      console.log(`\n\x1b[33m${label} complete for ${result.version}; nothing was published\x1b[0m`);
    }
  } catch (error) {
    if (error instanceof UsageError) {
      console.error(`${usage}\n${error.message}`);
    } else {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`\x1b[31mrelease aborted: ${message}\x1b[0m`);
    }
    process.exit(error instanceof UsageError ? 2 : 1);
  }
}
