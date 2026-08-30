import { describe, expect, test } from "bun:test";
import {
  compareVersions,
  parseOptions,
  ReleaseError,
  rollChangelog,
  UsageError,
  versionedCloudFormationPutArgs,
} from "./release";

describe("release inputs", () => {
  test("parses the public CLI", () => {
    expect(parseOptions([])).toEqual({ mode: "release", version: undefined });
    expect(parseOptions(["--version", "1.2.3", "--dry-run"])).toEqual({ mode: "dry-run", version: "1.2.3" });
    expect(parseOptions(["--rehearse"])).toEqual({ mode: "rehearse", version: undefined });
  });
  test("rejects ambiguous or incomplete arguments", () => {
    expect(() => parseOptions(["--version", "--dry-run"])).toThrow(UsageError);
    expect(() => parseOptions(["--dry-run", "--rehearse"])).toThrow(UsageError);
    expect(() => parseOptions(["--wat"])).toThrow(UsageError);
  });
});
describe("release policy", () => {
  test("compares numeric SemVer without accepting loose forms", () => {
    expect(compareVersions("1.10.0", "1.9.9")).toBe(1);
    expect(compareVersions("1.2.3", "1.2.3")).toBe(0);
    expect(() => compareVersions("01.2.3", "1.2.3")).toThrow(ReleaseError);
  });
  test("rolls only the real Unreleased heading", () => {
    const changelog = "Mention `## Unreleased` here.\n\n## Unreleased\n\n### Added\n\n- One thing\n";
    expect(rollChangelog(changelog, "0.2.0", "2026-08-22")).toBe(
      "Mention `## Unreleased` here.\n\n## Unreleased\n\n## 0.2.0 — 2026-08-22\n\n### Added\n\n- One thing\n",
    );
  });
  test("publishes a versioned CloudFormation object only when absent", () => {
    expect(versionedCloudFormationPutArgs("installer-bucket", "1.2.3", "/tmp/template.yaml")).toEqual([
      "s3api",
      "put-object",
      "--bucket",
      "installer-bucket",
      "--key",
      "releases/1.2.3/cloudformation.yaml",
      "--body",
      "/tmp/template.yaml",
      "--content-type",
      "application/yaml",
      "--if-none-match",
      "*",
    ]);
  });
});
