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

Clear an interrupted workbench update/lock:

```bash
make pulumi-workbench cmd=force-unlock
```

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
