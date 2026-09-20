import { describe, expect, test } from "bun:test";

import {
  changeSummary,
  formatEventLine,
  formatRunDuration,
  maskConfig,
  resourceRows,
  stripAnsi,
} from "@/routes/run-detail";
import type { RunChanges } from "@/lib/api";

const emptyChanges: RunChanges = { changed: [], unchanged: [], activity: [], outputs: [] };

describe("run detail presentation", () => {
  test("strips terminal and Pulumi color sequences without changing whitespace", () => {
    expect(stripAnsi("\u001b[31merror\u001b[0m\n  detail")).toBe("error\n  detail");
    expect(stripAnsi('<{%fg 3%}>warning: <{%reset%}>property "number" is deprecated'))
      .toBe('warning: property "number" is deprecated');
  });

  test("masks secret config and unwraps ordinary config values", () => {
    const masked = maskConfig({
      "db:password": { object: false, string: "do-not-render", secret: true },
      "app:region": { object: false, string: "ca-central-1", secret: false },
      "app:ports": { object: true, string: "[80,443]", secret: false },
    });
    expect(String(masked["db:password"])).not.toContain("do-not-render");
    expect(masked["app:region"]).toBe("ca-central-1");
    expect(masked["app:ports"]).toEqual([80, 443]);
  });

  test("turns structural engine events into useful log lines", () => {
    expect(formatEventLine({
      sequence: 4,
      kind: "resourcePre",
      severity: null,
      payload: {
        resourcePreEvent: {
          metadata: {
            op: "create",
            urn: "urn:pulumi:prod::networking::aws:s3/bucket:Bucket::assets",
            type: "aws:s3/bucket:Bucket",
          },
        },
      },
    }, false)).toEqual({ marker: "+ ", text: "aws:s3/bucket:Bucket assets creating" });

    expect(formatEventLine({
      sequence: 9,
      kind: "summary",
      severity: null,
      payload: {
        summaryEvent: {
          durationSeconds: 7,
          resourceChanges: { create: 1, same: 6 },
        },
      },
    }, false)).toEqual({ marker: "✓ ", text: "Update complete · 1 created · 6 unchanged · 7s" });
  });

  test("folds resource lifecycle events for the log", () => {
    const metadata = {
      op: "update",
      urn: "urn:pulumi:prod::app::aws:lambda/function:Function::worker",
      type: "aws:lambda/function:Function",
      new: { parent: "urn:pulumi:prod::app::pulumi:pulumi:Stack::app-prod" },
      diffs: ["environment", "timeout"],
    };
    const rows = resourceRows([
      { sequence: 1, kind: "resourcePre", severity: null, payload: { timestamp: 1000, resourcePreEvent: { metadata } } },
      { sequence: 2, kind: "resOutputs", severity: null, payload: { timestamp: 1006, resOutputsEvent: { metadata } } },
      { sequence: 3, kind: "resourcePre", severity: null, payload: { resourcePreEvent: { metadata: { ...metadata, op: "same", urn: `${metadata.urn}-same` } } } },
    ], false, {
      ...emptyChanges,
      changed: [{
        sequence: 1,
        urn: metadata.urn,
        type: metadata.type,
        name: "worker",
        op: "update",
        properties: [
          { path: "environment", kind: "update", internal: false, secret: false, oldPresent: false, oldValue: null, newPresent: false, newValue: null },
          { path: "timeout", kind: "update", internal: false, secret: false, oldPresent: false, oldValue: null, newPresent: false, newValue: null },
        ],
      }],
    });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      marker: "~ ",
      name: "worker",
      status: "updated",
      done: true,
      seconds: 6,
      diffs: ["environment", "timeout"],
    });
  });

  test("takes replacement operations from the canonical backend model", () => {
    const urn = "urn:pulumi:prod::app::aws:s3/bucket:Bucket::assets";
    const rows = resourceRows([
      {
        sequence: 1,
        kind: "resourcePre",
        severity: null,
        payload: {
          resourcePreEvent: {
            metadata: { op: "create-replacement", urn, type: "aws:s3/bucket:Bucket" },
          },
        },
      },
    ], false, {
      ...emptyChanges,
      changed: [{
        sequence: 1,
        urn,
        type: "aws:s3/bucket:Bucket",
        name: "assets",
        op: "replace",
        properties: [],
      }],
    });

    expect(rows[0]?.op).toBe("replace");
    expect(rows[0]?.marker).toBe("+-");
  });

  test("formats live and historical durations", () => {
    expect(formatRunDuration("2026-07-19T10:00:00Z", "2026-07-19T10:02:14Z")).toBe("2m 14s");
    expect(formatRunDuration("2026-07-19T10:00:00Z", null, Date.parse("2026-07-19T10:00:09Z"))).toBe("9s");
  });

  test("counts replacements without folding them into create or delete", () => {
    const direct = { summary: { resourceChanges: { replace: 2, same: 3 } } } as never;
    expect(changeSummary(direct)).toEqual({ created: 0, updated: 0, deleted: 0, replaced: 2, unchanged: 3 });

    const decomposed = {
      summary: {
        resourceChanges: {
          "create-replacement": 1,
          "delete-replaced": 1,
          create: 1,
        },
      },
    } as never;
    expect(changeSummary(decomposed)).toMatchObject({ created: 1, replaced: 1 });
  });
});
