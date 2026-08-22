# Pulumi Compatibility Sweep

One command that answers: **"has Pulumi changed anything that affects state-db
since the version we're pinned to?"**

It chains the three existing tools — `api-extract` (source-level endpoint
diff), `recorder` (behavioral capture with the target CLI), and the pin
registry (recorder Dockerfile, `verify/go.mod`, release workflow matrix) — into
a single report.

## Quick start

From repo root:

```bash
make pulumi-compat-sweep                 # against the latest Pulumi release
make pulumi-compat-sweep TO=v3.253.0     # against a specific release
make pulumi-compat-sweep TO=v3.242.0     # target == pin: reproducibility check
make pulumi-compat-sweep SWEEP_ARGS=--skip-record   # no Docker; pins + endpoint diff only
make pulumi-compat-sweep TO=v3.253.0 SWEEP_ARGS=--publish   # adopting: write the record
```

By default **everything stays in the gitignored scratch dir**
(`tools/pulumi/compat/.sweep/<target>/`): endpoint snapshots, comparison, trial
fixtures, capture log, masked diff, and the report. A routine sweep leaves
`git status` clean. `--publish` copies the target's snapshot, the comparison,
and the report into `api-snapshots/pulumi/` — do that only for the sweep that
leads to a pin adoption, and delete superseded snapshot dirs at the same time
(the report's pin audit lists which ones are prunable; they are regenerable
from pinned refs).

## What a sweep does

1. **Pin audit** — reads the baseline from `ARG PULUMI_VERSION` in
   `tools/pulumi/recorder/Dockerfile` and checks the two pins that must move in
   lockstep with it: `github.com/pulumi/pulumi/sdk/v3` in
   `tools/pulumi/verify/go.mod`, and the CLI image matrix in
   `.github/workflows/native.yml`.
2. **Endpoint diff** — `api-extract` from baseline to target, into the scratch
   dir (the committed baseline snapshot is copied in if present, extracted
   otherwise). Source-level: catches added/removed endpoints in the scoped
   state-backend surface. The report embeds only the summary and
   added/removed sections; the full inventory stays in the comparison file.
3. **Trial capture** — runs the recorder with the *target* CLI version into a
   scratch directory. Committed fixtures and the committed catalog are never
   touched. A capture failure is itself a finding (stub gap or protocol
   change), and the partial capture is still diffed.
4. **Masked diff** — `fixture_diff.py` compares trial vs committed fixtures:
   - **Call-sequence diff per command** — the readable view when the CLI
     reorders, adds, or removes calls.
   - **Per-call diffs** aligned by (command, method, path, occurrence index) —
     immune to filename renumbering when a call is inserted mid-command.
   - **User-Agent changes** aggregated into one table (CLI cmd token renames).
   - Version-dependent noise (CLI version strings, manifest `magic`,
     `Content-Length`, `seq`/`cmd_seq`) is masked.
   The catalog is diffed too.

## Reading the report

- **Endpoint diff shows changes** → the protocol surface moved; read the
  comparison and the Pulumi changelog, then decide whether state-db needs work
  before adopting the version.
- **Call-sequence changes** → the CLI drives the same endpoints differently
  (e.g. v3.249.0 started sending `POST .../encrypt` before every operation).
  Usually no state-db change needed, but confirm the smoke tier passes with the
  new CLI.
- **Per-call body/response diffs** → request or response shapes changed; this
  is the strongest signal that stubs and possibly state-db need updating.
- **Diffs in a target == baseline run** → drift between committed fixtures and
  current stub code (someone changed `stubs.go` without re-recording), or
  capture nondeterminism. Re-record.

Known nondeterminism: concurrent CLI probes can swap adjacent call order in
`26-destroy` / `27-change-secrets-provider` (see
`stabilizeConcurrentPulumiProbe` in the recorder). A one-line add/remove pair
there with no body changes is benign.

## Adopting a new version (after a clean-enough sweep)

1. Bump `ARG PULUMI_VERSION` in `tools/pulumi/recorder/Dockerfile`.
2. Re-record: `make pulumi-recorder-discover`; review + commit fixtures and
   catalog.
3. Bump `github.com/pulumi/pulumi/sdk/v3` in `tools/pulumi/verify/go.mod` to
   the same version (lockstep rule) and run `make pulumi-verify-test`.
4. Add the new version to the release compatibility matrix in
   `.github/workflows/native.yml`; drop versions no longer worth supporting.
5. Run the replay tier (`ContractReplayTest`) and, before release, the
   smoke/fault tiers.
6. Re-run the sweep with `--publish` and commit the published report,
   comparison, and snapshot as the record of the review; delete the snapshot
   dirs the pin audit flags as prunable.

## Cadence

Pulumi ships roughly weekly. A sweep per two-to-four weeks keeps the diff
small; the `--skip-record` form is cheap enough to run more often. The manual
release build exercises the pinned versions before publishing — the sweep is
what tells you when to *move* the pins.

Like the recorder and verifier, this is a host tool that manages its own Go,
Docker, and Python execution environment.
