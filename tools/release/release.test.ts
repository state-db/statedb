import { describe, expect, test } from "bun:test";
import {
  RecoveryRequired,
  ReleaseError,
  type ReleaseEnvironment,
  type ReleaseMode,
  parseOptions,
  rollChangelog,
  runRelease,
  UsageError,
} from "./release";

const DIGEST_A = `sha256:${"a".repeat(64)}`;
const DIGEST_B = `sha256:${"b".repeat(64)}`;
const MANIFEST_DIGEST = `sha256:${"c".repeat(64)}`;

class Fixture {
  readonly actions: string[] = [];
  branch = "main";
  clean = true;
  head = "1234567890abcdef1234567890abcdef12345678";
  originMain = this.head;
  remoteVersions = ["0.1.0"];
  signoff = true;
  nativeConclusion = "success";
  failPublishing = false;

  environment(): ReleaseEnvironment {
    const record = (action: string) => this.actions.push(action);
    return {
      candidateRegistry: "registry.example/state-db-candidates",
      registry: "registry.example/state-db",
      today: () => "2026-08-22",
      validatePrerequisites: () => record("prerequisites"),
      output: {
        note: (message) => record(`note:${message}`),
        step: (message) => record(`step:${message}`),
        warn: (message) => record(`warn:${message}`),
      },
      repository: {
        branch: () => this.branch,
        buildVersion: () => "0.2.0",
        changelogNotes: () => "### Added\n\n- Release tooling",
        createAnnotatedTag: (tag) => record(`create-tag:${tag}`),
        deleteLocalTag: (tag) => record(`delete-tag:${tag}`),
        fetchMain: () => record("fetch-main"),
        head: () => this.head,
        isClean: () => this.clean,
        localTagExists: () => false,
        nextPlannedVersion: () => "0.2.0",
        originMain: () => this.originMain,
        pushTagAtomically: (tag, expected) => record(`push-tag:${tag}:${expected}`),
        remoteReleaseVersions: () => this.remoteVersions,
        rollForward: (version, next, date) => record(`roll:${version}:${next}:${date}`),
      },
      github: {
        createRelease: (_tag, _version, _notes, assets) => record(`github-release:${assets.length}`),
        downloadEvidence: () => ({
          assets: ["amd64.json", "arm64.json"],
          candidateDigests: { amd64: DIGEST_A, arm64: DIGEST_B },
          javaSbom: "java.cdx.json",
        }),
        findNativeRun: () =>
          this.nativeConclusion ? { conclusion: this.nativeConclusion, id: "42" } : undefined,
        hasCiSignoff: () => this.signoff,
      },
      images: {
        assertExists: (reference) => record(`inspect:${reference}`),
        buildLocal: (reference, version) => record(`build:${reference}:${version}`),
        createManifest: (target, sources) => record(`manifest:${target}:${sources.join(",")}`),
        digest: () => MANIFEST_DIGEST,
        digestIfPresent: () => undefined,
        gate: (reference, commit, version) => record(`gate:${reference}:${commit}:${version}`),
        moveAliases: () => record("move-aliases"),
        publishVersion: () => {
          record("publish-version");
          if (this.failPublishing) throw new Error("registry unavailable");
        },
        pull: (reference) => record(`pull:${reference}`),
        sign: () => record("sign"),
      },
    };
  }
}

function run(fixture: Fixture, mode: ReleaseMode = "release") {
  return runRelease({ mode }, fixture.environment());
}

describe("release orchestration", () => {
  test("publishes only the digest-pinned, version-checked manifest", () => {
    const fixture = new Fixture();
    const result = run(fixture);

    expect(result).toEqual({
      commit: fixture.head,
      digest: MANIFEST_DIGEST,
      nextVersion: "0.3.0",
      published: true,
      version: "0.2.0",
    });
    expect(fixture.actions).toContain(
      `manifest:registry.example/state-db-candidates:${fixture.head}:registry.example/state-db-candidates@${DIGEST_A},registry.example/state-db-candidates@${DIGEST_B}`,
    );
    expect(fixture.actions).toContain(
      `gate:registry.example/state-db-candidates:${fixture.head}:1234567:0.2.0`,
    );
    expect(fixture.actions.indexOf(`push-tag:v0.2.0:${fixture.head}`)).toBeLessThan(
      fixture.actions.indexOf("publish-version"),
    );
    expect(fixture.actions).toContain("roll:0.2.0:0.3.0:2026-08-22");
  });

  test("dry-run performs no external publication", () => {
    const fixture = new Fixture();
    const result = run(fixture, "dry-run");

    expect(result.published).toBe(false);
    expect(fixture.actions).not.toContain("publish-version");
    expect(fixture.actions.some((action) => action.startsWith("push-tag:"))).toBe(false);
    expect(fixture.actions).toContain("delete-tag:v0.2.0");
    expect(fixture.actions).toContain(`inspect:registry.example/state-db-candidates@${DIGEST_A}`);
  });

  test("rehearsal warns about missing CI evidence and still gates the local image", () => {
    const fixture = new Fixture();
    fixture.clean = false;
    fixture.signoff = false;
    fixture.nativeConclusion = "";

    const result = run(fixture, "rehearse");

    expect(result.published).toBe(false);
    expect(fixture.actions.some((action) => action.startsWith("warn:"))).toBe(true);
    expect(fixture.actions).toContain("gate:state-db:0.2.0:1234567:0.2.0");
    expect(fixture.actions).toContain("delete-tag:v0.2.0");
  });

  test("rejects a version that does not advance the remote release", () => {
    const fixture = new Fixture();
    fixture.remoteVersions = ["0.2.0"];

    expect(() => run(fixture)).toThrow(ReleaseError);
    expect(fixture.actions.some((action) => action.startsWith("create-tag:"))).toBe(false);
  });

  test("preserves a published Git tag and reports recovery after a later failure", () => {
    const fixture = new Fixture();
    fixture.failPublishing = true;

    expect(() => run(fixture)).toThrow(RecoveryRequired);
    expect(fixture.actions).not.toContain("delete-tag:v0.2.0");
  });
});

describe("release input and changelog policy", () => {
  test("a missing --version value is a usage error", () => {
    expect(() => parseOptions(["--version", "--dry-run"])).toThrow(UsageError);
  });

  test("rolls only the actual Unreleased heading", () => {
    const changelog = "Mention `## Unreleased` here.\n\n## Unreleased\n\n### Added\n\n- One thing\n";
    expect(rollChangelog(changelog, "0.2.0", "2026-08-22")).toBe(
      "Mention `## Unreleased` here.\n\n## Unreleased\n\n## 0.2.0 — 2026-08-22\n\n### Added\n\n- One thing\n",
    );
  });
});
