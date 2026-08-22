import { spawnSync } from "node:child_process";
import {
  existsSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { resolve } from "node:path";
import type {
  GitHubPort,
  ImagePort,
  NativeEvidence,
  NativeRun,
  ReleaseEnvironment,
  ReleaseOutput,
  RepositoryPort,
} from "./release";
import { ReleaseError, rollChangelog } from "./release";

interface RunOptions {
  allowFailure?: boolean;
  capture?: boolean;
  input?: string;
}

interface CommandResult {
  status: number;
  stdout: string;
}

class CommandRunner {
  constructor(private readonly root: string) {}

  run(command: string, args: string[], options: RunOptions = {}): CommandResult {
    const capture = options.capture ?? false;
    const result = spawnSync(command, args, {
      cwd: this.root,
      encoding: "utf8",
      input: options.input,
      stdio: capture
        ? [options.input === undefined ? "ignore" : "pipe", "pipe", "pipe"]
        : [options.input === undefined ? "inherit" : "pipe", "inherit", "inherit"],
    });
    if (result.error) throw new ReleaseError(`could not run ${formatCommand(command, args)}`, { cause: result.error });
    const status = result.status ?? 1;
    if (status !== 0 && !options.allowFailure) {
      const detail = capture ? `: ${(result.stderr ?? "").trim()}` : "";
      throw new ReleaseError(`${formatCommand(command, args)} failed${detail}`);
    }
    return { status, stdout: capture ? (result.stdout ?? "").trim() : "" };
  }
}

class SystemRepository implements RepositoryPort {
  constructor(
    private readonly root: string,
    private readonly commands: CommandRunner,
  ) {}

  branch(): string {
    return this.git(["rev-parse", "--abbrev-ref", "HEAD"]);
  }

  buildVersion(): string {
    return this.commands.run("bin/build-version", [], { capture: true }).stdout;
  }

  changelogNotes(): string {
    const changelog = readFileSync(resolve(this.root, "CHANGELOG.md"), "utf8");
    const lines = changelog.split("\n");
    const start = lines.findIndex((line) => line === "## Unreleased");
    if (start < 0) throw new ReleaseError("CHANGELOG.md is missing '## Unreleased'");
    const endOffset = lines.slice(start + 1).findIndex((line) => line.startsWith("## "));
    const end = endOffset < 0 ? lines.length : start + 1 + endOffset;
    return lines.slice(start + 1, end).join("\n").trim();
  }

  createAnnotatedTag(tag: string, version: string): void {
    this.commands.run("git", ["tag", "-a", tag, "-m", version]);
  }

  deleteLocalTag(tag: string): void {
    this.commands.run("git", ["tag", "-d", tag], { allowFailure: true, capture: true });
  }

  fetchMain(): void {
    this.commands.run("git", ["fetch", "--quiet", "origin", "main"]);
  }

  head(): string {
    return this.git(["rev-parse", "HEAD"]);
  }

  isClean(): boolean {
    return this.git(["status", "--porcelain"]) === "";
  }

  localTagExists(tag: string): boolean {
    return this.commands.run("git", ["rev-parse", "-q", "--verify", `refs/tags/${tag}`], {
      allowFailure: true,
      capture: true,
    }).status === 0;
  }

  nextPlannedVersion(): string {
    return readFileSync(resolve(this.root, ".version"), "utf8");
  }

  originMain(): string {
    return this.git(["rev-parse", "origin/main"]);
  }

  pushTagAtomically(tag: string, expectedMain: string): void {
    this.commands.run("git", ["push", "--atomic", "origin", tag, `${expectedMain}:refs/heads/main`]);
  }

  remoteReleaseVersions(): string[] {
    const output = this.git(["ls-remote", "--tags", "--refs", "origin", "v*"]);
    return output
      .split("\n")
      .filter(Boolean)
      .map((line) => line.split(/\s+/)[1])
      .filter((reference): reference is string => Boolean(reference))
      .map((reference) => reference.replace(/^refs\/tags\/v/, ""));
  }

  rollForward(version: string, nextVersion: string, date: string): void {
    const changelogPath = resolve(this.root, "CHANGELOG.md");
    const temporaryPath = `${changelogPath}.tmp`;
    const changelog = readFileSync(changelogPath, "utf8");
    writeFileSync(temporaryPath, rollChangelog(changelog, version, date), "utf8");
    renameSync(temporaryPath, changelogPath);
    writeFileSync(resolve(this.root, ".version"), `${nextVersion}\n`, "utf8");
    this.commands.run("git", ["add", "CHANGELOG.md", ".version"]);
    this.commands.run("git", ["commit", "-m", `Release ${version}`]);
    this.commands.run("git", ["push", "origin", "main"]);
  }

  private git(args: string[]): string {
    return this.commands.run("git", args, { capture: true }).stdout;
  }
}

class SystemGitHub implements GitHubPort {
  private readonly evidenceDirectory: string;
  private readonly javaSbomDirectory: string;

  constructor(
    root: string,
    private readonly commands: CommandRunner,
  ) {
    this.evidenceDirectory = resolve(root, "artifacts/native-release-evidence");
    this.javaSbomDirectory = resolve(root, "artifacts/ci-java-sbom");
  }

  createRelease(tag: string, version: string, notes: string, assets: string[]): void {
    this.commands.run(
      "gh",
      ["release", "create", tag, "--title", version, "--notes-file", "-", "--verify-tag", ...assets],
      { input: `${notes}\n` },
    );
  }

  downloadEvidence(runId: string): NativeEvidence {
    rmSync(this.evidenceDirectory, { force: true, recursive: true });
    rmSync(this.javaSbomDirectory, { force: true, recursive: true });
    const amd64Directory = resolve(this.evidenceDirectory, "amd64");
    const arm64Directory = resolve(this.evidenceDirectory, "arm64");
    this.download(runId, "native-build-amd64", amd64Directory);
    this.download(runId, "native-build-arm64", arm64Directory);
    this.download(runId, "java-sbom", this.javaSbomDirectory);

    const javaSbom = resolve(this.javaSbomDirectory, "state-db-java.cdx.json");
    if (!existsSync(javaSbom)) throw new ReleaseError("native.yml did not produce the scanned Java SBOM");
    return {
      assets: [...filesIn(amd64Directory), ...filesIn(arm64Directory)],
      candidateDigests: {
        amd64: readDigest(resolve(amd64Directory, "native-candidate-amd64.digest")),
        arm64: readDigest(resolve(arm64Directory, "native-candidate-arm64.digest")),
      },
      javaSbom,
    };
  }

  findNativeRun(commit: string): NativeRun | undefined {
    const result = this.commands.run(
      "gh",
      ["run", "list", "--workflow", "native.yml", "--commit", commit, "--limit", "1", "--json", "databaseId,conclusion"],
      { allowFailure: true, capture: true },
    );
    if (result.status !== 0 || !result.stdout) return undefined;
    const [run] = JSON.parse(result.stdout) as Array<{ conclusion: string; databaseId: number }>;
    return run ? { conclusion: run.conclusion, id: String(run.databaseId) } : undefined;
  }

  hasCiSignoff(commit: string): boolean {
    const result = this.commands.run("gh", ["api", `repos/:owner/:repo/commits/${commit}/status`], {
      allowFailure: true,
      capture: true,
    });
    if (result.status !== 0 || !result.stdout) return false;
    const response = JSON.parse(result.stdout) as { statuses?: Array<{ context: string; state: string }> };
    return response.statuses?.find((status) => status.context === "signoff/ci")?.state === "success";
  }

  private download(runId: string, artifact: string, destination: string): void {
    this.commands.run("gh", ["run", "download", runId, "-n", artifact, "-D", destination]);
  }
}

class SystemImages implements ImagePort {
  constructor(private readonly commands: CommandRunner) {}

  assertExists(reference: string): void {
    this.commands.run("docker", ["buildx", "imagetools", "inspect", reference], { capture: true });
  }

  buildLocal(reference: string, version: string): void {
    this.commands.run("bin/build-native-app-image", [reference, "--version", version]);
  }

  createManifest(target: string, sources: string[]): void {
    this.commands.run("docker", ["buildx", "imagetools", "create", "-t", target, ...sources]);
  }

  digest(reference: string): string {
    const digest = this.commands.run(
      "docker",
      ["buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest.Digest}}"],
      { capture: true },
    ).stdout.replaceAll('"', "");
    if (!/^sha256:[0-9a-f]{64}$/.test(digest)) throw new ReleaseError(`invalid digest for ${reference}: ${digest}`);
    return digest;
  }

  digestIfPresent(reference: string): string | undefined {
    const result = this.commands.run(
      "docker",
      ["buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest.Digest}}"],
      { allowFailure: true, capture: true },
    );
    return result.status === 0 ? result.stdout.replaceAll('"', "") : undefined;
  }

  gate(reference: string, expectedCommit: string, expectedVersion: string, previous?: string): void {
    const args = [reference, "--expect-commit", expectedCommit, "--expect-version", expectedVersion];
    if (previous) args.push("--previous", previous);
    this.commands.run("bin/release-gate", args);
  }

  moveAliases(digestReference: string, minorReference: string, latestReference: string): void {
    this.commands.run("docker", [
      "buildx",
      "imagetools",
      "create",
      "-t",
      minorReference,
      "-t",
      latestReference,
      digestReference,
    ]);
  }

  publishVersion(source: string, target: string): void {
    this.commands.run("docker", ["buildx", "imagetools", "create", "-t", target, source]);
  }

  pull(reference: string): void {
    this.commands.run("docker", ["pull", reference]);
  }

  sign(digestReference: string): void {
    this.commands.run("cosign", ["sign", "--yes", digestReference]);
  }
}

class TerminalOutput implements ReleaseOutput {
  note(message: string): void {
    console.log(`    ${message}`);
  }

  step(message: string): void {
    console.log(`\n\x1b[1m==> ${message}\x1b[0m`);
  }

  warn(message: string): void {
    console.log(`\x1b[33m    ${message}\x1b[0m`);
  }
}

export function systemEnvironment(): ReleaseEnvironment {
  const root = resolve(import.meta.dir, "../..");
  const commands = new CommandRunner(root);
  return {
    candidateRegistry:
      process.env.STATEDB_CANDIDATE_REGISTRY ??
      `${process.env.STATEDB_REGISTRY ?? "ghcr.io/state-db/statedb"}-candidates`,
    github: new SystemGitHub(root, commands),
    images: new SystemImages(commands),
    output: new TerminalOutput(),
    registry: process.env.STATEDB_REGISTRY ?? "ghcr.io/state-db/statedb",
    repository: new SystemRepository(root, commands),
    today: () => new Date().toISOString().slice(0, 10),
    validatePrerequisites: () => {
      for (const tool of ["docker", "git", "gh", "cosign"]) {
        if (!Bun.which(tool)) throw new ReleaseError(`${tool} is not on PATH`);
      }
      commands.run("docker", ["buildx", "version"], { capture: true });
    },
  };
}

function filesIn(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => resolve(directory, entry.name));
}

function formatCommand(command: string, args: string[]): string {
  return [command, ...args].map((part) => (/^[A-Za-z0-9_./:@=-]+$/.test(part) ? part : JSON.stringify(part))).join(" ");
}

function readDigest(path: string): string {
  if (!existsSync(path)) throw new ReleaseError(`native.yml evidence is missing ${path}`);
  return readFileSync(path, "utf8").trim();
}
