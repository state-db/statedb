# Design

state-db is a Pulumi state backend and web console backed by PostgreSQL.
These documents explain the implementation, its boundaries, and the decisions
that matter when changing it. Installation and operator instructions live in
the [user documentation](../site/src/content/docs/docs/).

Start with [Architecture](architecture.md), then follow the part of the system
you want to understand:

| Area | Reference |
| --- | --- |
| State storage | [Database model](db-schema.md), [Pulumi state lifecycle](pulumi-state-lifecycle.md) |
| Access control | [Authentication and authorization](auth.md), [Stack visibility and grants](stack-sharing.md) |
| Encryption | [Service secrets](secrets.md) |
| Browser and reports | [Console](console-ux.md), [GitHub PR reports](pr-enrichment.md) |
| Runtime | [Load and recovery](load-resilience.md), [Native image](native-image.md) |
| Deployment | [Database connections](database-connections.md), [Migrations](migrations.md), [Releases](release.md) |

The [Flyway migrations](../modules/persistence/src/main/resources/db/migration/)
define the schema. [schema.sql](schema.sql) is its generated reference.
The [CLI smoke suite](../modules/pulumi-cli-smoke/README.md) explains how the
project verifies Pulumi compatibility.

OpenTofu support is not implemented. The database reserves a workspace kind
for it, but the application has no OpenTofu backend.
