import { describe, expect, test } from "bun:test";

import { expirationState } from "@/routes/account-tokens";

describe("personal token one-time display", () => {
  test("marks tokens with less than two hours left as expiring", () => {
    const now = Date.parse("2026-07-19T12:00:00Z");
    expect(expirationState("2026-07-19T13:59:59Z", now)).toBe("expiring");
    expect(expirationState("2026-07-19T14:00:00Z", now)).toBe("active");
    expect(expirationState("2026-07-19T11:59:59Z", now)).toBe("expired");
  });
});
