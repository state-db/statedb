# Pulumi-compatible Recorder

Capture Pulumi CLI HTTP backend calls into JSON fixtures, then generate a deduplicated API catalog.

## Prerequisites

- Docker
- Go (only needed for `catalog` / `verify` targets)

## Quick Start

From repo root:

```bash
make pulumi-recorder-discover
```

This will:
- build and run the recorder container
- execute the Pulumi command sequence
- replace the previous recorded fixture set
- write fixtures to `tools/pulumi/recorder/fixtures/by-command/`
- regenerate `api-snapshots/pulumi/catalog.md`

## Common Targets

From `tools/pulumi/recorder`:

```bash
make run            # build + record
make run-no-build   # record using existing image
make catalog        # regenerate catalog from existing fixtures
make verify         # go test + go build
make clean-fixtures # remove recorded fixtures/meta
```

Or from repo root:

```bash
make pulumi-recorder-run
make pulumi-recorder-catalog
make pulumi-recorder-verify
```

## Version overrides

`run.sh` (and the `run`/`run-no-build` targets) accept environment overrides:
`PULUMI_VERSION` (CLI version to capture with; images are tagged
`pulumi-recorder:<version>` so cached builds coexist), `FIXTURES_DIR`, and
`SNAPSHOTS_DIR`. Trial captures against candidate versions should redirect the
output dirs — `tools/pulumi/compat/sweep.sh` does this for you and diffs the
result against the committed baseline.

## Reproducibility Notes

- The recorder uses fixed test values: org `testorg`, project `recorder-test`, stack `dev`.
- The Dockerfile pins the Pulumi CLI and provider versions used for capture.
- Resource operations run with `--parallel 1` so event ordering is deterministic.
- `meta.json` stores the recording org, project, and stack values plus the exact
  Pulumi CLI version (`cli_version`) and random provider version used for the
  capture.
- Volatile fixture values such as timestamps, generated IDs, and random outputs are
  normalized so repeated captures produce reviewable diffs.
- Command boundaries are explicit (`/meta/command`), so call ordering is stable across runs.
