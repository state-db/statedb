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

```bash
cd tools/pulumi/recorder
./run.sh          # docker build + full capture session → updates fixtures + catalog
```

After a fresh clone, regenerate only the catalog (no Docker required):

```bash
go run ./catalog -fixtures fixtures -out ../../api-snapshots/pulumi/catalog.md
```

---

## File layout

```
recorder/
├── main.go              HTTP server — catch-all handler, fixture writing, /meta/command endpoint
├── stubs.go             Response routing — returns stub JSON to keep the CLI alive
├── catalog/main.go      Post-processor — reads fixtures, deduplicates, writes catalog.md
├── Dockerfile           Two-stage build: Go compiler + Pulumi CLI + runtime
├── entrypoint.sh        Runs inside container: signals command boundaries, drives pulumi commands
├── run.sh               Host-side entry point: docker build + run, mounts fixtures + snapshots
├── testproject/
│   └── Pulumi.yaml      Minimal empty YAML program — no resources, no providers needed
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
a global sequence number (field `seq`, for cross-command ordering).

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
  "body": { ... },      // parsed JSON, or absent
  "body_raw": "...",    // present instead of body when content is binary (e.g. gzip)
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
- `PATCH .../checkpoint` is called 3× during a real `pulumi up` with resources.
- The `events/batch` and `checkpoint` request bodies are **gzip-compressed** — not JSON.
  Store as `body_raw`; treat as opaque blobs.
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

### 2. Run `./run.sh`

The new command will fail at whichever endpoint the stub doesn't handle yet.
The last fixture in `fixtures/by-command/09-stack-rename/` shows what the CLI
sent and what we returned. Read it:

```bash
ls fixtures/by-command/09-stack-rename/
cat fixtures/by-command/09-stack-rename/0003_POST_api_stacks_testorg_recorder-test_dev_rename.json
```

### 3. Fix the stub

The fixture's `.path` and `.body` tell you what the CLI sent.
Check `api-snapshots/pulumi/v3.234.0/openapi-subset.yaml` for the expected
response shape, or infer from the error the CLI prints.

Edit `stubs.go`. The typical pattern is a new `case` inside `routeStacks()`:

```go
case op == "rename" && method == "POST":
    return rt.renameStack(org, proj, stack, body)
```

Add a helper at the bottom of `stubs.go`.

### 4. Verify compile + re-run

```bash
go build ./... && ./run.sh
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

- `pulumi stack rename` — exercises `POST .../rename`
- `pulumi config set` / `get` — exercises `PUT .../config` and encrypt/decrypt
- `pulumi stack tag set` — exercises `PATCH .../tags`
- `pulumi up` with actual resources (e.g. `random` provider) — will show richer
  checkpoint bodies, multiple `PATCH .../checkpoint` calls, and `renew_lease`
  under a slow deploy
- `pulumi stack import` — exercises `POST .../import`
- `pulumi whoami` — may hit additional `/api/user` sub-paths

To capture these, add a provider to `testproject/Pulumi.yaml` (e.g. `random`)
and add the `pulumi plugin install` step to the Dockerfile before running.

---

## Catalog regeneration

The catalog is a derived artifact — regenerate it any time fixtures change:

```bash
cd tools/pulumi/recorder
go run ./catalog \
  -fixtures fixtures \
  -out ../../api-snapshots/pulumi/catalog.md \
  -org testorg \
  -project recorder-test \
  -stack dev
```

The catalog deduplicates by `METHOD + normalized-path`, prefers 2xx examples,
and detects binary bodies (gzip checkpoint/events payloads).
