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

## Reproducibility Notes

- The recorder uses fixed test values: org `testorg`, project `recorder-test`, stack `dev`.
- `meta.json` stores recording metadata and timestamp.
- Command boundaries are explicit (`/meta/command`), so call ordering is stable across runs.
