import { describe, expect, test } from "bun:test";

import { PULUMI_SECRET_SIG } from "@/components/console/SecretBadge";
import {
  buildPropertyRows,
  buildResourceRows,
  buildResourceTree,
  containsSecret,
  calculateChangeSummary,
  defaultTreeBranchOpen,
  filterResourceRows,
  filterAndSortResources,
  maskSecrets,
  lastUpdateActorLabel,
  pageResources,
  propertyPriorityRank,
  resourceTreeIndentRem,
  resourceName,
  resourceType,
  serializeOutput,
  stackOutputs,
  formatDuration,
} from "@/routes/stack-detail";

describe("stack resource presentation", () => {
  test("derives the resource name and type from a URN", () => {
    const resource = {
      urn: "urn:pulumi:prod::networking::aws:s3/bucket:Bucket::site-assets",
    };

    expect(resourceName(resource)).toBe("site-assets");
    expect(resourceType(resource)).toBe("aws:s3/bucket:Bucket");
  });

  test("masks nested secrets without leaking ciphertext", () => {
    const value = {
      public: "visible",
      nested: {
        [PULUMI_SECRET_SIG]: "1",
        ciphertext: "do-not-render",
      },
    };

    expect(containsSecret(value)).toBe(true);
    expect(maskSecrets(value)).toEqual({ public: "visible", nested: "[secret]" });
    expect(JSON.stringify(maskSecrets(value))).not.toContain("do-not-render");
  });

  test("reads outputs from the synthetic stack resource", () => {
    const outputs = { endpoint: "https://example.test", replicas: 3 };
    expect(stackOutputs([
      { urn: "urn:pulumi:dev::app::random:RandomPet::pet", outputs: { id: "fox" } },
      {
        urn: "urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev",
        outputs,
      },
    ])).toEqual(outputs);
  });

  test("serializes object outputs as compact raw JSON for copying", () => {
    expect(serializeOutput({ endpoint: "api", ports: [80, 443] })).toBe(
      '{"endpoint":"api","ports":[80,443]}',
    );
    expect(serializeOutput(42)).toBe("42");
  });

  test("formats completed and in-progress update durations", () => {
    expect(formatDuration("2026-07-18T10:00:00Z", "2026-07-18T10:01:05Z")).toBe("1m 5s");
    expect(formatDuration("2026-07-18T10:00:00Z", null)).toBe("—");
  });

  test("does not count a replacement again as an update", () => {
    expect(
      calculateChangeSummary({
        "create-replacement": 1,
        replace: 1,
        "delete-replaced": 1,
      }),
    ).toEqual({ added: 1, changed: 0, deleted: 1 });
  });

  test("sorts resources by type before name", () => {
    const sorted = buildResourceRows([
      resource("urn:pulumi:dev::app::z:type:Thing::alpha"),
      resource("urn:pulumi:dev::app::a:type:Thing::zulu"),
      resource("urn:pulumi:dev::app::a:type:Thing::alpha"),
    ]);

    expect(sorted.map((row) => `${row.type} ${row.name}`)).toEqual([
      "a:type:Thing alpha",
      "a:type:Thing zulu",
      "z:type:Thing alpha",
    ]);
  });

  test("filters pre-sorted resource rows without resorting them", () => {
    const rows = buildResourceRows([
      resource("urn:pulumi:dev::app::z:type:Thing::alpha"),
      resource("urn:pulumi:dev::app::a:type:Thing::zulu"),
      resource("urn:pulumi:dev::app::a:type:Thing::alpha"),
    ]);

    expect(filterResourceRows(rows, "thing").map((row) => resourceName(row))).toEqual([
      "alpha",
      "zulu",
      "alpha",
    ]);
    expect(filterAndSortResources(rows.map((row) => row.resource), "zulu").map(resourceName)).toEqual([
      "zulu",
    ]);
  });

  test("paginates resource rows with clamped page bounds", () => {
    const rows = Array.from({ length: 7 }, (_, index) =>
      resource(`urn:pulumi:dev::app::pkg:index:Thing::thing-${index}`),
    );

    expect(pageResources(rows, 1, 3)).toMatchObject({
      page: 1,
      pageCount: 3,
      start: 0,
      end: 3,
    });
    expect(pageResources(rows, 99, 3)).toMatchObject({
      page: 3,
      start: 6,
      end: 7,
    });
  });

  test("tree pages include ancestors and auto-expand them", () => {
    const stack = resource("urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev");
    const parent = resource("urn:pulumi:dev::app::pkg:index:Component::parent", {
      parent: stack.urn,
      custom: false,
    });
    const child = resource("urn:pulumi:dev::app::pkg:index:Child::child", {
      parent: parent.urn,
    });

    const tree = buildResourceTree([child], [stack, parent, child], { includeAncestors: true });

    expect(tree.roots.map((row) => row.urn)).toEqual([stack.urn]);
    expect(tree.children.get(stack.urn)?.map((row) => row.urn)).toEqual([parent.urn]);
    expect(tree.children.get(parent.urn)?.map((row) => row.urn)).toEqual([child.urn]);
    expect(tree.autoExpanded.has(stack.urn)).toBe(true);
    expect(tree.autoExpanded.has(parent.urn)).toBe(true);
  });

  test("tree child counts describe rendered children only", () => {
    const stack = resource("urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev");
    const first = resource("urn:pulumi:dev::app::pkg:index:Child::first", { parent: stack.urn });
    const second = resource("urn:pulumi:dev::app::pkg:index:Child::second", { parent: stack.urn });

    const tree = buildResourceTree([first], [stack, first, second], { includeAncestors: true });

    expect(tree.childCounts.get(stack.urn)).toBe(1);
    expect(tree.children.get(stack.urn)?.map((row) => row.urn)).toEqual([first.urn]);
  });

  test("tree branches expand roots by default and collapse below them", () => {
    expect(defaultTreeBranchOpen(0)).toBe(true);
    expect(defaultTreeBranchOpen(1)).toBe(false);
    expect(defaultTreeBranchOpen(4)).toBe(false);
    expect(defaultTreeBranchOpen(5)).toBe(true);
    expect(defaultTreeBranchOpen(1, { expandAll: true })).toBe(true);
  });

  test("caps rendered tree indentation at deep nesting levels", () => {
    expect(resourceTreeIndentRem(-1)).toBe(0);
    expect(resourceTreeIndentRem(3)).toBe(3);
    expect(resourceTreeIndentRem(50)).toBe(7);
  });

  test("labels the last update actor from the richest available identity field", () => {
    expect(
      lastUpdateActorLabel({ displayName: "Deploy Bot", email: null, fallback: "opaque" }),
    ).toBe("Deploy Bot");
    expect(
      lastUpdateActorLabel({ displayName: null, email: "ops@example.test", fallback: "opaque" }),
    ).toBe("ops@example.test");
    expect(
      lastUpdateActorLabel({ displayName: null, email: null, fallback: "opaque" }),
    ).toBe("opaque");
  });

  test("ranks well-known identity keys ahead of everything else", () => {
    expect(propertyPriorityRank("arn")).toBeLessThan(propertyPriorityRank("region"));
    expect(propertyPriorityRank("Arn")).toBe(propertyPriorityRank("arn"));
    expect(propertyPriorityRank("somethingElse")).toBeGreaterThan(propertyPriorityRank("status"));
  });

  test("sinks double-underscore engine/provider bookkeeping keys to the bottom", () => {
    const rows = buildPropertyRows(
      { __internal: { rotation: "2" }, arn: "arn:aws:s3:::bucket", zzz: "last-alphabetically" },
      {},
    );

    expect(rows.map((row) => row.key)).toEqual(["arn", "zzz", "__internal"]);
  });

  test("merges inputs and outputs, sorting priority keys before the rest", () => {
    const rows = buildPropertyRows(
      { name: "web", timeout: 30 },
      { name: "web", arn: "arn:aws:s3:::web", timeout: 30 },
    );

    expect(rows.map((row) => row.key)).toEqual(["arn", "name", "timeout"]);
    expect(rows[0]).toMatchObject({ source: "output", differs: false });
    expect(rows[1]).toMatchObject({ source: "both", differs: false, value: "web" });
  });

  test("flags a property whose deployed output disagrees with its input", () => {
    const rows = buildPropertyRows({ replicas: 2 }, { replicas: 3 });

    expect(rows).toEqual([
      { key: "replicas", value: 3, source: "both", differs: true, inputValue: 2, outputValue: 3 },
    ]);
  });

  test("does not flag a provider's own type coercion of a passthrough value as a difference", () => {
    // random.RandomId's `keepers` schema is map[string]string: a number authored
    // in the program round-trips through the provider as its string form. That's
    // not a real disagreement worth flagging.
    const rows = buildPropertyRows(
      { keepers: { rotation: 2, active: true } },
      { keepers: { rotation: "2", active: "true" } },
    );

    expect(rows).toEqual([
      {
        key: "keepers",
        value: { rotation: "2", active: "true" },
        source: "both",
        differs: false,
        inputValue: { rotation: 2, active: true },
        outputValue: { rotation: "2", active: "true" },
      },
    ]);
  });

  test("treats a key present on only one side as input-only or output-only", () => {
    const rows = buildPropertyRows({ writeOnlyToken: "secret-config" }, { computedId: "abc123" });

    expect(rows.map((row) => ({ key: row.key, source: row.source }))).toEqual([
      { key: "computedId", source: "output" },
      { key: "writeOnlyToken", source: "input" },
    ]);
  });

  test("unfiltered trees do not force-expand descendants", () => {
    const stack = resource("urn:pulumi:dev::app::pulumi:pulumi:Stack::app-dev");
    const parent = resource("urn:pulumi:dev::app::pkg:index:Component::parent", {
      parent: stack.urn,
      custom: false,
    });
    const child = resource("urn:pulumi:dev::app::pkg:index:Child::child", {
      parent: parent.urn,
    });

    const tree = buildResourceTree([stack, parent, child], [stack, parent, child]);

    expect(tree.autoExpanded.size).toBe(0);
  });
});

function resource(urn: string, overrides: Record<string, unknown> = {}) {
  return { urn, ...overrides };
}
