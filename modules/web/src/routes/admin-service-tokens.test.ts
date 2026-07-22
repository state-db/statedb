import { describe, expect, test } from "bun:test";

import {
  canManageServiceTokens,
  expiryState,
  serviceAccountStatus,
} from "@/routes/admin-service-tokens";

const active = {
  tokenRevokedAt: null,
  accountDeactivatedAt: null,
  tokenExpiresAt: "2026-10-01T00:00:00Z",
};

describe("service token lifecycle", () => {
  test("site admins and owners can manage while ordinary members cannot", () => {
    expect(canManageServiceTokens({ siteAdmin: true, organizations: [] })).toBe(true);
    expect(canManageServiceTokens({
      siteAdmin: false,
      organizations: [{ name: "acme", displayName: "Acme", role: "owner" }],
    })).toBe(true);
    expect(canManageServiceTokens({
      siteAdmin: false,
      organizations: [{ name: "acme", displayName: "Acme", role: "member" }],
    })).toBe(false);
  });

  test("revocation takes precedence over expiry", () => {
    expect(serviceAccountStatus({
      ...active,
      tokenRevokedAt: "2026-07-01T00:00:00Z",
      tokenExpiresAt: "2026-06-01T00:00:00Z",
    }, Date.parse("2026-07-21T00:00:00Z"))).toBe("revoked");
  });

  test("expired and active credentials are distinguished at the current instant", () => {
    const now = Date.parse("2026-07-21T00:00:00Z");
    expect(serviceAccountStatus({ ...active, tokenExpiresAt: "2026-07-21T00:00:00Z" }, now)).toBe("expired");
    expect(serviceAccountStatus({ ...active, tokenExpiresAt: "2026-07-21T00:00:01Z" }, now)).toBe("active");
  });

  test("seven-day warning boundary is inclusive", () => {
    const now = Date.parse("2026-07-21T00:00:00Z");
    expect(expiryState("2026-07-28T00:00:00Z", now)).toBe("expiring");
    expect(expiryState("2026-07-28T00:00:01Z", now)).toBe("healthy");
  });
});
