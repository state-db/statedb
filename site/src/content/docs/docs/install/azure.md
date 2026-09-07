---
title: Microsoft Azure
description: Status of the planned Microsoft Azure installation and what to use instead.
---

:::note[Planned, not available]
There is no Azure installer for statedb yet. This page holds the URL it will
occupy.
:::

Nothing prevents running statedb on Azure in the meantime. On AKS, use the
[Helm chart](/docs/install/kubernetes/); on Container Apps, follow the
[self-managed guide](/docs/deployment/). Either way, back it with Azure Database
for PostgreSQL Flexible Server.

Two Azure-specific notes. statedb requires PostgreSQL 14 or newer with LZ4
support and refuses to start without it, so confirm that on your chosen Flexible
Server version before deploying. And every replica needs the same
`STATEDB_SESSION_ENCRYPTION_SECRET`, since console sessions are encrypted with
it. Key Vault is the natural home for that value.

The packaged installer, when it lands, will keep the same service boundary: one
statedb service, one private PostgreSQL database, HTTPS in front, and OpenID
Connect for console access.
