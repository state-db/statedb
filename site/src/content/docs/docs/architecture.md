---
title: Architecture
description: How statedb handles a Pulumi update, what it keeps in PostgreSQL, and where to read the detailed design.
---

statedb is one application and one PostgreSQL database. The application serves
the Pulumi HTTP API and the web console from the same container. It holds no
state of its own, so every replica is interchangeable and a database backup is a
complete backup.

statedb stores state. It never runs your Pulumi program or calls a cloud
provider; the CLI does that on your machine or in CI.

## Request paths

```text
Pulumi CLI → /pulumi/api/* → API token or lease ─┐
Browser    → /api/*        → console session ────┴→ PostgreSQL
Browser    → /console/*    → static console application
```

| Path | Port | Purpose |
| --- | --- | --- |
| `/pulumi/api/*` | `8080` | The Pulumi backend protocol |
| `/api/*` | `8080` | The console's own API, behind a browser session |
| `/console/*` | `8080` | The console application |
| `/q/health/*`, `/q/metrics` | `9000` | Health and metrics for your platform |

The Pulumi API sits under `/pulumi` so that the console can own the rest of the
origin. That is why the login URL ends in `/pulumi`.

## What happens during an update

1. The CLI creates an update record, then starts it. Starting takes the stack's
   **lease**: one row per stack in PostgreSQL, so a second `pulumi up` on the
   same stack is refused whichever replica it reaches.
2. As resources change, the CLI sends **journal entries**. statedb appends each
   batch in one transaction, in the order the CLI assigned, so a crash cannot
   leave half a batch behind.
3. The CLI also sends **engine events**. These feed the console's run history
   and are never used to rebuild state.
4. The CLI renews the lease while it works. A lease lasts five minutes from its
   last renewal, so a run that dies releases its stack without anyone stepping
   in.
5. Completing the update records its result, releases the lease, and advances
   the stack's version, all in one transaction.

When the CLI next asks for the stack, statedb rebuilds the checkpoint from the
latest **snapshot** plus the journal entries written after it.

## Storage

| Data | Role |
| --- | --- |
| Journal entries | The source of truth for every state change, kept in order |
| Snapshots | Full checkpoints that shorten replay; older ones are pruned |
| Engine events | Run history for the console; finished runs are compressed |
| Identity and access | Users, organizations, roles, grants, and token hashes |
| Service keyset | The encryption keys for the service-managed secrets provider |

**Compaction** runs in the background. It folds journal entries into a new
snapshot so that reads stay fast as history grows. Compaction and updates
exclude each other on the same stack through a database row lock, so a snapshot
is never taken halfway through an update.

There is no queue, cache, or object store. Background work is coordinated
through PostgreSQL, which is also why any number of replicas can run side by
side.

## Access control

Three kinds of credential stay separate:

- A **console session** is an encrypted cookie issued after OpenID Connect
  sign-in. It is scoped to `/api/`, so the Pulumi API never sees it.
- An **API token** identifies a person or a service account to the Pulumi API.
  Only its hash is stored.
- An **update lease token** authorizes writes to one running update on one
  stack.

Sessions and API tokens identify who is asking. [Cedar](https://www.cedarpolicy.com/)
policies then decide what that identity may do, from its role in the
organization and any per-stack grants. A stack you cannot read answers `404`,
the same as a stack that does not exist.

[Authentication and access](/docs/authentication/) describes the roles.

## Secrets

Stacks that use a passphrase or a cloud KMS encrypt on the client, and statedb
only ever stores ciphertext. Stacks that use the service-managed provider are
encrypted with a keyset held in PostgreSQL, and ciphertext is bound to its
stack so it cannot be decrypted through another one.

That keyset protects a leaked checkpoint or a partial dump. It does not protect
against someone who has the whole database, so use an external provider for
stacks where that matters.

## Design reference

The repository holds the detailed design, written against the code:

| Topic | Document |
| --- | --- |
| Modules and request flow | [Architecture](https://github.com/state-db/statedb/blob/main/design/architecture.md) |
| Updates, journals, and compaction | [Pulumi state lifecycle](https://github.com/state-db/statedb/blob/main/design/pulumi-state-lifecycle.md) |
| Tables and invariants | [Database model](https://github.com/state-db/statedb/blob/main/design/db-schema.md) |
| Sessions, tokens, and Cedar | [Authentication and authorization](https://github.com/state-db/statedb/blob/main/design/auth.md) |
| Private stacks and grants | [Stack visibility and grants](https://github.com/state-db/statedb/blob/main/design/stack-sharing.md) |
| The service secrets provider | [Service secrets](https://github.com/state-db/statedb/blob/main/design/secrets.md) |
| Startup migrations and upgrades | [Migrations](https://github.com/state-db/statedb/blob/main/design/migrations.md) |
| Capacity, leases, and recovery | [Load and recovery](https://github.com/state-db/statedb/blob/main/design/load-resilience.md) |
| Database credentials and RDS IAM | [Database connections](https://github.com/state-db/statedb/blob/main/design/database-connections.md) |
| The console | [Console](https://github.com/state-db/statedb/blob/main/design/console-ux.md) |
| GitHub pull request reports | [GitHub PR reports](https://github.com/state-db/statedb/blob/main/design/pr-enrichment.md) |
| The native image | [Native image](https://github.com/state-db/statedb/blob/main/design/native-image.md) |
| How releases are built and gated | [Releases](https://github.com/state-db/statedb/blob/main/design/release.md) |

The [design index](https://github.com/state-db/statedb/tree/main/design) lists
them all, and [Pulumi CLI compatibility](/docs/compatibility/) explains how the
protocol is tested.
