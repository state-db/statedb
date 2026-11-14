---
title: Microsoft Azure
description: How to run statedb on Microsoft Azure.
---

:::note[No Azure installer]
statedb has no packaged Azure installer.
:::

You can still run statedb on Azure. On AKS, use the
[Helm chart](/docs/install/kubernetes/); on Container Apps, follow the
[self-managed guide](/docs/deployment/). Either way, back it with Azure Database
for PostgreSQL Flexible Server.

Two Azure-specific notes: statedb requires PostgreSQL 14 or newer with LZ4
support and refuses to start without it, so confirm that on your chosen Flexible
Server version before deploying, and every replica needs the same
`STATEDB_SESSION_ENCRYPTION_SECRET`, since console sessions are encrypted with
it. Key Vault is the natural home for that value.
