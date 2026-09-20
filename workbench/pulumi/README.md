# Pulumi Workbench

Run the real Pulumi CLI against a local state-db backend and inspect what
lands in Postgres. Not hermetic — edit `Pulumi.yaml`, run the CLI, check the
DB, repeat. Everything here runs through the host-native workbench lifecycle.

## Quick reference

| Task | Command |
| --- | --- |
| Run everything (built image, default) | `make pulumi-workbench cmd=app` |
| Run everything (clean build and hot reload) | `make pulumi-workbench cmd=app-dev` |
| Rebuild the image after code changes | `make pulumi-workbench cmd=build-image` |
| Populate fixtures | `make pulumi-workbench cmd=fixtures-up` |
| Pulumi preview / up / destroy | `cmd=preview\|up\|destroy` |
| Inspect the DB | `cmd=sql` / `cmd=psql` |
| Stop app, keep data | `Ctrl-C` |
| Stop stray processes/containers | `cmd=app-stop` |
| Stop infra, keep data | `cmd=infra-down` |
| Wipe everything | `cmd=reset` |
| Clear a stuck lock/update | `cmd=force-unlock` |

`make pulumi-workbench cmd=app` is one command: infra, seeding, and the app
all come up together, building `state-db:workbench` automatically the first
time (several minutes; cached after that). Open `http://localhost:5173`,
sign in as `admin` / `developer` / `viewer`:

| Subject | Role |
| --- | --- |
| `admin` | site admin + org owner |
| `developer` | org member |
| `viewer` | org viewer |

`Ctrl-C` stops the app + console; infra (Postgres + mock IdP) stays up. Every
other target (`fixtures-up`, `churn-*`, `scale-*`, `preview`/`up`) just needs
`cmd=app` running — no container, no extra setup.

Needs `mise` (`mise trust && mise install`, once) for `cmd=app-dev`'s Java/Bun
toolchain, and `pulumi`/`docker` on `PATH`.

The Make targets are the supported entry points. They stop stale processes,
reuse healthy infrastructure, and run Java and Bun through the versions pinned
by mise. `workbench/pulumi/dev-host.sh` is their internal supervisor; do not
start Bun or Quarkus separately.

`cmd=app-dev` cleans the Maven reactor before it starts Quarkus. This removes
compiled classes for source files that were deleted since the prior run.

Override with env vars: `ORG`, `CONSOLE_PORT` (5173), `IDP_UPSTREAM_PORT`
(58081), `DB_PORT` (55432), `STATEDB_IMAGE` (default `state-db:workbench`).

**Port 8080/9000 already in use?** Run `cmd=app-stop` — kills stray
`bun`/`quarkus:dev`/`state-db-app-dev.jar` processes and the
`state-db-workbench-app` container a previous `Ctrl-C` didn't reap.

## Fixture estate

`make pulumi-workbench cmd=fixtures-up` drives real Pulumi ops to build:

| Project | Stacks | Contents |
| --- | --- | --- |
| `shared-foundation` | dev, staging, prod | random ids/passwords, TLS certs, secrets |
| `artifact-pipeline` | nightly, release | generated manifests/docs/checksums, an archive |
| `application-fleet` | dev, staging, prod | app-shaped resources, refs to `shared-foundation` |
| `deploy-simulator` | dev | chained sleeping commands, drives churn runs |

Each stack gets real update history (creates, config updates, a rotation,
refresh-before-preview, refresh-before-update, a standalone refresh,
`artifact-pipeline/nightly`'s release gate failing on purpose then retrying, a
targeted destroy/restore, and a full destroy/rebuild on
`application-fleet/dev`). Re-running converges to the final revision and adds
fresh refresh-before-preview and refresh-before-update runs to each existing
`shared-foundation` stack. A fresh `shared-foundation` fixture follows the
first-creation choreography and uses the same refresh-display messages. First
run: ~35 real Pulumi ops, a few minutes.

On a `shared-foundation` fixture, use these messages to verify refresh display
semantics in the console:

- `Preview with refresh activity`: the summary remains unchanged, while the
  Changes tab exposes `refresh` rows behind its activity checkbox.
- `Update with refresh activity`: the applied `up --refresh` has the same
  two-phase display.
- `Scheduled drift check`: this is a standalone refresh, so its terminal rows
  are `same` and the page reports no drift.

`fixtures-up` creates the first two runs on both new and existing stacks. The
standalone `Scheduled drift check` is part of the first-creation choreography,
and existing stacks take the convergence path and do not acquire these three
historical runs.

`cmd=fixtures-destroy` tears down resources, keeps the empty stacks.
`cmd=force-unlock` clears a stuck lock after an interrupted run.

**Churn runs** (watch a live in-progress update in the console):

```bash
make pulumi-workbench cmd=churn-preview
make pulumi-workbench cmd=churn-up args="CHURN_SECONDS=15"   # default 6
```

Both target `deploy-simulator/dev` with a fresh nonce so every step changes.

## Scale runs

A local-only Go provider, `statedbsim`, generates high-cardinality stacks for
production-scale checks. Not published anywhere; built with host Docker.

```bash
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=500 SCALE_DEPTH=12"
make pulumi-workbench cmd=scale-fault-up args="SCALE_RESOURCES=250 SCALE_FAULT_INDEX=42"
make pulumi-workbench cmd=scale-destroy
```

Creates are throttled by default (`SCALE_CREATE_DELAY_MS=600`,
`SCALE_CREATE_JITTER_MS=800`, `SCALE_PARALLEL=8`) to resemble a real cloud
deploy — 1000 resources ≈ 2 min. Set both delay knobs to `0` for instant
runs. Generated YAML lives under `workbench/.generated/pulumi/scale-simulator`
(gitignored).

## Browser login (SSO)

Exercise the real `pulumi login` browser flow instead of the bootstrap raw
token (the loopback listener needs to share `127.0.0.1` with your browser,
so this only works run directly on your machine — true for everything here):

```bash
export PULUMI_HOME="$(git rev-parse --show-toplevel)/workbench/.pulumi-home"
export PULUMI_CONSOLE_DOMAIN=localhost:5173
pulumi login http://localhost:8080/pulumi   # press <ENTER>, don't paste a token
pulumi stack ls
```

`PULUMI_HOME` must stay exported for every later command or it falls back to
`~/.pulumi` and re-prompts. Don't set `PULUMI_ACCESS_TOKEN` — that skips the
browser flow entirely.

## Defaults

- Backend: `http://localhost:8080/pulumi` · org `acme` · stack `dev`
  (`acme/dev` for the CLI) · token `state-db-workbench-token`
- Postgres: container `state-db-workbench-postgres`, image `postgres:16`,
  `localhost:55432`, volume `state-db-workbench-postgres-data`
- Mock IdP upstream: `localhost:58081` (internal plumbing — sign in via the
  console URL, not this port)
- App image: `state-db:workbench` (override with `STATEDB_IMAGE`)

Override via Make args, e.g. `make pulumi-workbench cmd=up args="STACK=scratch"`.
Stacks are addressed `<org>/<stack>`, derived as `$(ORG)/$(STACK)`.

Switching Postgres major versions: `cmd=reset` then
`cmd=db-up args="DB_IMAGE=postgres:18"`.

`workbench/.pulumi-home/` is gitignored, local only.

## Notes

If `pulumi up` fails, still run `make pulumi-workbench cmd=sql` — partial
writes are often the most useful signal while tightening protocol
compatibility.
