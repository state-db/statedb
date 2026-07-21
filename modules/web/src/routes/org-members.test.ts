import { describe, expect, test } from "bun:test";

import { canManageOrganizationMembers } from "@/routes/org-members";

describe("organization member administration access", () => {
  test("allows an owner without site-admin access", () => {
    expect(canManageOrganizationMembers({
      siteAdmin: false,
      organizations: [{ name: "acme", displayName: "Acme", role: "owner" }],
    }, "acme")).toBe(true);
  });

  test("does not allow a non-owner member", () => {
    expect(canManageOrganizationMembers({
      siteAdmin: false,
      organizations: [{ name: "acme", displayName: "Acme", role: "member" }],
    }, "acme")).toBe(false);
  });

  test("allows a site admin without organization membership", () => {
    expect(canManageOrganizationMembers({
      siteAdmin: true,
      organizations: [],
    }, "acme")).toBe(true);
  });
});
