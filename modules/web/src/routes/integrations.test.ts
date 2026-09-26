import { describe, expect, test } from "bun:test";
import { canManageIntegrations, canManageOrg, connectionNotice } from "@/routes/integrations";

const owner = { siteAdmin: false, organizations: [{ name: "acme", displayName: "Acme", role: "owner" as const }] };

describe("GitHub setup authority", () => {
  test("restores only an organization the user can manage", () => {
    expect(canManageIntegrations(owner)).toBe(true);
    expect(canManageOrg(owner, "acme")).toBe(true);
    expect(canManageOrg(owner, "other-org")).toBe(false);
    expect(canManageOrg(owner, null)).toBe(false);
  });

  test("members cannot connect or disconnect", () => {
    const member = { siteAdmin: false, organizations: [{ name: "acme", displayName: "Acme", role: "member" as const }] };
    expect(canManageIntegrations(member)).toBe(false);
    expect(canManageOrg(member, "acme")).toBe(false);
  });

  test("site administrators can manage selected organizations", () => {
    expect(canManageOrg({ siteAdmin: true, organizations: [] }, "acme")).toBe(true);
  });
});

describe("GitHub callback feedback", () => {
  test("does not turn an arbitrary installation id into a success message", () => {
    expect(connectionNotice("9876")).toBeNull();
    expect(connectionNotice("verification-failed")).toContain("Could not verify");
    expect(connectionNotice("denied")).toContain("Nothing was connected");
    expect(connectionNotice("installation-pending")).toContain("approval");
  });
});
