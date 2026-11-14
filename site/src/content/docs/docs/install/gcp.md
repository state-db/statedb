---
title: Google Cloud
description: How to run statedb on Google Cloud.
---

:::note[No Google Cloud installer]
statedb has no packaged Google Cloud installer.
:::

You can still run statedb on Google Cloud. On GKE, use the
[Helm chart](/docs/install/kubernetes/); on Cloud Run, follow the
[self-managed guide](/docs/deployment/). Either way, back it with a Cloud SQL
for PostgreSQL instance.

Two things to check on Google Cloud: statedb requires
PostgreSQL 14 or newer with LZ4 support and refuses to start without it, so
confirm that on your chosen Cloud SQL version before deploying, and because
console sessions are encrypted with a shared secret, every revision or replica
must receive the identical `STATEDB_SESSION_ENCRYPTION_SECRET`. Secret Manager
is the natural place to keep it.
