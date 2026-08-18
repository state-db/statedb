// Recursive structural diff of two JSON-ish values. Pure and Pulumi-agnostic.

export type DiffProperty = { path: string; oldValue: unknown; newValue: unknown };
export type JsonDiffLine = { depth: number; kind: "add" | "delete" | "update" | "same" | "fold"; text: string };

export type ValueDiff =
  | { op: "add"; value: unknown }
  | { op: "delete"; value: unknown }
  | { op: "same"; value: unknown }
  | { op: "update"; oldValue: unknown; newValue: unknown }
  | { op: "object"; entries: { key: string; diff: ValueDiff }[] }
  | { op: "array"; entries: { key: string; diff: ValueDiff }[] };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// Not JSON.stringify equality: that's key-order-sensitive, and JSON round-tripping a backend can reorder keys.
export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (isRecord(a) && isRecord(b)) {
    const aKeys = Object.keys(a);
    const bKeys = Object.keys(b);
    return aKeys.length === bKeys.length && aKeys.every((key) => Object.prototype.hasOwnProperty.call(b, key) && deepEqual(a[key], b[key]));
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((value, index) => deepEqual(value, b[index]));
  }
  return false;
}

export function diffValue(oldValue: unknown, newValue: unknown): ValueDiff {
  if (oldValue === undefined) return { op: "add", value: newValue };
  if (newValue === undefined) return { op: "delete", value: oldValue };
  if (isRecord(oldValue) && isRecord(newValue)) {
    const keys = Array.from(new Set([...Object.keys(oldValue), ...Object.keys(newValue)]));
    const entries = keys.map((key) => ({ key, diff: diffValue(oldValue[key], newValue[key]) }));
    return entries.every((entry) => entry.diff.op === "same") ? { op: "same", value: newValue } : { op: "object", entries };
  }
  if (Array.isArray(oldValue) && Array.isArray(newValue)) {
    const entries = diffArrayEntries(oldValue, newValue);
    return entries.every((entry) => entry.diff.op === "same") ? { op: "same", value: newValue } : { op: "array", entries };
  }
  return deepEqual(oldValue, newValue) ? { op: "same", value: newValue } : { op: "update", oldValue, newValue };
}

// Matches elements by content via LCS (not index) so a reorder or mid-array insert doesn't misread as a cascade of updates.
export function diffArrayEntries(oldArray: unknown[], newArray: unknown[]): { key: string; diff: ValueDiff }[] {
  const script = lcsEditScript(oldArray, newArray);
  const entries: { key: string; diff: ValueDiff }[] = [];
  let index = 0;
  while (index < script.length) {
    const op = script[index];
    if (op.type === "same") {
      entries.push({ key: String(op.newIndex), diff: { op: "same", value: newArray[op.newIndex] } });
      index++;
      continue;
    }
    const deletes: number[] = [];
    while (index < script.length && script[index].type === "delete") {
      deletes.push((script[index] as { type: "delete"; oldIndex: number }).oldIndex);
      index++;
    }
    const adds: number[] = [];
    while (index < script.length && script[index].type === "add") {
      adds.push((script[index] as { type: "add"; newIndex: number }).newIndex);
      index++;
    }
    const paired = Math.min(deletes.length, adds.length);
    for (let i = 0; i < paired; i++) entries.push({ key: String(adds[i]), diff: diffValue(oldArray[deletes[i]], newArray[adds[i]]) });
    for (let i = paired; i < deletes.length; i++) entries.push({ key: String(deletes[i]), diff: { op: "delete", value: oldArray[deletes[i]] } });
    for (let i = paired; i < adds.length; i++) entries.push({ key: String(adds[i]), diff: { op: "add", value: newArray[adds[i]] } });
  }
  return entries;
}

export type ArrayEditOp = { type: "same"; oldIndex: number; newIndex: number } | { type: "delete"; oldIndex: number } | { type: "add"; newIndex: number };

// Standard LCS edit-script backtrack, O(n*m); beyond this size fall back to a positional diff instead of building a huge table.
export const ARRAY_DIFF_LIMIT = 400;

export function lcsEditScript(oldArray: unknown[], newArray: unknown[]): ArrayEditOp[] {
  const n = oldArray.length;
  const m = newArray.length;
  if (n > ARRAY_DIFF_LIMIT || m > ARRAY_DIFF_LIMIT) {
    const length = Math.max(n, m);
    return Array.from({ length }, (_, i) =>
      i >= n ? { type: "add", newIndex: i } : i >= m ? { type: "delete", oldIndex: i } : { type: "same", oldIndex: i, newIndex: i });
  }
  // Precompute equality once per (i, j) pair; reused for both the table fill and the backtrack below.
  const equal: boolean[][] = oldArray.map((oldItem) => newArray.map((newItem) => deepEqual(oldItem, newItem)));
  const table: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i][j] = equal[i][j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const ops: ArrayEditOp[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (equal[i][j]) {
      ops.push({ type: "same", oldIndex: i, newIndex: j });
      i++;
      j++;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      ops.push({ type: "delete", oldIndex: i });
      i++;
    } else {
      ops.push({ type: "add", newIndex: j });
      j++;
    }
  }
  while (i < n) ops.push({ type: "delete", oldIndex: i++ });
  while (j < m) ops.push({ type: "add", newIndex: j++ });
  return ops;
}

export function jsonDiffLines(properties: DiffProperty[]): JsonDiffLine[] {
  const lines: JsonDiffLine[] = [];
  for (const property of properties) {
    const diff = diffValue(property.oldValue, property.newValue);
    flattenDiff(diff, property.path, 0, lines);
  }
  return lines;
}

function flattenDiff(diff: ValueDiff, key: string | undefined, depth: number, lines: JsonDiffLine[]): void {
  const label = key === undefined ? "" : `${key}: `;
  switch (diff.op) {
    case "add":
      renderWhole(diff.value, key, depth, "add", lines);
      return;
    case "delete":
      renderWhole(diff.value, key, depth, "delete", lines);
      return;
    case "update":
      lines.push({ depth, kind: "update", text: `${label}${formatJsonValue(diff.oldValue)} → ${formatJsonValue(diff.newValue)}` });
      return;
    case "same":
      lines.push({ depth, kind: "same", text: `${label}${formatJsonValue(diff.value)}` });
      return;
    case "object":
      lines.push({ depth, kind: "same", text: `${label}{` });
      flattenChildren(diff.entries, depth + 1, lines);
      lines.push({ depth, kind: "same", text: "}" });
      return;
    case "array":
      lines.push({ depth, kind: "same", text: `${label}[` });
      flattenChildren(diff.entries, depth + 1, lines);
      lines.push({ depth, kind: "same", text: "]" });
      return;
  }
}

function flattenChildren(entries: { key: string; diff: ValueDiff }[], depth: number, lines: JsonDiffLine[]): void {
  let sameRun = 0;
  const flushRun = () => {
    if (sameRun > 0) lines.push({ depth, kind: "fold", text: `⋯ ${sameRun} unchanged` });
    sameRun = 0;
  };
  for (const { key, diff } of entries) {
    if (diff.op === "same") {
      sameRun++;
      continue;
    }
    flushRun();
    flattenDiff(diff, key, depth, lines);
  }
  flushRun();
}

// Renders a whole add/delete as indented JSON rather than one long line.
function renderWhole(value: unknown, key: string | undefined, depth: number, kind: "add" | "delete", lines: JsonDiffLine[]): void {
  const label = key === undefined ? "" : `${key}: `;
  if (isRecord(value)) {
    const entries = Object.entries(value);
    if (entries.length === 0) {
      lines.push({ depth, kind, text: `${label}{}` });
      return;
    }
    lines.push({ depth, kind, text: `${label}{` });
    for (const [childKey, childValue] of entries) renderWhole(childValue, childKey, depth + 1, kind, lines);
    lines.push({ depth, kind, text: "}" });
    return;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      lines.push({ depth, kind, text: `${label}[]` });
      return;
    }
    lines.push({ depth, kind, text: `${label}[` });
    value.forEach((item, index) => renderWhole(item, String(index), depth + 1, kind, lines));
    lines.push({ depth, kind, text: "]" });
    return;
  }
  lines.push({ depth, kind, text: `${label}${formatJsonValue(value)}` });
}

function formatJsonValue(value: unknown): string {
  if (value === undefined) return "∅";
  if (value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}
