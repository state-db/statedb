# Pulumi API Recorder

## What this is

An introspection rig for reverse-engineering the Pulumi HTTP backend protocol.
It runs inside Docker: a Go HTTP server (`recorder`) intercepts every request
the Pulumi CLI makes when `PULUMI_BACKEND_URL` points at it, returns minimal
stub responses to keep the CLI alive, and writes each request+response as a
numbered JSON fixture.

The fixtures are the source of truth. The catalog (`api-snapshots/pulumi/catalog.md`)
is derived from them and is what you actually implement state-db from.

**Do not proxy to api.pulumi.com** — that violates Pulumi's ToS. Always use the
active-stub approach where we return synthetic responses locally.

---

## Running it

From repo root (preferred):

```bash
make pulumi-recorder-discover   # docker build + full capture → replaces fixtures + catalog
```

Equivalent targets from `tools/pulumi/recorder/`: `make run`, `make run-no-build`,
`make catalog`, `make verify`, `make clean-fixtures` (also reachable from repo
root as `pulumi-recorder-*`). The host-side entry point is `run.sh`, which accepts
`PULUMI_VERSION`, `FIXTURES_DIR`, and `SNAPSHOTS_DIR` overrides; non-default CLI
versions build their own image tag (`pulumi-recorder:<version>`) so cached layers
coexist.

Regenerate only the catalog after a fresh clone (no Docker required):

```bash
cd tools/pulumi/recorder && make catalog
```

### Using the recorder against a new Pulumi CLI version

The workflow is owned by `tools/pulumi/compat/README.md`; in short:

1. Run the compat sweep: `make pulumi-compat-sweep TO=v3.NNN.0`. It captures
   with the target CLI into a scratch dir, diffs against the committed baseline,
   and writes a report. Committed fixtures/catalog are never touched.
2. Review the report (endpoint diff, call-sequence diff, per-call body/response
   diffs) and fix stubs/state-db if the protocol moved.
3. Adopt: bump `ARG PULUMI_VERSION` in the Dockerfile **and**
   `github.com/pulumi/pulumi/sdk/v3` in `tools/pulumi/verify/go.mod` in lockstep,
   re-record (`make pulumi-recorder-discover`), update the release matrix in
   `.github/workflows/native.yml`, run replay/smoke tiers, and re-run the sweep
   with `--publish`.

Do not point trial captures at the committed fixtures dir directly. Each capture
records the exact CLI version it ran under in `fixtures/meta.json`
(`cli_version`), alongside org/project/stack and the random provider version.

---

## File layout

```
recorder/
├── main.go              HTTP server — catch-all handler, fixture writing, /meta/ admin endpoints
├── stubs.go             Response routing — returns stub JSON to keep the CLI alive
├── catalog/main.go      Post-processor — reads fixtures, deduplicates, writes catalog.md
├── Dockerfile           Two-stage build: Go compiler + Pulumi CLI + runtime
├── entrypoint.sh        Runs inside container: signals command boundaries, drives pulumi commands
├── run.sh               Host-side entry point: docker build + run, mounts fixtures + snapshots
├── testproject/
│   └── Pulumi.yaml      Minimal YAML program with random provider resources
└── fixtures/
    ├── by-command/      One subdirectory per command, fixtures numbered by per-command sequence
    │   ├── 01-login/
    │   ├── 02-stack-init/
    │   └── ...
    └── meta.json        Recording metadata (org/project/stack values used)
```

---

## How the recorder works

### Command boundary signalling

`entrypoint.sh` calls `POST /meta/command {"command": "03-preview"}` before each
`pulumi` invocation. The server switches its output directory to
`fixtures/by-command/03-preview/` and resets the per-command sequence counter.
Fixtures get a per-command sequence number (for ordering within the command) and
a global sequence number (field `seq`, for cross-command ordering). Labels must
be unique across a session — a reused label is rejected with 409, so copy-paste
slips fail fast instead of silently merging two commands' traffic. The entrypoint
waits for `GET /meta/health` before driving any command.

### Seeding state no command can produce

`POST /meta/active-update {"stack": "testorg/recorder-test/dev"}` puts a stack
into the "update in flight" state that `pulumi cancel` requires. No pulumi
command can leave that state behind — every update the CLI starts runs to
completion — and backgrounding a blocking `up` would interleave its journal and
event traffic into whichever command is currently signalled, since command
attribution is a single mutable global.

Seed through `/meta/` rather than by curling the stub's real API: `/meta/` paths
bypass fixture writing, so the fabricated state never appears in `by-command/`
or the catalog as traffic some CLI supposedly sent. Anything else the CLI cannot
reach on its own belongs here too, for the same reason.

### Fixture format

Each JSON file has:

```json
{
  "seq": 11,            // global sequence across all commands
  "command": "03-preview",
  "cmd_seq": 5,         // sequence within this command
  "method": "POST",
  "path": "/api/stacks/testorg/recorder-test/dev/preview",
  "query": "",
  "headers": { "Authorization": "REDACTED", ... },
  "body": { ... },      // parsed JSON; gzip bodies are decompressed before parsing
  "body_b64": "...",    // replay bytes, base64-encoded
  "body_gzip": true,    // true when body_b64 is gzip-compressed
  "body_raw": "...",    // legacy fallback for non-JSON, non-gzip text/binary
  "status_code": 200,
  "response": { ... }   // what our stub returned
}
```

### Response routing (`stubs.go`)

Path segments for `/api/stacks/...` are positional:

```
/api / stacks / {org} / {project} / {stack} / {op} / {updateID} / {subOp} / {leaf}
  0       1       2        3          4         5        6           7         8
```

`routeStacks()` switches on `n` (number of segments) and `method`. The stateful
parts live in `router`:

- `router.stacks` — map of `org/proj/stack` → stack record. `GET` returns 404
  until `POST /api/stacks/{org}/{project}` creates the stack; `DELETE` removes it.
- `router.ops` — map of `updateID` → lease token. Created by
  `POST .../preview|update|destroy|refresh`; consumed by
  `POST .../update/{updateID}` (start operation, returns token).

### Key API discoveries (things the spec got wrong)

| What the spec says | What the CLI actually does |
|---|---|
| `POST /api/stacks/{org}` | `POST /api/stacks/{org}/{project}` — project in URL, `stackName` in body |
| `GET /api/stacks/{org}` for stack list | `GET /api/user/stacks?project=recorder-test` |
| Separate sub-paths for preview/destroy ops | All post-creation calls go through `/update/{updateID}` regardless of op kind |

Other discoveries:
- `pulumi up` and `pulumi destroy` each run the full lifecycle **twice**: first pass
  is a preview (no checkpoint writes), second pass is the actual apply.
- With `journalVersion: 1`, real resource changes are written through
  `PATCH .../journalentries`; without journaling, the CLI falls back to
  `PATCH .../checkpoint`.
- The `events/batch`, `checkpoint`, and `journalentries` request bodies can be
  gzip-compressed. Fixtures preserve replayable bytes in `body_b64`; when the
  decompressed payload is JSON, it also appears in `body` for inspection. Volatile
  JSON fields such as event/resource timestamps, generated provider IDs, and random
  outputs are normalized to keep fixture diffs stable.
- CLI version strings are masked to `[cli-version]` at capture time — in the
  `User-Agent` header and in `version` / `pulumi.version` values — so a pin bump
  only diffs fixtures whose calls actually changed. The `cmd=` token in the
  User-Agent is kept (it is real signal: the CLI renames command tokens), and
  the exact version lives in `fixtures/meta.json`.
- Recorder-owned container variables must not be named `PULUMI_*`: the CLI
  enumerates every `PULUMI_`-prefixed variable into the update request body, so
  such a name would plant recorder plumbing in the fixtures
  (`STATEDB_RECORDER_RANDOM_PROVIDER_VERSION` is named that way for this reason).
- `DELETE /api/stacks/{org}/{project}/{stack}?force=false` — the `force` query param
  matters; passing `force=true` skips the "has resources" guard.

---

## Adding more fixtures

### 1. Add the command to `entrypoint.sh`

```bash
signal "09-stack-rename"
run pulumi stack rename neworg/recorder-test/staging
```

Pick the next number in sequence. The signal must come before the `run` call.

### 2. Run the capture

```bash
make pulumi-recorder-discover   # from repo root, or `make run` from this dir
```

The new command will fail at whichever endpoint the stub doesn't handle yet.
The last fixture in `fixtures/by-command/09-stack-rename/` shows what the CLI
sent and what we returned. Read it:

```bash
ls fixtures/by-command/09-stack-rename/
cat fixtures/by-command/09-stack-rename/0003_POST_api_stacks_testorg_recorder-test_dev_rename.json
```

### 3. Fix the stub

The fixture's `.path` and `.body` tell you what the CLI sent.
Check the versioned OpenAPI subset under `api-snapshots/pulumi/` for the
expected response shape, or infer from the error the CLI prints.

Edit `stubs.go`. The typical pattern is a new `case` inside `routeStacks()`:

```go
case op == "rename" && method == "POST":
    return rt.renameStack(org, proj, stack, body)
```

Add a helper at the bottom of `stubs.go`.

### 4. Verify compile + re-run

```bash
make verify && make pulumi-recorder-discover   # or: go build ./... && make run
```

Repeat steps 2–4 until the command completes. The iteration is fast after the
first Docker build because only the Go compilation step re-runs (Pulumi install
is cached).

### 5. Commit the new fixtures and updated catalog

```bash
git add tools/pulumi/recorder/fixtures/by-command/09-stack-rename/
git add api-snapshots/pulumi/catalog.md
git add tools/pulumi/recorder/stubs.go
git add tools/pulumi/recorder/entrypoint.sh
```

---

## Planned lifecycle gaps

Commands not yet captured (add these as state-db matures):

- `pulumi config set` / `get` — exercises `PUT .../config` and encrypt/decrypt
- A slower `pulumi up` / `destroy` — may show `renew_lease`
- `pulumi stack import` — exercises `POST .../import`
- `pulumi whoami` — may hit additional `/api/user` sub-paths

Already captured: login, stack init/select/unselect/rm/rename, up/preview/
refresh/destroy, cancel, export/graph/output/history, tags, and
change-secrets-provider (see the numbered commands in `entrypoint.sh`).

When adding providers to `testproject/Pulumi.yaml`, pin the corresponding
`pulumi plugin install` version in the Dockerfile before recording.

---

## Catalog regeneration

The catalog is a derived artifact — regenerate it any time fixtures change:

```bash
cd tools/pulumi/recorder && make catalog
```

(equivalent to `go run ./catalog -fixtures fixtures -out ../../api-snapshots/pulumi/catalog.md -org testorg -project recorder-test -stack dev`).

The catalog deduplicates by `METHOD + normalized-path`, prefers 2xx examples,
and detects binary bodies (gzip checkpoint/events payloads).
