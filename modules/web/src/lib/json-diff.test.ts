import { describe, expect, test } from "bun:test";

import { type ArrayEditOp, decodeJsonContainer, deepEqual, diffValue, ENCODED_JSON_LIMIT, type JsonDiffLine, jsonDiffLines, lcsEditScript, type ValueDiff } from "@/lib/json-diff";

describe("json-diff", () => {
  test("formats scalar property changes as single-line diffs", () => {
    expect(jsonDiffLines([
      { path: "length", oldValue: 24, newValue: 32 },
      { path: "overrideSpecial", oldValue: "_%", newValue: undefined },
    ])).toEqual([
      { depth: 0, kind: "update", text: "length: 24 → 32" },
      { depth: 0, kind: "delete", text: 'overrideSpecial: "_%"' },
    ]);
  });

  test("recurses into nested objects, folding unchanged siblings", () => {
    expect(jsonDiffLines([
      {
        path: "keepers",
        oldValue: { rotation: 1, environment: "prod", region: "us-east-1" },
        newValue: { rotation: 2, environment: "prod", region: "us-east-1" },
      },
    ])).toEqual([
      { depth: 0, kind: "same", text: "keepers: {" },
      { depth: 1, kind: "update", text: "rotation: 1 → 2" },
      { depth: 1, kind: "fold", text: "⋯ 2 unchanged" },
      { depth: 0, kind: "same", text: "}" },
    ]);
  });

  test("renders a whole add as indented JSON rather than one long blob", () => {
    expect(jsonDiffLines([
      { path: "keepers", oldValue: undefined, newValue: { rotation: 1, environment: "prod" } },
    ])).toEqual([
      { depth: 0, kind: "add", text: "keepers: {" },
      { depth: 1, kind: "add", text: "rotation: 1" },
      { depth: 1, kind: "add", text: 'environment: "prod"' },
      { depth: 0, kind: "add", text: "}" },
    ]);
  });

  test("array diff matches elements by content, so a reorder shows only the moved element", () => {
    expect(jsonDiffLines([
      { path: "tags", oldValue: ["a", "b", "c"], newValue: ["b", "c", "a"] },
    ])).toEqual([
      { depth: 0, kind: "same", text: "tags: [" },
      { depth: 1, kind: "delete", text: '0: "a"' },
      { depth: 1, kind: "fold", text: "⋯ 2 unchanged" },
      { depth: 1, kind: "add", text: '2: "a"' },
      { depth: 0, kind: "same", text: "]" },
    ]);
  });

  test("array diff shows a mid-array insert as one addition, not a cascade of shifted updates", () => {
    expect(jsonDiffLines([
      { path: "list", oldValue: ["a", "b", "c"], newValue: ["a", "x", "b", "c"] },
    ])).toEqual([
      { depth: 0, kind: "same", text: "list: [" },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "add", text: '1: "x"' },
      { depth: 1, kind: "fold", text: "⋯ 2 unchanged" },
      { depth: 0, kind: "same", text: "]" },
    ]);
  });

  test("array diff recurses into a changed element of an array of objects", () => {
    expect(jsonDiffLines([
      {
        path: "ingress",
        oldValue: [{ protocol: "tcp", port: 22 }, { protocol: "tcp", port: 443 }],
        newValue: [{ protocol: "tcp", port: 22 }, { protocol: "tcp", port: 8443 }],
      },
    ])).toEqual([
      { depth: 0, kind: "same", text: "ingress: [" },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "same", text: "1: {" },
      { depth: 2, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 2, kind: "update", text: "port: 443 → 8443" },
      { depth: 1, kind: "same", text: "}" },
      { depth: 0, kind: "same", text: "]" },
    ]);
  });

  test("treats objects with reordered keys as equal, not a spurious update", () => {
    expect(jsonDiffLines([
      { path: "keepers", oldValue: { rotation: 1, environment: "prod" }, newValue: { environment: "prod", rotation: 1 } },
    ])).toEqual([
      { depth: 0, kind: "same", text: 'keepers: {"environment":"prod","rotation":1}' },
    ]);
  });

  test("array element identity is deep-equality, not JSON.stringify, so a reordered-key match still folds", () => {
    expect(jsonDiffLines([
      { path: "keys", oldValue: [{ a: 1, b: 2 }], newValue: [{ b: 2, a: 1 }] },
    ])).toEqual([
      { depth: 0, kind: "same", text: 'keys: [{"b":2,"a":1}]' },
    ]);
  });

  // ── Correctness invariants ────────────────────────────────────────────
  //
  // The line-formatting tests above prove the renderer produces the shape we
  // designed. They don't prove the underlying diff is *correct* — they were
  // hand-traced against the same algorithm they're checking, so a shared
  // misunderstanding wouldn't show up. These tests check properties that
  // must hold for ANY correct diff, verified against randomized input rather
  // than hand-picked examples, and (for the LCS specifically) cross-checked
  // against an independently-written brute-force reference rather than the
  // DP implementation under test.

  function mulberry32(seed: number): () => number {
    let state = seed;
    return () => {
      state |= 0;
      state = (state + 0x6d2b79f5) | 0;
      let t = Math.imul(state ^ (state >>> 15), 1 | state);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function randomScalar(rng: () => number): unknown {
    const kind = Math.floor(rng() * 4);
    if (kind === 0) return Math.floor(rng() * 10);
    if (kind === 1) return ["a", "b", "c", "d"][Math.floor(rng() * 4)];
    if (kind === 2) return rng() < 0.5;
    return null;
  }

  function randomValue(rng: () => number, depth: number): unknown {
    const kind = depth <= 0 ? 0 : Math.floor(rng() * 4);
    if (kind === 0) return randomScalar(rng);
    if (kind === 1) return Array.from({ length: Math.floor(rng() * 4) }, () => randomValue(rng, depth - 1));
    const keys = ["a", "b", "c", "d"].slice(0, Math.floor(rng() * 4) + 1);
    const obj: Record<string, unknown> = {};
    for (const key of keys) obj[key] = randomValue(rng, depth - 1);
    return obj;
  }

  // Produces a plausible "new" version of a value: some keys added/removed/
  // changed, some array elements reordered/inserted/removed — the same kind
  // of edit a real Pulumi update actually makes, rather than two unrelated
  // random trees that happen to share no structure.
  function mutate(rng: () => number, value: unknown, depth: number): unknown {
    if (rng() < 0.1) return randomValue(rng, depth);
    if (Array.isArray(value)) {
      const source: unknown[] = value;
      const next: unknown[] = source.map((item) => (rng() < 0.4 ? mutate(rng, item, depth - 1) : item));
      if (rng() < 0.3 && next.length > 0) next.splice(Math.floor(rng() * next.length), 1);
      if (rng() < 0.3) next.splice(Math.floor(rng() * (next.length + 1)), 0, randomValue(rng, depth - 1));
      if (rng() < 0.3 && next.length > 1) {
        const i = Math.floor(rng() * next.length);
        const j = Math.floor(rng() * next.length);
        const temp = next[i];
        next[i] = next[j];
        next[j] = temp;
      }
      return next;
    }
    if (value !== null && typeof value === "object") {
      const next: Record<string, unknown> = { ...(value as Record<string, unknown>) };
      for (const key of Object.keys(next)) {
        const roll = rng();
        if (roll < 0.25) delete next[key];
        else if (roll < 0.5) next[key] = mutate(rng, next[key], depth - 1);
      }
      if (rng() < 0.3) next[["a", "b", "c", "d", "e"][Math.floor(rng() * 5)]] = randomValue(rng, depth - 1);
      return next;
    }
    return rng() < 0.5 ? randomValue(rng, depth) : value;
  }

  // Independently-written from diffValue: reconstructs "new" (or "old") by
  // walking the diff tree, rather than reusing any of the diffing logic. If
  // this always reconstructs the real newValue/oldValue, the diff is a sound
  // (lossless) representation of the transformation, regardless of whether
  // the specific edit choices it made were minimal.
  function materialize(diff: ValueDiff, side: "old" | "new"): unknown {
    switch (diff.op) {
      case "add":
        return side === "new" ? diff.value : undefined;
      case "delete":
        return side === "old" ? diff.value : undefined;
      case "same":
        return diff.value;
      case "update":
        return side === "new" ? diff.newValue : diff.oldValue;
      case "encoded":
        return side === "new" ? diff.newValue : diff.oldValue;
      case "object": {
        const result: Record<string, unknown> = {};
        for (const { key, diff: child } of diff.entries) {
          if (side === "new" && child.op === "delete") continue;
          if (side === "old" && child.op === "add") continue;
          result[key] = materialize(child, side);
        }
        return result;
      }
      case "array": {
        const result: unknown[] = [];
        for (const { diff: child } of diff.entries) {
          if (side === "new" && child.op === "delete") continue;
          if (side === "old" && child.op === "add") continue;
          result.push(materialize(child, side));
        }
        return result;
      }
    }
  }

  test("round-trip: the diff always reconstructs both the old and new value exactly, across randomized JSON trees", () => {
    const rng = mulberry32(42);
    for (let trial = 0; trial < 300; trial++) {
      const oldValue = randomValue(rng, 3);
      const newValue = mutate(rng, oldValue, 3);
      const diff = diffValue(oldValue, newValue);
      expect(materialize(diff, "new")).toEqual(newValue);
      expect(materialize(diff, "old")).toEqual(oldValue);
    }
  });

  // Exhaustive: enumerates every subsequence of the shorter array (valid up
  // to ~12 elements) and returns the length of the longest one that also
  // embeds in the longer array in order. This is algorithmically unrelated
  // to the DP table in lcsEditScript, so it can't share a bug with it.
  function isSubsequence(candidate: unknown[], array: unknown[]): boolean {
    let index = 0;
    for (const item of array) {
      if (index < candidate.length && deepEqual(candidate[index], item)) index++;
    }
    return index === candidate.length;
  }

  function bruteForceLcsLength(a: unknown[], b: unknown[]): number {
    const shorter = a.length <= b.length ? a : b;
    const longer = a.length <= b.length ? b : a;
    let best = 0;
    for (let mask = 0; mask < 1 << shorter.length; mask++) {
      const bitCount = mask.toString(2).replace(/0/g, "").length;
      if (bitCount <= best) continue;
      const candidate: unknown[] = [];
      for (let bit = 0; bit < shorter.length; bit++) if (mask & (1 << bit)) candidate.push(shorter[bit]);
      if (isSubsequence(candidate, longer)) best = candidate.length;
    }
    return best;
  }

  test("LCS match count equals an independently brute-forced optimum, across randomized short arrays", () => {
    const rng = mulberry32(7);
    for (let trial = 0; trial < 200; trial++) {
      const oldArray = Array.from({ length: Math.floor(rng() * 8) }, () => randomScalar(rng));
      const newArray = Array.from({ length: Math.floor(rng() * 8) }, () => randomScalar(rng));
      const matched = lcsEditScript(oldArray, newArray).filter((op) => op.type === "same").length;
      expect(matched).toBe(bruteForceLcsLength(oldArray, newArray));
    }
  });

  test("LCS matches preserve relative order in both arrays (a valid common-subsequence alignment)", () => {
    const rng = mulberry32(99);
    for (let trial = 0; trial < 200; trial++) {
      const oldArray = Array.from({ length: Math.floor(rng() * 9) }, () => randomScalar(rng));
      const newArray = Array.from({ length: Math.floor(rng() * 9) }, () => randomScalar(rng));
      const matches = lcsEditScript(oldArray, newArray).filter((op): op is Extract<ArrayEditOp, { type: "same" }> => op.type === "same");
      for (let k = 1; k < matches.length; k++) {
        expect(matches[k].oldIndex).toBeGreaterThan(matches[k - 1].oldIndex);
        expect(matches[k].newIndex).toBeGreaterThan(matches[k - 1].newIndex);
      }
    }
  });
  // ── JSON encoded as a string ──────────────────────────────────────────
  //
  // Mirrors the Pulumi CLI's printEncodedValueDiff: decode only when both
  // sides parse fully to an object or array and differ semantically. Every
  // case that is not provably safe falls back to the raw string update.

  const policy = (actions: string[]) => JSON.stringify({ Version: "2012-10-17", Statement: [{ Sid: "Read", Effect: "Allow", Action: actions, Resource: "*" }] });
  const rawUpdate = (path: string, oldValue: string, newValue: string): JsonDiffLine[] =>
    [{ depth: 0, kind: "update", text: `${path}: ${JSON.stringify(oldValue)} → ${JSON.stringify(newValue)}` }];

  test("decodes a JSON string update into a structural diff marked (json)", () => {
    expect(jsonDiffLines([
      { path: "policy", oldValue: policy(["s3:Get*"]), newValue: policy(["s3:Get*", "s3:List*"]) },
    ])).toEqual([
      { depth: 0, kind: "same", text: "policy: (json) {", encoded: true },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "same", text: "Statement: [" },
      { depth: 2, kind: "same", text: "0: {" },
      { depth: 3, kind: "fold", text: "⋯ 2 unchanged" },
      { depth: 3, kind: "same", text: "Action: [" },
      { depth: 4, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 4, kind: "add", text: '1: "s3:List*"' },
      { depth: 3, kind: "same", text: "]" },
      { depth: 3, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 2, kind: "same", text: "}" },
      { depth: 1, kind: "same", text: "]" },
      { depth: 0, kind: "same", text: "}" },
    ]);
  });

  test("decodes JSON strings nested inside an object property, with surrounding whitespace", () => {
    expect(jsonDiffLines([
      { path: "keepers", oldValue: { plain: "x", doc: '\n  {"a": 1, "b": 2}\n' }, newValue: { plain: "x", doc: '{\n  "a": 1,\n  "b": 3\n}' } },
    ])).toEqual([
      { depth: 0, kind: "same", text: "keepers: {" },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "same", text: "doc: (json) {", encoded: true },
      { depth: 2, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 2, kind: "update", text: "b: 2 → 3" },
      { depth: 1, kind: "same", text: "}" },
      { depth: 0, kind: "same", text: "}" },
    ]);
  });

  test("decodes a top-level JSON array string", () => {
    expect(jsonDiffLines([{ path: "list", oldValue: '["a","b"]', newValue: '["a","c"]' }])).toEqual([
      { depth: 0, kind: "same", text: "list: (json) [", encoded: true },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "update", text: '1: "b" → "c"' },
      { depth: 0, kind: "same", text: "]" },
    ]);
  });

  test("a formatting-only change stays a raw update, never 'no change'", () => {
    const oldValue = '{"family":"api","cpu":256}';
    const newValue = '{\n    "cpu": 256,\n    "family": "api"\n}';
    expect(jsonDiffLines([{ path: "def", oldValue, newValue }])).toEqual(rawUpdate("def", oldValue, newValue));
  });

  test("JSON scalars encoded as strings stay raw updates", () => {
    for (const [oldValue, newValue] of [["42", "43"], ["true", "false"], ["null", '"x"'], ['"a"', '"b"']]) {
      expect(jsonDiffLines([{ path: "v", oldValue, newValue }])).toEqual(rawUpdate("v", oldValue, newValue));
    }
  });

  test("invalid, truncated, or trailing-content strings stay raw updates", () => {
    const cases: [string, string][] = [
      ["{this is not json", "{this is not json either"],
      ['{"a":1}', '{"a":'],
      ['{"a":1}', '{"a":2} trailing'],
      ['{"a":1}', '{"a":2}{"a":3}'],
      ["{'a':1}", "{'a':2}"],
      ["[secret]", '{"password":"x"}'],
    ];
    for (const [oldValue, newValue] of cases) {
      expect(jsonDiffLines([{ path: "v", oldValue, newValue }])).toEqual(rawUpdate("v", oldValue, newValue));
    }
  });

  test("decodes only when both sides are JSON containers", () => {
    expect(jsonDiffLines([{ path: "v", oldValue: "plain", newValue: '{"a":1}' }])).toEqual(rawUpdate("v", "plain", '{"a":1}'));
    expect(jsonDiffLines([{ path: "v", oldValue: '{"a":1}', newValue: "42" }])).toEqual(rawUpdate("v", '{"a":1}', "42"));
  });

  test("an object-to-array change gains nothing from decoding and stays raw", () => {
    expect(jsonDiffLines([{ path: "v", oldValue: '{"a":1}', newValue: "[1]" }])).toEqual(rawUpdate("v", '{"a":1}', "[1]"));
  });

  test("numbers a double cannot hold exactly keep the raw update, so no value is shown rounded", () => {
    const cases: [string, string][] = [
      ['{"account":123456789012345678,"x":1}', '{"account":123456789012345678,"x":2}'],
      ['{"ratio":0.10000000000000000555,"x":1}', '{"ratio":0.10000000000000000555,"x":2}'],
      ['{"big":1e400,"x":1}', '{"big":1e400,"x":2}'],
    ];
    for (const [oldValue, newValue] of cases) {
      expect(jsonDiffLines([{ path: "v", oldValue, newValue }])).toEqual(rawUpdate("v", oldValue, newValue));
    }
  });

  test("numbers within double precision still decode, including exponents and long fractions", () => {
    const lines = jsonDiffLines([{ path: "v", oldValue: '{"a":123456789012345,"b":1.5e-7,"c":-0.000123456789012345}', newValue: '{"a":123456789012345,"b":1.5e-7,"c":1}' }]);
    expect(lines[0]).toEqual({ depth: 0, kind: "same", text: "v: (json) {", encoded: true });
  });

  test("duplicate keys keep the raw update, since JSON.parse silently drops all but the last", () => {
    const oldValue = '{"Effect":"Deny","Effect":"Allow","x":1}';
    const newValue = '{"Effect":"Allow","x":2}';
    expect(jsonDiffLines([{ path: "v", oldValue, newValue }])).toEqual(rawUpdate("v", oldValue, newValue));
  });

  test("a __proto__ key on one side diffs as an add, not against Object.prototype", () => {
    expect(jsonDiffLines([{ path: "v", oldValue: '{"a":1}', newValue: '{"a":1,"__proto__":{"b":2}}' }])).toEqual([
      { depth: 0, kind: "same", text: "v: (json) {", encoded: true },
      { depth: 1, kind: "fold", text: "⋯ 1 unchanged" },
      { depth: 1, kind: "add", text: "__proto__: {" },
      { depth: 2, kind: "add", text: "b: 2" },
      { depth: 1, kind: "add", text: "}" },
      { depth: 0, kind: "same", text: "}" },
    ]);
  });

  test("decodes one level only: a JSON string inside decoded JSON stays a string", () => {
    const inner = (n: number) => JSON.stringify({ n });
    expect(jsonDiffLines([{ path: "v", oldValue: JSON.stringify({ inner: inner(1) }), newValue: JSON.stringify({ inner: inner(2) }) }])).toEqual([
      { depth: 0, kind: "same", text: "v: (json) {", encoded: true },
      { depth: 1, kind: "update", text: `inner: ${JSON.stringify(inner(1))} → ${JSON.stringify(inner(2))}` },
      { depth: 0, kind: "same", text: "}" },
    ]);
  });

  test("strings over the decode size limit stay raw updates", () => {
    const pad = "x".repeat(ENCODED_JSON_LIMIT);
    const oldValue = JSON.stringify({ pad, v: 1 });
    const newValue = JSON.stringify({ pad, v: 2 });
    expect(jsonDiffLines([{ path: "v", oldValue, newValue }])).toEqual(rawUpdate("v", oldValue, newValue));
  });

  test("renders an added or deleted JSON string as an indented (json) tree", () => {
    expect(jsonDiffLines([
      { path: "policy", oldValue: undefined, newValue: '{"Effect":"Allow","Action":["s3:Get*"]}' },
      { path: "old", oldValue: "[1]", newValue: undefined },
    ])).toEqual([
      { depth: 0, kind: "add", text: "policy: (json) {", encoded: true },
      { depth: 1, kind: "add", text: 'Effect: "Allow"' },
      { depth: 1, kind: "add", text: "Action: [" },
      { depth: 2, kind: "add", text: '0: "s3:Get*"' },
      { depth: 1, kind: "add", text: "]" },
      { depth: 0, kind: "add", text: "}" },
      { depth: 0, kind: "delete", text: "old: (json) [", encoded: true },
      { depth: 1, kind: "delete", text: "0: 1" },
      { depth: 0, kind: "delete", text: "]" },
    ]);
  });

  test("added JSON strings that fail any guard render as the raw string", () => {
    expect(jsonDiffLines([
      { path: "a", oldValue: undefined, newValue: "42" },
      { path: "b", oldValue: undefined, newValue: '{"n":123456789012345678}' },
      { path: "c", oldValue: undefined, newValue: "{}" },
    ])).toEqual([
      { depth: 0, kind: "add", text: 'a: "42"' },
      { depth: 0, kind: "add", text: 'b: "{\\"n\\":123456789012345678}"' },
      { depth: 0, kind: "add", text: 'c: "{}"' },
    ]);
  });

  test("decodeStrings: false reproduces the raw rendering exactly", () => {
    const properties = [
      { path: "policy", oldValue: policy(["s3:Get*"]), newValue: policy(["s3:List*"]) },
      { path: "created", oldValue: undefined, newValue: '{"a":1}' },
    ];
    expect(jsonDiffLines(properties, { decodeStrings: false })).toEqual([
      ...rawUpdate("policy", policy(["s3:Get*"]), policy(["s3:List*"])),
      { depth: 0, kind: "add", text: 'created: "{\\"a\\":1}"' },
    ]);
  });

  test("the encoded diff keeps both raw strings", () => {
    const diff = diffValue('{"a":1}', '{"a":2}');
    expect(diff).toMatchObject({ op: "encoded", format: "json", oldValue: '{"a":1}', newValue: '{"a":2}' });
  });

  test("decodeJsonContainer accepts only fully-parsed, exactly-representable, duplicate-free containers", () => {
    expect(decodeJsonContainer(' {"a":[1,2]} ')).toEqual({ ok: true, value: { a: [1, 2] } });
    expect(decodeJsonContainer("[]")).toEqual({ ok: true, value: [] });
    for (const rejected of ["", "42", '"{}"', "null", "{", '{"a":1,"a":1}', '{"a":1,"\\u0061":2}', '[{"k":1,"k":2}]', '{"n":9007199254740993}']) {
      expect(decodeJsonContainer(rejected)).toEqual({ ok: false });
    }
  });

  test("duplicate detection is per object, so the same key in sibling objects still decodes", () => {
    expect(decodeJsonContainer('[{"k":1},{"k":2}]')).toEqual({ ok: true, value: [{ k: 1 }, { k: 2 }] });
    expect(decodeJsonContainer('{"k":{"k":1}}')).toEqual({ ok: true, value: { k: { k: 1 } } });
    expect(decodeJsonContainer('{"a":"\\"k\\":","k":1}')).toEqual({ ok: true, value: { a: '"k":', k: 1 } });
  });

  function randomIndent(rng: () => number): number | undefined {
    return [undefined, 2, 4][Math.floor(rng() * 3)];
  }

  test("round-trip: a decoded diff always reconstructs JSON.parse of both strings, across randomized trees and formatting", () => {
    const rng = mulberry32(2026);
    let decoded = 0;
    for (let trial = 0; trial < 300; trial++) {
      const oldTree = { root: randomValue(rng, 3) };
      const newTree = mutate(rng, oldTree, 3);
      if (newTree === null || typeof newTree !== "object") continue;
      const oldValue = JSON.stringify(oldTree, null, randomIndent(rng));
      const newValue = JSON.stringify(newTree, null, randomIndent(rng));
      const diff = diffValue(oldValue, newValue);
      expect(materialize(diff, "old")).toBe(oldValue);
      expect(materialize(diff, "new")).toBe(newValue);
      if (diff.op === "encoded") {
        decoded++;
        expect(materialize(diff.diff, "old")).toEqual(JSON.parse(oldValue));
        expect(materialize(diff.diff, "new")).toEqual(JSON.parse(newValue));
      } else if (oldValue !== newValue) {
        expect(diff.op).toBe("update");
        if (Array.isArray(newTree) === Array.isArray(oldTree)) expect(deepEqual(JSON.parse(oldValue), JSON.parse(newValue))).toBe(true);
      }
    }
    expect(decoded).toBeGreaterThan(50);
  });
});
