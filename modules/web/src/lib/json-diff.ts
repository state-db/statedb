// Recursive structural diff of two JSON-ish values. Pure and Pulumi-agnostic.

export type DiffProperty = { path: string; oldValue: unknown; newValue: unknown };
export type JsonDiffLine = { depth: number; kind: "add" | "delete" | "update" | "same" | "fold"; text: string; encoded?: true };
export type DiffOptions = { decodeStrings?: boolean };

type ContainerDiff = { op: "object"; entries: { key: string; diff: ValueDiff }[] } | { op: "array"; entries: { key: string; diff: ValueDiff }[] };

export type ValueDiff =
  | { op: "add"; value: unknown }
  | { op: "delete"; value: unknown }
  | { op: "same"; value: unknown }
  | { op: "update"; oldValue: unknown; newValue: unknown }
  | { op: "encoded"; format: "json"; oldValue: string; newValue: string; diff: ContainerDiff }
  | ContainerDiff;

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

// Own properties only: a key like "__proto__" or "constructor" present on one side must read as absent on the other.
function ownValue(record: Record<string, unknown>, key: string): unknown {
  return Object.prototype.hasOwnProperty.call(record, key) ? record[key] : undefined;
}

export function diffValue(oldValue: unknown, newValue: unknown, options: DiffOptions = {}): ValueDiff {
  if (oldValue === undefined) return { op: "add", value: newValue };
  if (newValue === undefined) return { op: "delete", value: oldValue };
  if (isRecord(oldValue) && isRecord(newValue)) {
    const keys = Array.from(new Set([...Object.keys(oldValue), ...Object.keys(newValue)]));
    const entries = keys.map((key) => ({ key, diff: diffValue(ownValue(oldValue, key), ownValue(newValue, key), options) }));
    return entries.every((entry) => entry.diff.op === "same") ? { op: "same", value: newValue } : { op: "object", entries };
  }
  if (Array.isArray(oldValue) && Array.isArray(newValue)) {
    const entries = diffArrayEntries(oldValue, newValue, options);
    return entries.every((entry) => entry.diff.op === "same") ? { op: "same", value: newValue } : { op: "array", entries };
  }
  if (deepEqual(oldValue, newValue)) return { op: "same", value: newValue };
  if (options.decodeStrings !== false && typeof oldValue === "string" && typeof newValue === "string") {
    const encoded = diffEncodedJson(oldValue, newValue);
    if (encoded) return encoded;
  }
  return { op: "update", oldValue, newValue };
}

// Mirrors the Pulumi CLI's printEncodedValueDiff; decodes one level only.
function diffEncodedJson(oldValue: string, newValue: string): ValueDiff | undefined {
  const oldDecoded = decodeJsonContainer(oldValue);
  if (!oldDecoded.ok) return undefined;
  const newDecoded = decodeJsonContainer(newValue);
  if (!newDecoded.ok) return undefined;
  const diff = diffValue(oldDecoded.value, newDecoded.value, { decodeStrings: false });
  // "same" means a formatting-only change and "update" an object/array swap: the raw strings show those better.
  return diff.op === "object" || diff.op === "array" ? { op: "encoded", format: "json", oldValue, newValue, diff } : undefined;
}

// Decoding runs on every render, so larger strings stay raw.
export const ENCODED_JSON_LIMIT = 256 * 1024;

export type DecodedJson = { ok: true; value: Record<string, unknown> | unknown[] } | { ok: false };

export function decodeJsonContainer(text: string): DecodedJson {
  if (text.length > ENCODED_JSON_LIMIT) return { ok: false };
  const trimmed = text.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return { ok: false };
  let value: unknown;
  try {
    value = JSON.parse(trimmed);
  } catch {
    return { ok: false };
  }
  if (!isRecord(value) && !Array.isArray(value)) return { ok: false };
  return unsafeToDecode(trimmed) ? { ok: false } : { ok: true, value };
}

// Rendering recurses once per level, so deeper documents stay raw.
export const ENCODED_JSON_DEPTH_LIMIT = 64;

// Finds nesting too deep to render and what JSON.parse silently loses: duplicate keys (last wins) and inexact numbers.
function unsafeToDecode(json: string): boolean {
  const objectKeys: (Set<string> | undefined)[] = [];
  let index = 0;
  while (index < json.length) {
    const char = json[index];
    if (char === '"') {
      let end = index + 1;
      while (json[end] !== '"') end += json[end] === "\\" ? 2 : 1;
      let next = end + 1;
      while (" \t\n\r".includes(json[next])) next++;
      if (json[next] === ":") {
        const keys = objectKeys[objectKeys.length - 1];
        const key = JSON.parse(json.slice(index, end + 1)) as string;
        if (keys?.has(key)) return true;
        keys?.add(key);
      }
      index = end + 1;
    } else if (char === "-" || (char >= "0" && char <= "9")) {
      let end = index + 1;
      while (end < json.length && "0123456789.eE+-".includes(json[end])) end++;
      if (!isExactNumberLiteral(json.slice(index, end))) return true;
      index = end;
    } else {
      if (char === "{") objectKeys.push(new Set());
      else if (char === "[") objectKeys.push(undefined);
      if (objectKeys.length > ENCODED_JSON_DEPTH_LIMIT) return true;
      else if (char === "}" || char === "]") objectKeys.pop();
      index++;
    }
  }
  return false;
}

// A double round-trips any normal decimal of at most 15 significant digits (DBL_DIG).
function isExactNumberLiteral(literal: string): boolean {
  const value = Number(literal);
  if (!Number.isFinite(value)) return false;
  const digits = literal.replace(/^-/, "").split(/[eE]/)[0].replace(".", "").replace(/^0+/, "").replace(/0+$/, "");
  if (digits.length === 0) return !literal.startsWith("-");
  return digits.length <= 15 && Math.abs(value) >= Number.MIN_VALUE * 2 ** 52;
}

// Matches elements by content via LCS (not index) so a reorder or mid-array insert doesn't misread as a cascade of updates.
export function diffArrayEntries(oldArray: unknown[], newArray: unknown[], options: DiffOptions = {}): { key: string; diff: ValueDiff }[] {
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
    for (let i = 0; i < paired; i++) entries.push({ key: String(adds[i]), diff: diffValue(oldArray[deletes[i]], newArray[adds[i]], options) });
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
    const ops: ArrayEditOp[] = [];
    for (let i = 0; i < Math.max(n, m); i++) {
      if (i < n && i < m && deepEqual(oldArray[i], newArray[i])) ops.push({ type: "same", oldIndex: i, newIndex: i });
      else {
        if (i < n) ops.push({ type: "delete", oldIndex: i });
        if (i < m) ops.push({ type: "add", newIndex: i });
      }
    }
    return ops;
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

export function jsonDiffLines(properties: DiffProperty[], options: DiffOptions = {}): JsonDiffLine[] {
  const decode = options.decodeStrings !== false;
  const lines: JsonDiffLine[] = [];
  for (const property of properties) {
    const diff = diffValue(property.oldValue, property.newValue, { decodeStrings: decode });
    flattenDiff(diff, property.path, 0, lines, decode);
  }
  return lines;
}

function flattenDiff(diff: ValueDiff, key: string | undefined, depth: number, lines: JsonDiffLine[], decode: boolean): void {
  const label = key === undefined ? "" : `${key}: `;
  switch (diff.op) {
    case "add":
      renderWhole(diff.value, key, depth, "add", lines, decode);
      return;
    case "delete":
      renderWhole(diff.value, key, depth, "delete", lines, decode);
      return;
    case "update":
      lines.push({ depth, kind: "update", text: `${label}${formatJsonValue(diff.oldValue)} → ${formatJsonValue(diff.newValue)}` });
      return;
    case "same":
      lines.push({ depth, kind: "same", text: `${label}${formatJsonValue(diff.value)}` });
      return;
    case "encoded":
      flattenContainer(diff.diff, `${label}(json) `, depth, lines, false, true);
      return;
    case "object":
    case "array":
      flattenContainer(diff, label, depth, lines, decode, false);
      return;
  }
}

function flattenContainer(diff: ContainerDiff, prefix: string, depth: number, lines: JsonDiffLine[], decode: boolean, encoded: boolean): void {
  const [open, close] = diff.op === "object" ? ["{", "}"] : ["[", "]"];
  lines.push(encoded ? { depth, kind: "same", text: `${prefix}${open}`, encoded } : { depth, kind: "same", text: `${prefix}${open}` });
  flattenChildren(diff.entries, depth + 1, lines, decode);
  lines.push({ depth, kind: "same", text: close });
}

function flattenChildren(entries: { key: string; diff: ValueDiff }[], depth: number, lines: JsonDiffLine[], decode: boolean): void {
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
    flattenDiff(diff, key, depth, lines, decode);
  }
  flushRun();
}

// Renders a whole add/delete as indented JSON rather than one long line.
function renderWhole(value: unknown, key: string | undefined, depth: number, kind: "add" | "delete", lines: JsonDiffLine[], decode: boolean): void {
  const label = key === undefined ? "" : `${key}: `;
  if (decode && typeof value === "string") {
    const decoded = decodeJsonContainer(value);
    if (decoded.ok && Object.keys(decoded.value).length > 0) {
      renderContainer(decoded.value, `${label}(json) `, depth, kind, lines, false, true);
      return;
    }
  }
  if (isRecord(value) || Array.isArray(value)) {
    renderContainer(value, label, depth, kind, lines, decode, false);
    return;
  }
  lines.push({ depth, kind, text: `${label}${formatJsonValue(value)}` });
}

function renderContainer(
  value: Record<string, unknown> | unknown[], prefix: string, depth: number, kind: "add" | "delete", lines: JsonDiffLine[], decode: boolean, encoded: boolean,
): void {
  const [open, close] = Array.isArray(value) ? ["[", "]"] : ["{", "}"];
  const entries = Array.isArray(value) ? value.map((item, index) => [String(index), item] as const) : Object.entries(value);
  if (entries.length === 0) {
    lines.push({ depth, kind, text: `${prefix}${open}${close}` });
    return;
  }
  lines.push(encoded ? { depth, kind, text: `${prefix}${open}`, encoded } : { depth, kind, text: `${prefix}${open}` });
  for (const [childKey, childValue] of entries) renderWhole(childValue, childKey, depth + 1, kind, lines, decode);
  lines.push({ depth, kind, text: close });
}

function formatJsonValue(value: unknown): string {
  if (value === undefined) return "∅";
  if (value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}
