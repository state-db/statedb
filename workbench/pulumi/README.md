# Pulumi Workbench

This is a mutable Pulumi project for running the real Pulumi CLI against the
local state-db backend and then inspecting what landed in Postgres.

## Flow

Start the stable workbench database:

```bash
make pulumi-workbench cmd=db-up
```

Start the app in another terminal:

```bash
make pulumi-workbench cmd=app
```

The workbench app target disables Quarkus continuous testing. It runs with
bootstrap credentials in the environment, and continuous tests would inherit
those values and pollute bootstrap-related test assertions.

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

## Defaults

- Backend URL: `http://localhost:8080/pulumi`
- Bootstrap org: `acme`
- Stack: `dev` (`acme/dev` when passed to the Pulumi CLI)
- Raw token: `state-db-workbench-token`
- Postgres container: `state-db-workbench-postgres`
- Postgres image: `postgres:16`
- Postgres host port: `55432`

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
