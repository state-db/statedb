---
title: What is statedb?
description: A self-hosted state backend with a web console, compatible with the Pulumi CLI.
---

statedb is a self-hosted state management backend compatible with the Pulumi
command-line tool. It implements the HTTP state storage protocol the CLI already
speaks and pairs it with a web console for browsing stacks, resources, and
history — without a managed cloud service in the loop.

You configure your CLI to point at your statedb instance:

```sh
pulumi login https://statedb.internal.example.com
```

Everything else about your workflow — `up`, `preview`, `refresh`, `destroy`,
stack references, secrets — stays exactly as it is. If the CLI breaks, the
backend is wrong; that contract is the core of the project.

## What you get

- **Stack storage** — state checkpoints, stack CRUD, tags, and rename across
  organizations and projects.
- **Locking** — leases prevent concurrent updates from corrupting state; a
  crashed run's lease expires on its own.
- **History** — the full update log per stack, browsable in the console.
- **A web console** — current resources, update history and diffs, stack
  outputs (secrets masked), and per-stack activity. Served from the same
  process as the backend.

## Architecture at a glance

statedb is a single deployable service backed by Postgres. State blobs and
metadata live in the database, which is what enables querying, history, and the
console — capabilities a bucket of JSON files can't offer. Schema changes are
owned by Flyway migrations and apply automatically at startup, safely even with
multiple replicas.

## Where to go next

- [Quickstart](/docs/quickstart/) — run statedb and point a CLI at it.
- [How we test compatibility](/docs/compatibility/) — replay, integrity
  verification, real-CLI smoke, and fault injection.
- [Migrating in and out](/docs/migration/) — no lock-in, by design.
- [License & pricing](/docs/license/) — free for up to ten active stacks.

:::note
Pulumi is a trademark of Pulumi Corporation. OpenTofu is a trademark of The
Linux Foundation. statedb is an independent project and is not affiliated with,
endorsed by, or sponsored by either.
:::
