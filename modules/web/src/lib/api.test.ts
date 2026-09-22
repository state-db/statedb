import { afterEach, describe, expect, test } from "bun:test";

import { api, UnauthorizedError } from "@/lib/api";

// request() times out via window.setTimeout; bun:test runs with no DOM.
(globalThis as { window?: unknown }).window ??= globalThis;

const originalFetch = global.fetch;

afterEach(() => {
  global.fetch = originalFetch;
});

function stubFetch(status: number) {
  let sentHeaders: Headers | undefined;
  global.fetch = ((_url: string, init?: RequestInit) => {
    sentHeaders = new Headers(init?.headers);
    return Promise.resolve(new Response(status === 200 ? "{}" : "", { status }));
  }) as typeof fetch;
  return () => sentHeaders;
}

describe("api request", () => {
  test("treats 499 the same as 401", async () => {
    stubFetch(499);
    let error: unknown;
    try {
      await api.user.get();
    } catch (caught) {
      error = caught;
    }
    expect(error).toBeInstanceOf(UnauthorizedError);
  });

  test("marks every request as a JavaScript fetch", async () => {
    const headers = stubFetch(200);
    await api.user.get();
    expect(headers()?.get("X-Requested-With")).toBe("XMLHttpRequest");
  });
});
