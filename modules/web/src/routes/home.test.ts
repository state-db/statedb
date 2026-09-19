import { describe, expect, test } from "bun:test";

import {
  isActiveUpdate,
  isFailedUpdate,
  lastUpdateVersionLabel,
  matchesFilter,
  runningIndicatorLabel,
  summarizeStacks,
} from "@/routes/home";
import type { Workspace } from "@/lib/api";

describe("home stack summary", () => {
  test("treats locks and in-progress updates as the same running state", () => {
    const locked = workspace("locked", {
      lock: {
        lockId: "lease-1",
        lockedBy: "pulumi",
        lockedAt: "2026-07-26T00:00:00Z",
      },
    });
    const updating = workspace("updating", { status: "in-progress" });
    const both = workspace("both", {
      status: "in-progress",
      lock: {
        lockId: "lease-2",
        lockedBy: "pulumi",
        lockedAt: "2026-07-26T00:00:00Z",
      },
    });

    expect([locked, updating, both].map(isActiveUpdate)).toEqual([true, true, true]);
    expect(summarizeStacks([locked, updating, both]).active).toBe(3);
    expect(matchesFilter(locked, "running")).toBe(true);
  });

  test("tracks failed stacks separately from running stacks", () => {
    const running = workspace("running", { status: "in-progress" });
    const failed = workspace("failed", { status: "failed" });

    expect(isFailedUpdate(running)).toBe(false);
    expect(isFailedUpdate(failed)).toBe(true);
    expect(summarizeStacks([running, failed]).failed).toBe(1);
    expect(matchesFilter(running, "failed")).toBe(false);
    expect(matchesFilter(failed, "failed")).toBe(true);
  });

  test("does not present a preview as applied version zero", () => {
    const preview = workspace("preview", { status: "succeeded", version: null });

    expect(lastUpdateVersionLabel(preview.lastUpdate!)).toBeNull();
  });

  test("uses the resolved service account name in the running tooltip", () => {
    const running = workspace("running", { status: "in-progress" });
    running.lastUpdate!.actor = {
      kind: "service",
      principalId: "service-account-id",
      credentialId: "token-id",
      displayName: "Deploy Bot",
      email: null,
      fallback: "user:service-account-id",
      accessProfile: "deploy",
      revoked: false,
    };
    running.lock = {
      lockId: "lease",
      lockedBy: "user:service-account-id",
      lockedAt: "2026-07-26T00:00:00Z",
    };

    expect(runningIndicatorLabel(running)).toContain("Deploy Bot");
    expect(runningIndicatorLabel(running)).not.toContain("service-account-id");
  });
});

function workspace(
  stack: string,
  overrides: {
    status?: NonNullable<Workspace["lastUpdate"]>["status"];
    version?: number | null;
    lock?: Workspace["lock"];
  } = {},
): Workspace {
  return {
    id: stack,
    org: "acme",
    project: "infra",
    projectCreatedAt: "2026-07-26T00:00:00Z",
    stack,
    kind: "pulumi",
    version: 1,
    resourceCount: 0,
    lastUpdate: overrides.status
      ? {
          id: `${stack}-run`,
          version: overrides.version === undefined ? 1 : overrides.version,
          kind: "update",
          status: overrides.status,
          startedAt: "2026-07-26T00:00:00Z",
          actor: null,
        }
      : null,
    lock: overrides.lock ?? null,
  };
}
