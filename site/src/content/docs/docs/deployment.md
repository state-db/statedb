---
title: Self-managed installation
description: Install, configure, and operate statedb on your own container platform or virtual machine.
---

statedb runs as one service against one PostgreSQL database. Port `8080` carries
everything users touch, meaning the Pulumi API and the console, while port
`9000` carries health and metrics for your platform. Put HTTPS in front of
`8080` and keep `9000` on the private network.

## Prepare the database

Use PostgreSQL 14 or newer with LZ4 support. Both the official images and the
major managed services include it, and statedb verifies the capability before
applying any migration, so startup fails with an explicit error rather than
producing a schema it cannot use.

Give statedb its own database and login role. It needs neither superuser rights
nor ownership of the database itself:

```sql
CREATE ROLE statedb LOGIN PASSWORD 'replace-this-password';
GRANT CONNECT ON DATABASE statedb TO statedb;
GRANT USAGE, CREATE ON SCHEMA public TO statedb;
```

Flyway creates the application objects under that role, which is what lets later
migrations alter them.

## Configure the service

Configure the database connection and the session secret for production:

| Variable | Meaning |
| --- | --- |
| `STATEDB_JDBC_URL` | JDBC URL, such as `jdbc:postgresql://db:5432/statedb` |
| `STATEDB_DB_AUTHENTICATION` | `password` by default, or `aws-iam` for RDS IAM authentication |
| `STATEDB_DB_USER` | PostgreSQL login name |
| `STATEDB_DB_PASSWORD` | PostgreSQL password, required for `password` authentication |
| `STATEDB_DB_HOST` | RDS endpoint, required for `aws-iam` authentication |
| `STATEDB_DB_PORT` | RDS port for IAM token signing, with a default of `5432` |
| `STATEDB_DB_REGION` | AWS Region, required for `aws-iam` authentication |
| `STATEDB_SESSION_ENCRYPTION_SECRET` | Secret that encrypts console session cookies |

Use at least 32 random bytes for the session secret, and give every replica the
same value. If replicas disagree, a user's session stops being readable the
moment their next request lands on a different pod.

On a fresh deployment you can also have statedb create the first organization
for you. Setting `STATEDB_BOOTSTRAP_ORG_NAME` opts into this;
`STATEDB_BOOTSTRAP_ORG_DISPLAY_NAME` is optional and defaults to the name. Both
are ignored once the organization exists.

### AWS IAM database authentication

With `aws-iam` authentication, statedb reads credentials only from the ECS
task-role endpoint. It creates a new RDS authentication token when the connection
pool requests credentials. Grant the task role `rds-db:connect` for the exact
database resource and PostgreSQL user. Do not put AWS access keys in the
container.

The CloudFormation installer in `deploy/aws` configures this authentication path
and TLS hostname verification. It can create a small VPC or use an existing VPC
and four supplied subnets. Existing-network mode does not change gateways,
routes, endpoints, network ACLs, or DNS configuration.

### First identity provider

You can seed one OpenID Connect provider from the environment, which saves a
manual database insert on a new deployment:

| Variable | Meaning |
| --- | --- |
| `STATEDB_OIDC_BOOTSTRAP_NAME` | Short provider name |
| `STATEDB_OIDC_BOOTSTRAP_ISSUER` | OpenID Connect issuer URL |
| `STATEDB_OIDC_BOOTSTRAP_CLIENT_ID` | Application client ID |
| `STATEDB_OIDC_BOOTSTRAP_CLIENT_SECRET` | Application client secret |
| `STATEDB_OIDC_DEFAULT_PROVIDER` | Provider used by the default sign-in button |
| `STATEDB_ADMIN_SUBJECTS` | Comma-separated issuer/subject pairs for site administrators |

`STATEDB_OIDC_BOOTSTRAP_NAME` is the switch: set it and the other three become
required, so a half-configured provider fails at startup instead of at first
login. These values are re-applied at every start, which makes rotating the
client secret a matter of changing the environment and restarting. The console
shows this provider as environment-managed and read-only.

Any further providers you add from **Manage → Providers** are stored in
PostgreSQL and editable in the console.

## Recovery access

Recovery access is off unless you set `STATEDB_LOCAL_GLASS_PASSWORD_HASH` to a
bcrypt hash in Modular Crypt Format. It exists for one situation: your identity
provider is down and you need to get back in.

Two things keep it contained. It accepts loopback connections only, and it turns
itself off entirely whenever an enabled OIDC provider exists. That second
condition is checked both at sign-in and on every session restore, so restoring
OIDC service also ends any recovery session already open.

:::caution
`STATEDB_LOCAL_GLASS_ALLOW_REMOTE=true` removes the loopback restriction. Use it
only in an isolated evaluation environment, never in production.
:::

## Health checks and traffic

| Purpose | Port | Path |
| --- | --- | --- |
| User traffic | `8080` | `/` and `/pulumi` |
| Readiness | `9000` | `/q/health/ready` |
| Liveness | `9000` | `/q/health/live` |
| Liveness (public, for a load balancer that can't reach `9000`) | `8080` | `/health/live` |

Prefer the management-port `/q/health/live` when your platform can target it.
`/health/live` exists for a platform whose health check can only reach the
public port; it never touches the database or a worker thread, so a task busy
with Pulumi writes never looks dead to it.

Allow at least 600 seconds of health-check grace for a new task. A database cold
start, or a wait behind another replica's migration lock, can legitimately take
minutes, and a tighter deadline will kill tasks that were about to succeed.

## Running several replicas

Update leases and session data both live in PostgreSQL, so any healthy replica
can serve any request. The requirements are just that every replica shares a
database and a session-encryption secret.

Migrations are safe under this model too. Flyway takes a database lock, one
replica applies the schema change, and the rest wait and then verify the result.

## Sizing for concurrent deploys

0.25 vCPU / 512 MB is not enough for concurrent deploys of stacks with
hundreds of resources. Size for at least 1 vCPU / 2 GB per task, and keep
enough tasks running that one task's worth of concurrent deploys stays within
what its memory and CPU can hold.

## Write-path tuning

| Variable | Meaning |
| --- | --- |
| `STATEDB_DB_REWRITE_BATCHED_INSERTS` | pgjdbc multi-row insert rewriting; `true` by default |
| `STATEDB_DB_INSERT_BATCH_SIZE` | Hibernate JDBC insert batch size; `50` by default |

These speed up journal and event writes under concurrent load. Rewriting only
applies to batches, so an insert batch size of `0` also turns it off and falls
back to one statement per row.

## Upgrades

Replacing the application image is the entire upgrade procedure. Every Flyway
migration ships inside the image and runs at startup, so there is no separate
migration job to sequence.

Keep the old tasks serving until a new one reports ready, and enable automatic
rollback for services that never reach a steady state. Allow at least 10 seconds
between `SIGTERM` and `SIGKILL` so in-flight requests can finish. While old and
new tasks overlap, only expand-only migrations are safe. That constraint is on
the migrations themselves, not on your deployment tooling.

## Backups

Every statedb record is in PostgreSQL: checkpoints, history, users,
organizations, policies, and tokens. Your existing backup process therefore
already covers statedb, whether that means managed snapshots, WAL archiving, or
`pg_dump`.

Test the restore rather than assuming it. Restore into a separate database,
start the same statedb version against it, and export a stack with the Pulumi
CLI. That exercises the whole path from stored rows back to a checkpoint the CLI
accepts.

## Source code and license

state-db uses `AGPL-3.0-only`. See [license and Pulumi compatibility](/docs/license/) for the license text and source-code link.
