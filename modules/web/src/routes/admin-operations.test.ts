import { describe, expect, test } from "bun:test";

import { formatCountdown, holderLabel } from "@/routes/admin-operations";
import type { AdminLock } from "@/lib/api";

function lock(holder: AdminLock["holder"]): AdminLock {
  return {
    lockId: "lock",
    stackId: "stack",
    org: "acme",
    project: "infra",
    stack: "prod",
    holder,
    acquiredAt: "2026-01-01T00:00:00Z",
    heartbeatAt: "2026-01-01T00:00:01Z",
    expiresAt: "2026-01-01T00:01:01Z",
    update: { id: "update", kind: "update", version: 1, preview: false },
  };
}

describe("admin operations", () => {
  test("formats a live lease countdown and clamps expired leases", () => {
    const now = Date.parse("2026-01-01T00:00:00Z");
    expect(formatCountdown("2026-01-01T00:01:01Z", now)).toBe("1m 1s");
    expect(formatCountdown("2025-12-31T23:59:59Z", now)).toBe("expired");
  });

  test("prefers a resolved identity but preserves the opaque holder fallback", () => {
    expect(holderLabel(lock({ kind: "service", id: "1", displayName: "deploy-bot", email: null, fallback: "opaque" }))).toBe("deploy-bot");
    expect(holderLabel(lock({ kind: "human", id: null, displayName: null, email: null, fallback: "pulumi-cli" }))).toBe("pulumi-cli");
  });
});
