---
title: Self-managed installation
description: Install and configure statedb on your own container platform or virtual machine.
---

statedb runs as one service against one PostgreSQL database. Port `8080` carries
everything users touch, meaning the Pulumi API and the console, while port
`9000` carries health and metrics for your platform. Put HTTPS in front of
`8080` and keep `9000` on the private network.

## Get the image

Releases are published to the GitHub Container Registry for `linux/amd64` and
`linux/arm64`:

```sh
docker pull ghcr.io/state-db/statedb:{{version}}
```

| Tag | Meaning |
| --- | --- |
| `X.Y.Z` | One release. It is never repointed. |
| `X.Y` | The latest patch of that minor release |
| `latest` | The latest release |

Run a specific release in production, not `latest`. For a reference that cannot
change, pin the digest that `docker buildx imagetools inspect` reports for the
tag, as in `ghcr.io/state-db/statedb:{{version}}@sha256:...`. Each
[GitHub Release](https://github.com/state-db/statedb/releases) lists its changes
and attaches the software bills of materials.

### Verify the image

Each platform image carries a build provenance attestation and a CycloneDX SBOM
attestation, signed by the release build workflow. Verification needs the
[GitHub CLI](https://cli.github.com/) and `jq`. Look up the digest for your
platform, then verify it:

```sh
digest=$(docker buildx imagetools inspect ghcr.io/state-db/statedb:{{version}} \
  --format '{{json .Manifest}}' |
  jq -r '.manifests[] | select(.platform.architecture == "amd64") | .digest')

gh attestation verify "oci://ghcr.io/state-db/statedb@$digest" \
  --repo state-db/statedb \
  --signer-workflow state-db/statedb/.github/workflows/native.yml
```

Use `arm64` for an ARM host. Add `--predicate-type https://cyclonedx.org/bom`
to verify the SBOM attestation instead.

## Prepare the database

Use PostgreSQL 14 or newer with LZ4 support. The official images and the major
managed services include it. statedb verifies the capability before applying any
migration and fails at startup with an explicit error if it is missing.

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
for you. Set `STATEDB_BOOTSTRAP_ORG_NAME` to enable this;
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

Setting `STATEDB_OIDC_BOOTSTRAP_NAME` makes the other three required, so a
half-configured provider fails at startup instead of at first login. These
values are re-applied at every start, so rotating the client secret means
changing the environment and restarting. The console shows this provider as
environment-managed and read-only.

Any further providers you add from **Manage → Providers** are stored in
PostgreSQL and editable in the console.

## Recovery access

Recovery access is off unless you set `STATEDB_LOCAL_GLASS_PASSWORD_HASH` to a
bcrypt hash in Modular Crypt Format. It exists for one situation: your identity
provider is down and you need to get back in.

It accepts loopback connections only, and it turns itself off whenever an
enabled OIDC provider exists. That second condition is checked at sign-in and on
every session restore, so restoring OIDC service also ends any recovery session
already open.

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
start, or a wait behind another replica's migration lock, can take minutes, and
a tighter deadline kills tasks that were about to succeed.

## Next steps

[Operate statedb](/docs/operations/) covers upgrades, backups, replicas,
sizing, and monitoring. [Authentication and access](/docs/authentication/)
covers identity providers, roles, and tokens.

## Source code and license

state-db uses `AGPL-3.0-only`. See [license and Pulumi compatibility](/docs/license/) for the license text and source-code link.
