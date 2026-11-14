---
title: What is statedb?
description: What statedb stores, how it fits into the Pulumi CLI workflow, and the vocabulary used throughout these docs.
---

statedb is a state backend for the Pulumi CLI that you run yourself. It keeps
checkpoints, update history, users, and tokens in a PostgreSQL database you
control, and gives you a console to browse them.

The CLI needs no plugin or patched build. Point Pulumi at your own URL:

```sh
pulumi login https://statedb.example.com/pulumi
```

From that point, `preview`, `up`, `refresh`, and `destroy` behave as they do on
any other backend. Only the place the state is stored, and who can see it,
differs.

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

Point the container at a managed PostgreSQL instance and your provider handles
backups, patching, and failover, while your container platform handles rollouts
and health checks.

Replicas share the database, so you can run several behind a load balancer.
Update leases and session state both live in PostgreSQL, so a request can land on
any healthy replica.

## Where to go next

To see it working, the [quickstart](/docs/quickstart/) runs it locally with
Docker Compose in about five minutes. To deploy it, start at
[choosing an installation](/docs/install/). To move existing stacks, see
[migrating a stack](/docs/migration/).

:::note
Pulumi is a trademark of Pulumi Corporation. statedb is an independent project.
Pulumi Corporation does not endorse or sponsor statedb.
:::
