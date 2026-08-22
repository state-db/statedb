export type ReleaseMode = "release" | "dry-run" | "rehearse";

export interface ReleaseOptions {
  mode: ReleaseMode;
  version?: string;
}

export interface ReleaseResult {
  commit: string;
  version: string;
  digest?: string;
  nextVersion?: string;
  published: boolean;
}

export interface NativeRun {
  conclusion: string;
  id: string;
}

export interface NativeEvidence {
  assets: string[];
  candidateDigests: Record<"amd64" | "arm64", string>;
  javaSbom: string;
}

export interface RepositoryPort {
  branch(): string;
  buildVersion(): string;
  changelogNotes(): string;
  createAnnotatedTag(tag: string, version: string): void;
  deleteLocalTag(tag: string): void;
  fetchMain(): void;
  head(): string;
  isClean(): boolean;
  localTagExists(tag: string): boolean;
  nextPlannedVersion(): string;
  originMain(): string;
  pushTagAtomically(tag: string, expectedMain: string): void;
  remoteReleaseVersions(): string[];
  rollForward(version: string, nextVersion: string, date: string): void;
}

export interface GitHubPort {
  createRelease(tag: string, version: string, notes: string, assets: string[]): void;
  downloadEvidence(runId: string): NativeEvidence;
  findNativeRun(commit: string): NativeRun | undefined;
  hasCiSignoff(commit: string): boolean;
}

export interface ImagePort {
  assertExists(reference: string): void;
  buildLocal(reference: string, version: string): void;
  createManifest(target: string, sources: string[]): void;
  digest(reference: string): string;
  digestIfPresent(reference: string): string | undefined;
  gate(reference: string, expectedCommit: string, expectedVersion: string, previous?: string): void;
  moveAliases(digestReference: string, minorReference: string, latestReference: string): void;
  publishVersion(source: string, target: string): void;
  pull(reference: string): void;
  sign(digestReference: string): void;
}

export interface ReleaseOutput {
  note(message: string): void;
  step(message: string): void;
  warn(message: string): void;
}

export interface ReleaseEnvironment {
  github: GitHubPort;
  images: ImagePort;
  output: ReleaseOutput;
  registry: string;
  candidateRegistry: string;
  repository: RepositoryPort;
  today(): string;
  validatePrerequisites(): void;
}

export class UsageError extends Error {}

export class ReleaseError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ReleaseError";
  }
}

export class RecoveryRequired extends ReleaseError {
  constructor(
    public readonly version: string,
    public readonly completedPhase: string,
    cause: unknown,
  ) {
    const detail = cause instanceof Error ? cause.message : String(cause);
    super(
      `release ${version} is partially published after ${completedPhase}: ${detail}. ` +
        `Inspect the matching Git tag and image digest, then reconcile the remaining steps manually.`,
      { cause },
    );
    this.name = "RecoveryRequired";
  }
}

const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;

export function parseOptions(argv: string[]): ReleaseOptions {
  let mode: ReleaseMode = "release";
  let modeFlag: string | undefined;
  let version: string | undefined;

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--dry-run" || argument === "--rehearse") {
      if (modeFlag && modeFlag !== argument) {
        throw new UsageError("--dry-run and --rehearse are mutually exclusive");
      }
      modeFlag = argument;
      mode = argument === "--rehearse" ? "rehearse" : "dry-run";
    } else if (argument === "--version") {
      version = argv[index + 1];
      if (!version || version.startsWith("--")) throw new UsageError("--version needs X.Y.Z");
      index += 1;
    } else {
      throw new UsageError(`unknown argument: ${argument}`);
    }
  }

  return { mode, version };
}

export function compareVersions(left: string, right: string): number {
  const leftParts = parseVersion(left);
  const rightParts = parseVersion(right);
  for (let index = 0; index < leftParts.length; index += 1) {
    if (leftParts[index] > rightParts[index]) return 1;
    if (leftParts[index] < rightParts[index]) return -1;
  }
  return 0;
}

export function rollChangelog(changelog: string, version: string, date: string): string {
  const heading = /^## Unreleased$/m;
  if (!heading.test(changelog)) throw new ReleaseError("CHANGELOG.md is missing '## Unreleased'");
  return changelog.replace(heading, `## Unreleased\n\n## ${version} — ${date}`);
}

export function runRelease(options: ReleaseOptions, environment: ReleaseEnvironment): ReleaseResult {
  const { github, images, output, repository } = environment;
  const relaxed = options.mode === "rehearse";
  const dryRun = options.mode !== "release";
  let localTagCreated = false;
  let tagPublished = false;
  let completedPhase = "preflight";
  let version = options.version ?? "unknown";
  let tag = "";

  const requireCondition = (condition: boolean, message: string): boolean => {
    if (condition) return true;
    if (relaxed) {
      output.warn(`[rehearsal] would have failed: ${message}`);
      return false;
    }
    throw new ReleaseError(message);
  };

  const requireAction = (action: () => void, message: string): boolean => {
    try {
      action();
      return true;
    } catch (error) {
      if (relaxed) {
        output.warn(`[rehearsal] would have failed: ${message}`);
        return false;
      }
      throw new ReleaseError(message, { cause: error });
    }
  };

  const ensureSourceUnchanged = (commit: string): void => {
    if (repository.head() !== commit) throw new ReleaseError("HEAD changed during release verification");
    if (!repository.isClean()) throw new ReleaseError("working tree changed during release verification");
    try {
      repository.fetchMain();
    } catch (error) {
      throw new ReleaseError("could not refresh origin/main before publication", { cause: error });
    }
    if (repository.originMain() !== commit) {
      throw new ReleaseError("origin/main changed during release verification");
    }
  };

  try {
    output.step("Preflight");
    environment.validatePrerequisites();
    requireCondition(repository.branch() === "main", "not on main");
    requireCondition(repository.isClean(), "working tree is dirty");
    const fetched = requireAction(() => repository.fetchMain(), "could not fetch origin/main");
    if (fetched) requireCondition(repository.head() === repository.originMain(), "HEAD is not origin/main");

    version = options.version ?? repository.nextPlannedVersion().trim();
    parseVersion(version);
    tag = `v${version}`;
    if (repository.localTagExists(tag)) throw new ReleaseError(`${tag} already exists locally`);

    let remoteVersions: string[] = [];
    try {
      remoteVersions = repository.remoteReleaseVersions().filter((candidate) => SEMVER.test(candidate));
    } catch (error) {
      if (!relaxed) throw new ReleaseError("could not list release tags on origin", { cause: error });
      output.warn("[rehearsal] would have failed: could not list release tags on origin");
    }
    if (remoteVersions.includes(version)) throw new ReleaseError(`${tag} already exists on origin`);
    const latestVersion = remoteVersions.sort(compareVersions).at(-1);
    if (latestVersion && compareVersions(version, latestVersion) <= 0) {
      throw new ReleaseError(`version ${version} must be newer than the latest release, ${latestVersion}`);
    }

    const commitFull = repository.head();
    const commit = commitFull.slice(0, 7);
    const minor = version.slice(0, version.lastIndexOf("."));
    const notes = repository.changelogNotes();
    if (!notes.trim()) throw new ReleaseError("CHANGELOG.md has an empty '## Unreleased' section");
    output.note(`releasing ${commit} as ${version}`);

    output.step(`Gate: what verification has already proved about ${commit}`);
    if (requireCondition(github.hasCiSignoff(commitFull), `local CI has not signed off on ${commit}`)) {
      output.note("local CI signed off");
    }
    const nativeRun = github.findNativeRun(commitFull);
    if (requireCondition(nativeRun?.conclusion === "success", `native.yml has not succeeded on ${commit}`)) {
      output.note("release build green: Pulumi compatibility plus native amd64 and arm64");
    }
    const evidence = nativeRun?.conclusion === "success" ? github.downloadEvidence(nativeRun.id) : undefined;

    output.step("Tagging locally");
    repository.createAnnotatedTag(tag, version);
    localTagCreated = true;
    const builtVersion = repository.buildVersion();
    const acceptedDirtyVersion = relaxed && builtVersion === `${version}+dirty`;
    if (builtVersion !== version && !acceptedDirtyVersion) {
      throw new ReleaseError(`build identity resolved to '${builtVersion}', expected '${version}'`);
    }
    output.note(`build identity: ${builtVersion}`);

    output.step("Building the native release candidate");
    const localCandidate = `state-db:${version}`;
    images.buildLocal(localCandidate, version);
    const previous = latestVersion ? `${environment.registry}:${latestVersion}` : undefined;
    if (latestVersion) output.note(`upgrading from v${latestVersion}`);
    images.gate(localCandidate, commit, version, previous);

    output.step("Assembling the immutable multi-architecture manifest");
    const candidateManifest = `${environment.candidateRegistry}:${commitFull}`;
    const candidateReferences = evidence
      ? (["amd64", "arm64"] as const).map((architecture) => {
          const digest = evidence.candidateDigests[architecture];
          if (!/^sha256:[0-9a-f]{64}$/.test(digest)) {
            throw new ReleaseError(`invalid ${architecture} candidate digest: ${digest}`);
          }
          const reference = `${environment.candidateRegistry}@${digest}`;
          images.assertExists(reference);
          return reference;
        })
      : [];

    if (!evidence && relaxed) {
      output.warn("[rehearsal] final manifest inspection skipped without successful native.yml evidence");
    } else if (dryRun) {
      output.note("CI candidate digests exist; final manifest creation and gate require a registry write");
    } else {
      ensureSourceUnchanged(commitFull);
      images.createManifest(candidateManifest, candidateReferences);
      images.pull(candidateManifest);
      images.gate(candidateManifest, commit, version, previous);

      ensureSourceUnchanged(commitFull);
      const digest = images.digest(candidateManifest);
      output.note(`digest ${digest}`);
      const versionReference = `${environment.registry}:${version}`;
      const existingDigest = images.digestIfPresent(versionReference);
      if (existingDigest && existingDigest !== digest) {
        throw new ReleaseError(`${versionReference} already points at a different digest, ${existingDigest}`);
      }

      output.step("Tagging the release");
      repository.pushTagAtomically(tag, commitFull);
      tagPublished = true;
      completedPhase = "Git tag publication";

      output.step("Publishing the signed image and GitHub Release");
      if (!existingDigest) images.publishVersion(candidateManifest, versionReference);
      completedPhase = "immutable image publication";
      const digestReference = `${environment.registry}@${digest}`;
      images.sign(digestReference);
      completedPhase = "image signing";
      images.moveAliases(digestReference, `${environment.registry}:${minor}`, `${environment.registry}:latest`);
      output.note(`signed native ${digest}; moved ${minor} and latest`);
      completedPhase = "floating tag publication";
      github.createRelease(tag, version, notes, [evidence!.javaSbom, ...evidence!.assets]);
      completedPhase = "GitHub Release publication";

      output.step("Rolling the changelog and .version");
      const [major, minorNumber] = minor.split(".").map(BigInt);
      const nextVersion = `${major}.${minorNumber + 1n}.0`;
      repository.rollForward(version, nextVersion, environment.today());
      completedPhase = "repository rollover";
      return { commit: commitFull, digest, nextVersion, published: true, version };
    }

    output.step("Rolling the changelog and .version");
    output.note(`[dry-run] would roll CHANGELOG.md, advance .version, and push main`);
    return { commit: commitFull, published: false, version };
  } catch (error) {
    if (tagPublished) throw new RecoveryRequired(version, completedPhase, error);
    if (error instanceof ReleaseError || error instanceof UsageError) throw error;
    throw new ReleaseError(error instanceof Error ? error.message : String(error), { cause: error });
  } finally {
    if (localTagCreated && !tagPublished) repository.deleteLocalTag(tag);
  }
}

function parseVersion(version: string): [bigint, bigint, bigint] {
  const match = SEMVER.exec(version);
  if (!match) throw new ReleaseError(`version '${version}' is not X.Y.Z`);
  return [BigInt(match[1]), BigInt(match[2]), BigInt(match[3])];
}
