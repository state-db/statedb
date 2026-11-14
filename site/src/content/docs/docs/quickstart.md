---
title: Quickstart
description: Run statedb locally with Docker Compose and connect the Pulumi CLI in a few minutes.
---

Run statedb locally with Docker Compose, point a real Pulumi CLI at it, and
watch a stack appear in the console. You need Docker and the Pulumi CLI, and
about five minutes.

This is an evaluation setup. It uses a password sign-in instead of an identity
provider and keeps everything on your machine, so it is not a template for a
real deployment. When you are ready for that, see
[choosing an installation](/docs/install/).

## Start statedb

Download the example stack and start it:

```sh
curl -O https://raw.githubusercontent.com/state-db/statedb/main/examples/compose.yml
docker compose -f compose.yml up
```

That starts statedb and a PostgreSQL database. The service creates its own
schema on first boot, so there is nothing to migrate or seed by hand.

Open `http://localhost:8080` and sign in with the password `statedb`. The
username is ignored. You land in an organization called `acme`, empty for now.

:::caution
The example file hard-codes a password and a session secret so the stack starts
with no configuration, and it allows password sign-in from outside the
container. That is fine on your laptop and unsafe anywhere else. A real
deployment uses an identity provider, and password sign-in switches itself off
as soon as one is enabled.
:::

## Connect the CLI

In another terminal, log in against the local backend. The `/pulumi` suffix is required because the Pulumi API is mounted on that path:

```sh
pulumi login http://localhost:8080/pulumi
```

Your browser opens statedb, you sign in, and the CLI collects its token.

Now use Pulumi normally. From any project directory:

```sh
pulumi stack init acme/dev
pulumi preview
```

Refresh the console and the stack is there, with the update you just ran and the
resources it planned.

## Try the console

To see more in the console, run a few more commands against the stack: `pulumi up`, a change, another `pulumi up`,
then `pulumi destroy`. Each one shows up under the stack's activity, with its
resource changes, duration, and result.

The [console guide](/docs/management/) covers the administrative screens:
organizations, identity providers, service tokens, and the operations view for
locks and compaction.

## Stop it

```sh
docker compose -f compose.yml down
```

That stops both containers and keeps the database volume, so the next `up`
starts where you left off. Add `-v` to delete the data too.

## Next steps

For a real deployment, [choose an installation](/docs/install/). If you have
stacks on another backend, [migrate one](/docs/migration/) next.

Building from source is only necessary if you intend to change statedb itself;
the repository's `CONTRIBUTING.md` covers that workflow.
