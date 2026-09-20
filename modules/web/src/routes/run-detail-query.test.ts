import { QueryClient } from "@tanstack/react-query";
import { describe, expect, test } from "bun:test";

import type { RunDetail } from "@/lib/api";
import { prefetchRun, runQueryOptions } from "@/routes/run-detail";

const run: RunDetail = {
  id: "7f000001-a05c-1880-81a0-7d42f2550001",
  version: 1,
  kind: "update",
  status: "succeeded",
  message: "done",
  runtime: null,
  pulumiVersion: null,
  startedAt: "2026-09-07T19:00:00Z",
  endedAt: "2026-09-07T19:01:00Z",
  summary: null,
  changes: { changed: [], unchanged: [], activity: [], outputs: [] },
  changesTruncated: false,
  config: {},
  options: {},
  metadata: {},
};

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("run detail route loading", () => {
  // A blocking loader would put the run request and AppShell's /api/user request in series.
  test("the loader starts the request without making the caller await it", () => {
    let calls = 0;
    let settle: (value: RunDetail) => void = () => {};
    const fetchRun = () => {
      calls += 1;
      return new Promise<RunDetail>((resolve) => {
        settle = resolve;
      });
    };

    const returned = prefetchRun(
      client(),
      "default",
      "deploy-simulator",
      "dev",
      { version: 1 },
      fetchRun,
    );

    // In flight already, with no promise returned for the router to await.
    expect(calls).toBe(1);
    expect(returned).toBeUndefined();
    settle(run);
  });

  test("the component mount joins the loader's request instead of issuing a second", async () => {
    const shared = client();
    let calls = 0;
    const fetchRun = async () => {
      calls += 1;
      await new Promise((resolve) => setTimeout(resolve, 1));
      return run;
    };
    const options = runQueryOptions("default", "deploy-simulator", "dev", { version: 1 }, fetchRun);

    prefetchRun(shared, "default", "deploy-simulator", "dev", { version: 1 }, fetchRun);
    await shared.ensureQueryData(options);

    expect(calls).toBe(1);
  });

  test("a mount after the loader settles reuses the cached run", async () => {
    const shared = client();
    let calls = 0;
    const fetchRun = () => {
      calls += 1;
      return Promise.resolve(run);
    };
    const options = runQueryOptions("default", "deploy-simulator", "dev", { version: 1 }, fetchRun);

    await shared.ensureQueryData(options);
    await shared.ensureQueryData(options);

    expect(calls).toBe(1);
    expect(options.staleTime).toBe(5_000);
  });
});
