---
title: What is statedb?
description: What statedb stores, how it fits into the Pulumi CLI workflow, and the vocabulary used throughout these docs.
---

statedb is a state backend for the Pulumi CLI that you run yourself. It keeps
checkpoints, update history, users, and tokens in a PostgreSQL database you
control, and gives you a console to browse them.

Nothing about the CLI changes. There is no plugin to install and no patched
build to distribute. You point Pulumi at your own URL and carry on:

```sh
pulumi login https://statedb.example.com/pulumi
```

From that point, `preview`, `up`, `refresh`, and `destroy` behave exactly as
they did before. What changes is where the state lands, and who can see it.

## Concepts

**Organization** is the tenancy boundary. Users, service accounts, and stacks
all belong to an organization, and every access grant is scoped to one. A single
deployment can host as many as you need.

**Projects and Stacks** mean what they mean in `Pulumi.yaml` and `pulumi stack init`.

**Update** is one run of `up`, `preview`, `refresh`, or `destroy`. statedb
records each update's events alongside the checkpoint it produced, which is what
the console's history view reads back.

**Lease** is the exclusive claim a running update holds on its stack. Only one
update can hold a stack's lease at a time, which is how two engineers running
`pulumi up` against the same stack cannot corrupt each other's work. A lease
expires an hour after its last heartbeat, so an interrupted run releases the
stack on its own.

## What statedb stores

- Pulumi checkpoints, along with stack metadata, tags, and rename history
- Every update's events and its pending-operation recovery data
- Organizations, users, roles, per-stack grants, and tokens
- The keyset for the service-managed secrets provider, kept apart from
  checkpoint data

Everything above lives in PostgreSQL. There is no queue, object store, or second
application process to operate.

## How it runs

statedb ships as a single container that serves both surfaces: the Pulumi API
your CLI talks to, and the web console, on the same origin. It manages its own
database schema, applying any pending changes at startup before it accepts
traffic, so an upgrade is just a new image.

That combination is what keeps self-hosting cheap. Point the container at a
managed PostgreSQL instance and your provider handles backups, patching, and
failover, while your existing container platform handles rollouts and health
checks. Neither job is specific to statedb, so running it adds no new
operational discipline.

Replicas share the database, so you can run as many as you want behind a load
balancer. Update leases and session state both live in PostgreSQL, which means a
request can land on any healthy replica.

## Where to go next

If you want to see it working, the [quickstart](/docs/quickstart/) gets it
running locally with Docker Compose in about five minutes. If you already know
you want to deploy it, start at [choosing an installation](/docs/install/). If
you have stacks on another backend today, [migrating a stack](/docs/migration/)
is the smallest useful first step.

:::note
Pulumi is a trademark of Pulumi Corporation. statedb is an independent project.
Pulumi Corporation does not endorse or sponsor statedb.
:::
