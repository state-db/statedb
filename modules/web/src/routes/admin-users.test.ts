import { describe, expect, test } from "bun:test";

import { filterUsers, identityString, manageOrgs } from "@/routes/admin-users";
import type { AdminUser } from "@/lib/api";

function user(overrides: Partial<AdminUser>): AdminUser {
  return {
    id: "00000000-0000-0000-0000-000000000000",
    iss: "https://accounts.google.com",
    sub: "115",
    email: "a@example.com",
    displayName: "A",
    principalType: "human",
    siteAdmin: false,
    lastLoginAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    organizations: [],
    ...overrides,
  };
}

describe("admin users", () => {
  test("identity string is the exact <iss>:<sub> paste format", () => {
    // The issuer itself contains colons; the identity is a literal join, never split on ':'.
    expect(identityString({ iss: "https://accounts.google.com", sub: "115" })).toBe(
      "https://accounts.google.com:115",
    );
  });

  test("filter segments by principal type", () => {
    const users = [user({ principalType: "human" }), user({ principalType: "service" })];
    expect(filterUsers(users, "human").map((u) => u.principalType)).toEqual(["human"]);
    expect(filterUsers(users, "service").map((u) => u.principalType)).toEqual(["service"]);
    expect(filterUsers(users, "all")).toHaveLength(2);
  });

  test("service accounts offer no manage-memberships target", () => {
    const org = { name: "acme", displayName: "Acme", role: "service" };
    expect(manageOrgs(user({ principalType: "service", organizations: [org] }))).toEqual([]);
  });

  test("humans offer each of their orgs as a target", () => {
    const orgs = [
      { name: "acme", displayName: "Acme", role: "owner" },
      { name: "beta", displayName: "Beta", role: "member" },
    ];
    expect(manageOrgs(user({ organizations: orgs })).map((o) => o.name)).toEqual(["acme", "beta"]);
  });
});
