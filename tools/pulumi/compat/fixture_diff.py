#!/usr/bin/env python3
"""Diff two recorder fixture trees, masking version-dependent noise.

The recorder already normalizes volatile values (timestamps, random outputs,
generated ids, salts, source positions) at capture time. What remains
version-dependent across two captures with different Pulumi CLIs is masked here
instead, so the diff shows protocol changes rather than version-string churn:

- global `seq` (shifts wholesale when any command gains/loses a call)
- `body_b64` (byte-identical information to `body`, unreadable in a diff)
- `Content-Length` header
- CLI version strings (`v3.NNN.N`) wherever they appear (User-Agent,
  deployment manifest `version`)
- deployment manifest `magic` (hash over version-dependent input)
- `salt` / `sourcePosition` (also masked capture-side now; masked here too so
  diffs against baselines recorded before that stay clean)

The report has two levels:

1. **Call-sequence diff per command** — the ordered `METHOD path` list each
   command produced. This is the readable view when a CLI reorders or
   adds/removes calls (which renumbers every subsequent fixture file).
2. **Per-call diffs** — fixtures are aligned by (command, method, path,
   occurrence index), NOT by filename, so an inserted call early in a command
   does not misalign every later comparison. Aligned pairs that differ after
   masking are shown as JSON diffs.

Usage:
    fixture_diff.py BASELINE_DIR TRIAL_DIR --out report.md

Prints a one-line summary to stdout. Exit code: 0 when trees match after
masking, 1 when they differ, 2 on usage errors.
"""

import argparse
import difflib
import json
import re
import sys
from pathlib import Path

CLI_VERSION_RE = re.compile(r"v3\.\d+\.\d+")
MAX_DIFF_LINES_PER_FILE = 200


def mask(value, key=None):
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if k in ("seq", "cmd_seq", "body_b64"):
                continue
            if k == "headers" and isinstance(v, dict):
                # User-Agent changes (CLI cmd token renames) are aggregated
                # into their own report section instead of repeating in every
                # per-call diff.
                out[k] = {
                    hk: CLI_VERSION_RE.sub("[cli-version]", hv)
                    for hk, hv in v.items()
                    if hk.lower() not in ("content-length", "user-agent")
                }
                continue
            out[k] = mask(v, k)
        return out
    if isinstance(value, list):
        return [mask(v, key) for v in value]
    if isinstance(value, (int, float)) and key in ("timestamp", "durationSeconds"):
        return 0
    if isinstance(value, str):
        if key in ("magic", "salt", "sourcePosition") and value:
            return f"[{key.lower()}]"
        return CLI_VERSION_RE.sub("[cli-version]", value)
    return value


def load_tree(root: Path):
    """rel path → parsed fixture JSON for every fixture under by-command/."""
    base = root / "by-command"
    if not base.is_dir():
        return {}
    tree = {}
    for p in sorted(base.rglob("*.json")):
        with p.open() as f:
            tree[str(p.relative_to(base))] = json.load(f)
    return tree


def call_line(fix) -> str:
    q = fix.get("query", "")
    return f'{fix["method"]} {fix["path"]}{"?" + q if q else ""} → {fix["status_code"]}'


def sequences(tree):
    """command dir → ordered list of call lines (fixture filename order)."""
    seqs = {}
    for rel in sorted(tree):
        cmd = rel.split("/", 1)[0]
        seqs.setdefault(cmd, []).append(call_line(tree[rel]))
    return seqs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("baseline", type=Path)
    ap.add_argument("trial", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    base_tree = load_tree(args.baseline)
    trial_tree = load_tree(args.trial)
    if not base_tree:
        print(f"no fixtures under {args.baseline}/by-command", file=sys.stderr)
        return 2

    # Level 1: call sequences per command.
    base_seqs, trial_seqs = sequences(base_tree), sequences(trial_tree)
    seq_changed = {}
    for cmd in sorted(set(base_seqs) | set(trial_seqs)):
        old, new = base_seqs.get(cmd, []), trial_seqs.get(cmd, [])
        if old != new:
            seq_changed[cmd] = list(
                difflib.unified_diff(old, new, fromfile=f"baseline/{cmd}",
                                     tofile=f"trial/{cmd}", lineterm="")
            )

    # Level 2: align fixtures by (command, method, path, occurrence index) so
    # an inserted call doesn't misalign every later filename-numbered fixture.
    def occurrences(tree):
        occ = {}
        for rel in sorted(tree):
            cmd = rel.split("/", 1)[0]
            fix = tree[rel]
            occ.setdefault((cmd, fix["method"], fix["path"]), []).append(rel)
        return occ

    base_occ, trial_occ = occurrences(base_tree), occurrences(trial_tree)
    added, removed = [], []
    changed, unchanged, diffs = [], [], {}
    ua_changes = set()

    def user_agent(fix):
        for hk, hv in fix.get("headers", {}).items():
            if hk.lower() == "user-agent":
                return CLI_VERSION_RE.sub("[cli-version]", hv)
        return None
    for call_key in sorted(set(base_occ) | set(trial_occ)):
        base_rels = base_occ.get(call_key, [])
        trial_rels = trial_occ.get(call_key, [])
        removed.extend(base_rels[len(trial_rels):])
        added.extend(trial_rels[len(base_rels):])
        for base_rel, trial_rel in zip(base_rels, trial_rels):
            ua_old, ua_new = user_agent(base_tree[base_rel]), user_agent(trial_tree[trial_rel])
            if ua_old != ua_new:
                ua_changes.add((ua_old, ua_new))
            old = json.dumps(mask(base_tree[base_rel]), indent=2, sort_keys=True)
            new = json.dumps(mask(trial_tree[trial_rel]), indent=2, sort_keys=True)
            label = (base_rel if base_rel == trial_rel
                     else f"{base_rel} ↔ {trial_rel}")
            if old == new:
                unchanged.append(label)
                continue
            changed.append(label)
            diff = list(
                difflib.unified_diff(old.splitlines(), new.splitlines(),
                                     fromfile=f"baseline/{base_rel}",
                                     tofile=f"trial/{trial_rel}", lineterm="")
            )
            if len(diff) > MAX_DIFF_LINES_PER_FILE:
                diff = diff[:MAX_DIFF_LINES_PER_FILE] + [
                    f"... truncated ({len(diff) - MAX_DIFF_LINES_PER_FILE} more lines)"
                ]
            diffs[label] = diff
    added.sort()
    removed.sort()

    lines = ["# Fixture diff (version-noise masked)", ""]
    lines.append(
        f"baseline `{args.baseline}` vs trial `{args.trial}`: "
        f"**{len(seq_changed)} commands with call-sequence changes**; "
        f"aligned calls: {len(changed)} changed, {len(added)} added, "
        f"{len(removed)} removed, {len(unchanged)} unchanged"
    )
    lines.append("")
    if seq_changed:
        lines.append("## Call-sequence changes")
        lines.append("")
        lines.append(
            "_The ordered HTTP calls each command made. Read this first — the "
            "per-call diffs below are aligned by method+path occurrence, so "
            "renumbered filenames don't produce false diffs._"
        )
        lines.append("")
        for cmd, diff in seq_changed.items():
            lines.append(f"### `{cmd}`")
            lines.append("")
            lines.append("```diff")
            lines.extend(diff)
            lines.append("```")
            lines.append("")
    if ua_changes:
        lines.append("## User-Agent changes")
        lines.append("")
        lines.append("| Baseline | Trial |")
        lines.append("|---|---|")
        for old, new in sorted(ua_changes, key=str):
            lines.append(f"| `{old}` | `{new}` |")
        lines.append("")
    if changed:
        lines.append("## Per-call changes (aligned by method+path occurrence)")
        lines.append("")
        for rel in changed:
            lines.append(f"### `{rel}`")
            lines.append("")
            lines.append("```diff")
            lines.extend(diffs[rel])
            lines.append("```")
            lines.append("")
    for title, items in (("Calls only in trial", added),
                         ("Calls only in baseline", removed)):
        if items:
            lines.append(f"## {title}")
            lines.append("")
            lines.extend(f"- `{rel}`" for rel in items)
            lines.append("")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n")

    clean = not (seq_changed or added or removed or changed or ua_changes)
    print(
        f"fixture diff: {len(seq_changed)} commands with call-sequence changes; "
        f"{len(changed)} calls changed, {len(added)} added, "
        f"{len(removed)} removed, {len(unchanged)} unchanged → {args.out}"
    )
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main())
