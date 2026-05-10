# pulumi api-extract

Extracts Pulumi HTTP backend API endpoints from `pulumi/pulumi`, writes versioned snapshots, and compares versions.

## Quick start

From repo root:

```bash
make pulumi-extract PULUMI_REF=v3.234.0
make pulumi-compare FROM=v3.233.0 TO=v3.234.0
make pulumi-discover FROM=v3.233.0 TO=v3.234.0
```

Or run tool-local targets:

```bash
make -C tools/pulumi/api-extract extract REF=v3.234.0
make -C tools/pulumi/api-extract compare FROM=v3.233.0 TO=v3.234.0
make -C tools/pulumi/api-extract discover FROM=v3.233.0 TO=v3.234.0
make -C tools/pulumi/api-extract test
```

## What gets generated

For each ref, files are written under `api-snapshots/pulumi/<ref>/`:

- `endpoints.json`: extracted endpoint list.
- `openapi-subset.yaml`: OpenAPI subset for extracted/scoped endpoints.
- `meta.json`: metadata including OpenAPI source and sha256.

Comparisons are written to:

- `api-snapshots/pulumi/comparisons/compare-<from>-<to>.md`

## Scope control

Default scope:

- `tools/pulumi/api-extract/scopes/state-backend.yaml`

Disable scope filtering:

```bash
make pulumi-extract PULUMI_REF=v3.234.0 PULUMI_SCOPE=
```

## Reproducibility notes

- Endpoint extraction is pinned to a Pulumi git `--ref` (tag/SHA).
- OpenAPI is fetched from a pinned file in `pulumi/pulumi` when present.
- If that file is missing for a ref, tool falls back to live Pulumi OpenAPI.
- `meta.json` records:
  - `openapi_source` (`repo-fixture` or `live-fallback`)
  - `openapi_location`
  - `openapi_sha256`
  - `openapi_fallback_reason`

For stable snapshots, prefer refs where `openapi_source` is `repo-fixture`.

## AI harness flow

Use one command to automate API change discovery:

```bash
make pulumi-discover FROM=v3.233.0 TO=v3.234.0
```

This extracts `TO` and writes a comparison report against `FROM`.
