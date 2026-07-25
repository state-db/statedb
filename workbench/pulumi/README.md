# Pulumi Workbench

This is a mutable Pulumi project for running the real Pulumi CLI against the
local state-db backend and then inspecting what landed in Postgres.

## Flow

Start the Compose-managed workbench infrastructure:

```bash
make pulumi-workbench cmd=infra-up
```

This starts Postgres and the mock OIDC provider. `cmd=app` also runs this target automatically.

Start the app in another terminal:

```bash
make pulumi-workbench cmd=app
```

The workbench app target disables Quarkus continuous testing. It runs with
bootstrap credentials in the environment, and continuous tests would inherit
those values and pollute bootstrap-related test assertions.

Open the console at `http://localhost:5173`. The Bun dev server proxies:

- `/api/*` and `/pulumi/*` to Quarkus on port 8080.
- `/default/*` to the Compose mock IdP.

The mock issuer is therefore `http://localhost:5173/default` for both Quarkus
and the host browser. There is no separate port 8081 to forward or manage.

The mock IdP has workbench profile defaults for these sign-in subjects:

| Subject | Email | Display name | Workbench role |
| --- | --- | --- | --- |
| `admin` | `admin@example.com` | `Admin Owner` | site admin + org owner |
| `developer` | `developer@example.com` | `Developer Member` | org member |
| `viewer` | `viewer@example.com` | `View Only` | org viewer |

You can still type ad hoc claims in the mock IdP JSON box, but these subjects
do not require a body.

Run Pulumi from another terminal:

```bash
make pulumi-workbench cmd=preview
make pulumi-workbench cmd=up
make pulumi-workbench cmd=sql
```

Destroy the Pulumi resources:

```bash
make pulumi-workbench cmd=destroy
```

## Console fixture estate

Populate a more realistic set of projects and stacks after the workbench app is
already running:

```bash
make pulumi-workbench cmd=fixtures-up
```

This uses the real Pulumi CLI and state-db backend to create the following
local-only estate:

| Project | Stacks | Contents |
| --- | --- | --- |
| `shared-foundation` | `dev`, `staging`, `prod` | Random identifiers and passwords, TLS keys, self-signed certificates, and secret outputs |
| `artifact-pipeline` | `nightly`, `release` | Locally generated manifests, documentation, checksums, and an archive asset stored in state |
| `application-fleet` | `dev`, `staging`, `prod` | Application-shaped resources, structured outputs, secrets, dependencies, and references to the matching foundation stack |
| `deploy-simulator` | `dev` | Chained local commands that sleep per step; drives the long-running churn runs below |

Newly created stacks are walked through a choreographed history so the console
has a realistic variety of runs to render, each with its own update message:

- **All projects** — initial create, then a config-only update.
- **`shared-foundation`** — a rotation revision that replaces the environment id
  and database password, a `pulumi refresh`, and a recorded preview of a further
  rotation that is never applied.
- **`artifact-pipeline/nightly`** — an update that **fails by design** (the
  release gate rejects the build; expect one red `pulumi up` during bootstrap),
  followed by a successful retry.
- **`artifact-pipeline/release`** — a targeted `pulumi destroy` of the checksum
  resource, then an update that restores it.
- **`application-fleet/dev`** — a full destroy and rebuild.

Re-running the command skips the history and converges existing stacks to the
final revision, which should produce no resource changes. The first run takes a
few minutes since it performs ~30 real Pulumi operations.

No cloud credentials or cloud APIs are used. Pulumi may access its plugin
download service on the first run to install the pinned `random`, `tls`, and
`command` provider versions. The command provider writes only beneath
`workbench/.generated/pulumi/`, which is ignored by Git.

Destroy all fixture resources while keeping the empty stacks:

```bash
make pulumi-workbench cmd=fixtures-destroy
```

The fixture inventory is declared in `fixtures.txt`; the individual Pulumi YAML
programs live under `projects/` and `bootstrap-fixtures.sh` drives deployment.

### Long-running churn runs

To watch a live run in the console (in-progress status, event streaming,
resource rows appearing as they complete), use the deploy-simulator churn
targets:

```bash
make pulumi-workbench cmd=churn-preview
make pulumi-workbench cmd=churn-up
```

Both target `deploy-simulator/dev`, set a fresh `deployNonce` so every step
reports a change (a mix of in-place updates and replacements), and sleep
`CHURN_SECONDS` (default 6) per simulated step:

- `churn-preview` stays in-progress for roughly 6×`CHURN_SECONDS` (~36s at the
  default) — only the six chained invoke-based checks execute during a preview;
  the rollout commands themselves never run until `up`.
- `churn-up` runs for roughly 11×`CHURN_SECONDS` (~66s at the default) as the
  checks re-run and each rollout step executes in sequence, logging
  per-resource output that shows up as diagnostics.

Stretch the window when you need more time to poke around:

```bash
make pulumi-workbench cmd=churn-preview args="CHURN_SECONDS=15"
```

Clear an interrupted workbench update/lock:

```bash
make pulumi-workbench cmd=force-unlock
```

### High-cardinality scale runs

For production-scale console and backend checks, the workbench includes a
local-only Go provider named `statedbsim`. It is not published anywhere. The
Make targets build it with host Docker in a Go builder container and install the
binary into the workbench-local Pulumi plugin cache under `workbench/.pulumi-home`.

Generate and deploy a synthetic stack:

```bash
make pulumi-workbench cmd=scale-up
```

Useful knobs:

```bash
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=500 SCALE_DEPTH=12 SCALE_PAYLOAD_BYTES=2048"
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=300 SCALE_CREATE_DELAY_MS=100 SCALE_UPDATE_DELAY_MS=50"
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=300 SCALE_CREATE_JITTER_MS=400"
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=500 SCALE_REVISION=2"
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=500 SCALE_REPLACEMENT_KEY=rotation-2"
```

By default a `scale-up` throttles creates so it resembles a real cloud deploy rather than
finishing instantly: `SCALE_CREATE_DELAY_MS` (default 600) is a fixed floor applied to every
create and `SCALE_CREATE_JITTER_MS` (default 800) adds a random `[0, jitter)` millisecond delay
on top, so resources finish creating at staggered, unpredictable times. `SCALE_PARALLEL`
(default 8) pins how many creates run at once. First-up wall time is roughly
`SCALE_RESOURCES * (SCALE_CREATE_DELAY_MS + SCALE_CREATE_JITTER_MS / 2) / SCALE_PARALLEL`, so the
defaults put a 1000-resource run at ~2 minutes. Set both delays to 0 for instant runs:

```bash
make pulumi-workbench cmd=scale-up args="SCALE_RESOURCES=1000 SCALE_CREATE_DELAY_MS=0 SCALE_CREATE_JITTER_MS=0"
```

Inject a deterministic provider failure:

```bash
make pulumi-workbench cmd=scale-fault-up args="SCALE_RESOURCES=250 SCALE_FAULT_INDEX=42 SCALE_FAULT_MODE=once"
```

Use `SCALE_FAULT_MODE=always` for a repeatable hard failure, or
`SCALE_FAIL_EVERY=97` with `scale-up` for hash-selected failures across the
graph. Destroy the generated stack with:

```bash
make pulumi-workbench cmd=scale-destroy
```

The generated Pulumi YAML lives under `workbench/.generated/pulumi/scale-simulator`
and is intentionally ignored by Git.

Reset all local workbench state:

```bash
make pulumi-workbench cmd=reset
```

## Browser login (SSO)

The Make targets authenticate with the bootstrap raw token
(`PULUMI_ACCESS_TOKEN`). To exercise the real `aws sso login`-style flow instead
— `pulumi login` opens the browser, you sign in through the console SSO, and an
account-scoped 24h token is minted and written to `credentials.json` — run, **on
the host** (not the dev container):

```bash
# export so every later pulumi command in this shell shares the same login,
# and use an absolute path so it doesn't matter which dir you run from.
export PULUMI_HOME="$(git rev-parse --show-toplevel)/workbench/.pulumi-home"
export PULUMI_CONSOLE_DOMAIN=localhost:5173

pulumi login http://localhost:8080/pulumi   # press <ENTER>, do the browser SSO
pulumi stack ls                             # reuses the same login
```

Press `<ENTER>` at the login prompt (do **not** paste a token) to start the
browser flow. Pulumi opens `http://localhost:5173/cli-login?...`, you complete
the mock-IdP login, click **Allow access**, and the CLI captures the token from
its loopback listener.

- **`export` `PULUMI_HOME` once.** It is read per command, not just by `login`;
  if a later `pulumi stack ls` omits it (or uses a different relative path), it
  falls back to `~/.pulumi` and re-prompts even though the login succeeded.
- `PULUMI_CONSOLE_DOMAIN` is required: the backend is on `:8080` and the console
  on `:5173`, so Pulumi cannot infer the console host on its own.
- Run it on the host because `pulumi login`'s loopback listener and your browser
  must share `127.0.0.1`; the dev container cannot open the host browser.
- Do not set `PULUMI_ACCESS_TOKEN` — that bypasses the browser flow and uses the
  raw token directly.

Re-run `pulumi login` to refresh the token once it expires (the SSO session, not
a per-mint re-auth, is what gates freshness and MFA).

## Defaults

- Backend URL: `http://localhost:8080/pulumi`
- Bootstrap org: `acme`
- Stack: `dev` (`acme/dev` when passed to the Pulumi CLI)
- Raw token: `state-db-workbench-token`
- Postgres container: `state-db-workbench-postgres`
- Postgres image: `postgres:16`
- Postgres host port: `localhost:55432`
- Postgres volume: `state-db-workbench-postgres-data` mounted at `/var/lib/postgresql/data`
- Mock IdP upstream port: `localhost:58081` (internal workbench plumbing; use the console URL above)

Override any of these with Make variables, for example:

```bash
make pulumi-workbench cmd=up args="STACK=scratch"
```

Pulumi Cloud-style org stacks must be addressed as `<org>/<stack>`. The
workbench derives that as `STACK_REF=$(ORG)/$(STACK)`, so overriding `STACK`
usually does the right thing.

Use a different app URL if needed:

```bash
make pulumi-workbench cmd=up args="BACKEND_URL=http://127.0.0.1:8080/pulumi"
```

Use another local Postgres image if Docker cannot pull the default:

```bash
make pulumi-workbench cmd=db-up args="DB_IMAGE=postgres:18"
```

The workbench database is published on host localhost only, so local tools can
connect to `postgresql://statedb:statedb@localhost:55432/statedb`.

Postgres and the IdP are declared in `compose.yml`. Stop them while preserving
database state with:

```bash
make pulumi-workbench cmd=infra-down
```

If you switch Postgres major versions, recreate the throwaway workbench
container and volume first:

```bash
make pulumi-workbench cmd=reset
make pulumi-workbench cmd=db-up args="DB_IMAGE=postgres:18"
```

You can also run the workbench Makefile directly from inside the dev container:

```bash
make -C workbench/pulumi up
```

The local Pulumi home is `workbench/.pulumi-home/` and is intentionally ignored
by git.

## Notes

The workbench is intentionally not a hermetic test. Edit `Pulumi.yaml`, run the
CLI, inspect the DB, and repeat.

If `pulumi up` fails, still run:

```bash
make pulumi-workbench cmd=sql
```

Partial writes are often the most useful signal while tightening protocol
compatibility.
