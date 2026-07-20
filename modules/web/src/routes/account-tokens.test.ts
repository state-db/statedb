import { describe, expect, test } from "bun:test";

import { expirationState, readMintedToken } from "@/routes/account-tokens";

describe("personal token one-time display", () => {
  test("only reads a fragment token when a minted id is present", () => {
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: { location: { search: "?minted=token-id", hash: "#token=raw%2Bsecret" } },
    });
    expect(readMintedToken()).toEqual({ id: "token-id", rawToken: "raw+secret" });
  });

  test("does not expose an unrelated token fragment", () => {
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: { location: { search: "", hash: "#token=raw-secret" } },
    });
    expect(readMintedToken().rawToken).toBeNull();
  });

  test("marks tokens with less than two hours left as expiring", () => {
    const now = Date.parse("2026-07-19T12:00:00Z");
    expect(expirationState("2026-07-19T13:59:59Z", now)).toBe("expiring");
    expect(expirationState("2026-07-19T14:00:00Z", now)).toBe("active");
    expect(expirationState("2026-07-19T11:59:59Z", now)).toBe("expired");
  });
});
