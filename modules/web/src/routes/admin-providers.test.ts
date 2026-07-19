import { describe, expect, test } from "bun:test";

import { isHttpUrl, parseScopes, providerWrite } from "@/routes/admin-providers";

describe("OIDC provider form", () => {
  test("normalizes comma-separated scopes", () => {
    expect(parseScopes("openid, profile, email, profile, ")).toEqual([
      "openid",
      "profile",
      "email",
    ]);
  });

  test("accepts only absolute HTTP(S) issuer URLs", () => {
    expect(isHttpUrl("https://id.example.com/tenant")).toBe(true);
    expect(isHttpUrl("http://localhost:8080/realms/test")).toBe(true);
    expect(isHttpUrl("id.example.com")).toBe(false);
    expect(isHttpUrl("javascript:alert(1)")).toBe(false);
  });

  test("creates providers disabled without changing enabled during edits", () => {
    const values = {
      name: " company-sso ",
      issuerUrl: " https://id.example.com/ ",
      clientId: " console ",
      clientSecret: "secret",
      scopes: "openid, email",
    };

    expect(providerWrite(values, false).enabled).toBe(false);
    expect(providerWrite(values, true)).not.toHaveProperty("enabled");
  });
});
