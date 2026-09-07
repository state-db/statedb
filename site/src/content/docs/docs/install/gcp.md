---
title: Google Cloud
description: Status of the planned Google Cloud installation and what to use instead.
---

:::note[Planned, not available]
There is no Google Cloud installer for statedb yet. This page holds the URL it
will occupy.
:::

statedb already runs well on Google Cloud today. On GKE, use the
[Helm chart](/docs/install/kubernetes/); on Cloud Run, follow the
[self-managed guide](/docs/deployment/). Either way, back it with a Cloud SQL
for PostgreSQL instance.

Two details are worth checking on Google Cloud specifically. statedb requires
PostgreSQL 14 or newer with LZ4 support and refuses to start without it, so
confirm that on your chosen Cloud SQL version before deploying. And because
console sessions are encrypted with a shared secret, every revision or replica
must receive the identical `STATEDB_SESSION_ENCRYPTION_SECRET`. Secret Manager
is the natural place to keep it.

When the packaged installer arrives it will not change the service boundary: one
statedb service, one private PostgreSQL database, HTTPS in front, and OpenID
Connect for console access.
