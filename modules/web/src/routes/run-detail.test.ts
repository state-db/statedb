import { describe, expect, test } from "bun:test";

import type { RunEvent } from "@/lib/api";
import {
  formatEventLine,
  formatRunDuration,
  jsonDiffLines,
  maskConfig,
  propertyChanges,
  resourceChanges,
  resourceRows,
  stackOutputsFromEvents,
  stripAnsi,
} from "@/routes/run-detail";

describe("run detail presentation", () => {
  test("strips terminal escape sequences without changing whitespace", () => {
    expect(stripAnsi("\u001b[31merror\u001b[0m\n  detail")).toBe("error\n  detail");
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
      sequence: 4,
      kind: "resourcePre",
      severity: null,
      payload: { resourcePreEvent: { metadata: { op: "create", urn: "urn:pulumi:prod::p::t::n", type: "t" } } },
    }, true)).toEqual({ marker: "+ ", text: "t n create" });

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

    expect(formatEventLine({
      sequence: 10,
      kind: "diagnostic",
      severity: "warning",
      payload: { diagnosticEvent: { prefix: "aws:s3/bucket:Bucket (assets): ", message: "deprecated" } },
    }, false)).toEqual({ marker: "! ", text: "aws:s3/bucket:Bucket (assets): deprecated" });
  });

  test("folds resource lifecycle events and hides unchanged resources", () => {
    const metadata = {
      op: "update",
      urn: "urn:pulumi:prod::app::aws:lambda/function:Function::worker",
      type: "aws:lambda/function:Function",
      new: { parent: "urn:pulumi:prod::app::pulumi:pulumi:Stack::app-prod" },
    };
    const rows = resourceRows([
      { sequence: 1, kind: "resourcePre", severity: null, payload: { resourcePreEvent: { metadata } } },
      { sequence: 2, kind: "resOutputs", severity: null, payload: { resOutputsEvent: { metadata } } },
      { sequence: 3, kind: "resourcePre", severity: null, payload: { resourcePreEvent: { metadata: { ...metadata, op: "same", urn: `${metadata.urn}-same` } } } },
    ], false);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ marker: "~ ", name: "worker", status: "updated", done: true });
  });

  test("derives property changes and final stack outputs", () => {
    const metadata = {
      op: "update",
      urn: "urn:pulumi:prod::app::aws:s3/bucket:Bucket::assets",
      type: "aws:s3/bucket:Bucket",
      old: { inputs: { versioning: false } },
      new: { inputs: { versioning: true } },
      detailedDiff: { versioning: { diffKind: "update", inputDiff: true } },
    };
    expect(propertyChanges(metadata)).toEqual([
      { path: "versioning", kind: "update", oldValue: false, newValue: true },
    ]);
    expect(stackOutputsFromEvents([{
      sequence: 9,
      kind: "resOutputs",
      severity: null,
      payload: { resOutputsEvent: { metadata: { op: "same", type: "pulumi:pulumi:Stack", new: { outputs: { endpoint: "https://example.test" } } } } },
    }])).toEqual({ endpoint: "https://example.test" });
  });

  test("formats property changes as an inline JSON diff", () => {
    expect(jsonDiffLines([
      { path: "length", kind: "update", oldValue: 24, newValue: 32 },
      { path: "overrideSpecial", kind: "delete", oldValue: "_%", newValue: undefined },
    ])).toEqual([
      { kind: "context", text: "length" },
      { kind: "delete", text: "24" },
      { kind: "add", text: "32" },
      { kind: "context", text: "overrideSpecial" },
      { kind: "delete", text: '"_%"' },
    ]);
  });

  test("derives resource changes from engine event metadata", () => {
    const events: RunEvent[] = [{
      sequence: 3,
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
    }];
    expect(resourceChanges(events)[0]).toMatchObject({
      sequence: 3,
      op: "create",
      type: "aws:s3/bucket:Bucket",
      name: "assets",
    });
  });

  test("formats live and historical durations", () => {
    expect(formatRunDuration("2026-07-19T10:00:00Z", "2026-07-19T10:02:14Z")).toBe("2m 14s");
    expect(formatRunDuration("2026-07-19T10:00:00Z", null, Date.parse("2026-07-19T10:00:09Z"))).toBe("9s");
  });
});
