import { describe, expect, test } from "bun:test";

import { safeReturnTo } from "@/routes/login";

const ORIGIN = "https://statedb.test";

describe("login returnTo", () => {
  test("keeps console paths and the CLI login handler", () => {
    expect(safeReturnTo("/console/stacks?x=1#top", ORIGIN)).toBe(
      "/console/stacks?x=1#top",
    );
    expect(safeReturnTo("/console", ORIGIN)).toBe("/console");
    const cli =
      "/api/auth/cli-login?cliSessionPort=59123&cliSessionNonce=n&afterSignIn=1";
    expect(safeReturnTo(cli, ORIGIN)).toBe(cli);
  });

  test("falls back to the console for anything off-site or outside the allow-list", () => {
    for (const value of [
      null,
      "",
      "//evil.example",
      "/\\evil.example",
      "/\\/evil.example",
      "https://evil.example/console",
      "javascript:alert(1)",
      "/api/user",
      "/console/../api/auth/cli-mint",
      "/consoleevil",
      "/api/auth/cli-login/approve",
    ]) {
      expect(safeReturnTo(value, ORIGIN)).toBe("/console");
    }
  });
});
