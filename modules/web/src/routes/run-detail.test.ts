import { describe, expect, test } from "bun:test";

import type { RunEvent } from "@/lib/api";
import {
  changeSummary,
  formatEventLine,
  formatRunDuration,
  maskConfig,
  propertyChanges,
  resourceChanges,
  resourceRows,
  stackOutputsDiff,
  stackOutputsFromEvents,
  stripAnsi,
} from "@/routes/run-detail";

describe("run detail presentation", () => {
  test("strips terminal escape sequences without changing whitespace", () => {
    expect(stripAnsi("\u001b[31merror\u001b[0m\n  detail")).toBe("error\n  detail");
  });

  test("strips pulumi colorization tokens from diagnostics", () => {
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

  test("times a resource operation from its pre-event to its completion event and surfaces changed keys", () => {
    const metadata = {
      op: "update",
      urn: "urn:pulumi:prod::app::aws:lambda/function:Function::worker",
      type: "aws:lambda/function:Function",
      new: { parent: "" },
      diffs: ["environment", "timeout"],
    };
    const rows = resourceRows([
      { sequence: 1, kind: "resourcePre", severity: null, payload: { timestamp: 1000, resourcePreEvent: { metadata } } },
      { sequence: 2, kind: "resOutputs", severity: null, payload: { timestamp: 1006, resOutputsEvent: { metadata } } },
    ], false);
    expect(rows[0]).toMatchObject({ seconds: 6, diffs: ["environment", "timeout"] });
  });

  test("diffs stack outputs against the prior run, treating a first-ever run as unchanged", () => {
    const outputsEvent = (old: Record<string, unknown>, current: Record<string, unknown>): RunEvent => ({
      sequence: 1,
      kind: "resOutputs",
      severity: null,
      payload: { resOutputsEvent: { metadata: { type: "pulumi:pulumi:Stack", old: { outputs: old }, new: { outputs: current } } } },
    });
    expect(stackOutputsDiff([outputsEvent({}, { endpoint: "https://a.test" })]))
      .toEqual([{ key: "endpoint", kind: "same", oldValue: undefined, newValue: "https://a.test" }]);
    expect(stackOutputsDiff([outputsEvent({ endpoint: "https://a.test" }, { endpoint: "https://b.test" })]))
      .toEqual([{ key: "endpoint", kind: "update", oldValue: "https://a.test", newValue: "https://b.test" }]);
    expect(stackOutputsDiff([outputsEvent({ endpoint: "https://a.test" }, { endpoint: "https://a.test", region: "us-east-1" })]))
      .toEqual([
        { key: "endpoint", kind: "same", oldValue: "https://a.test", newValue: "https://a.test" },
        { key: "region", kind: "add", oldValue: undefined, newValue: "us-east-1" },
      ]);
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
      { path: "versioning", kind: "update", oldValue: false, newValue: true, internal: false },
    ]);
    expect(stackOutputsFromEvents([{
      sequence: 9,
      kind: "resOutputs",
      severity: null,
      payload: { resOutputsEvent: { metadata: { op: "same", type: "pulumi:pulumi:Stack", new: { outputs: { endpoint: "https://example.test" } } } } },
    }])).toEqual({ endpoint: "https://example.test" });
  });

  test("masks the engine's unknown-value sentinel in preview outputs", () => {
    expect(stackOutputsFromEvents([{
      sequence: 9,
      kind: "resOutputs",
      severity: null,
      payload: { resOutputsEvent: { metadata: { type: "pulumi:pulumi:Stack", new: { outputs: {
        token: "04da6b54-80e4-46f7-96ec-b56ff0331ba9",
        nested: { report: "04da6b54-80e4-46f7-96ec-b56ff0331ba9" },
        plain: "kept",
      } } } } },
    }])).toEqual({ token: "[unknown]", nested: { report: "[unknown]" }, plain: "kept" });
  });

  test("flags Pulumi's dunder-prefixed bookkeeping paths as internal", () => {
    const metadata = {
      op: "create",
      urn: "urn:pulumi:prod::app::pulumi:providers:tls::tls-provider",
      type: "pulumi:providers:tls",
      old: {},
      new: { inputs: { __internal: {}, version: "5.4.0" } },
    };
    expect(propertyChanges(metadata).map((p) => ({ path: p.path, internal: p.internal }))).toEqual([
      { path: "__internal", internal: true },
      { path: "version", internal: false },
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

  test("counts replacements without folding them into create or delete", () => {
    const run = { summary: { resourceChanges: { replace: 2, same: 3 } } } as never;
    expect(changeSummary(run)).toEqual({ created: 0, updated: 0, deleted: 0, replaced: 2, unchanged: 3 });
  });

  test("derives replacement count from create-replacement / delete-replaced substeps", () => {
    const run = { summary: { resourceChanges: { "create-replacement": 1, "delete-replaced": 1, create: 1 } } } as never;
    expect(changeSummary(run)).toMatchObject({ created: 1, replaced: 1 });
  });
});
