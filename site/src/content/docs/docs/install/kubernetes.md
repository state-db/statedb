---
title: Kubernetes
description: Install statedb on Kubernetes with the Helm chart.
---

The Helm chart runs statedb as a stateless Deployment. It does not
install a database: point it at PostgreSQL you already run, or at a managed
instance from your cloud provider.

## Install

The chart is not yet published to a chart registry. Install it from a checkout
of the release you want to run:

```sh
git clone --branch v0.1.0 https://github.com/state-db/statedb
cd statedb
helm install statedb examples/helm/statedb \
  --set database.jdbcUrl=jdbc:postgresql://postgres:5432/statedb \
  --set database.user=statedb \
  --set database.existingSecret=statedb-db \
  --set bootstrap.orgName=acme
```

A first install needs the database location, its credentials, and the name of
your first organization. Everything else has a default.

Keep the database password in a Secret you manage, and name it with
`database.existingSecret`. Passing `database.password` directly works for a test
cluster but writes the password into your release values.

## Session encryption

Console sessions are encrypted with a shared key that must be identical across
replicas and stable across upgrades. Rotating it logs everyone out.

The chart generates a key on first install and reuses it on every upgrade. To manage the key yourself, put it in a
Secret and set `sessionExistingSecret`.

## Identity provider

You can configure OpenID Connect from the console after installing, or seed a
provider at install time so the deployment is usable immediately:

```sh
helm upgrade statedb examples/helm/statedb \
  --reuse-values \
  --set oidc.name=okta \
  --set oidc.issuer=https://example.okta.com \
  --set oidc.clientId=0oa1example \
  --set oidc.existingSecret=statedb-oidc \
  --set oidc.adminSubjects='https://example.okta.com:00u1example'
```

A seeded provider is re-applied at every pod start and shows as read-only in the
console, so rotating the client secret means updating the Secret and restarting. `oidc.adminSubjects` takes comma-separated `issuer:subject`
pairs and is what grants site administrator.

## Ingress

The chart's Ingress is off by default. One hostname serves both the console and
the Pulumi API, so there is no path splitting to configure:

```sh
--set ingress.enabled=true \
--set ingress.host=statedb.example.com \
--set ingress.tls.enabled=true \
--set ingress.tls.secretName=statedb-tls
```

The Ingress does not expose the management port that carries health and metrics. Leave it on the cluster network.

## Upgrades

```sh
helm upgrade statedb examples/helm/statedb --reuse-values
```

Each pod applies any pending schema changes before it reports ready, and a
database lock keeps concurrent pods from racing. Because a cold database or a
wait behind another pod's migration can take minutes, the chart has a long startup probe budget; raise `startupProbe.failureThreshold` if your database is
slow to wake.

While old and new versions overlap, only expand-only schema changes are safe;
the migrations guarantee this, not your rollout strategy.

## Common values

| Value | Default | Meaning |
| --- | --- | --- |
| `replicaCount` | `2` | Pods behind the Service |
| `database.jdbcUrl` | none | JDBC URL, required |
| `database.existingSecret` | none | Secret holding the database password |
| `sessionExistingSecret` | none | Secret holding the session key, if you manage it |
| `bootstrap.orgName` | none | First organization, created at startup |
| `oidc.name` | none | Seeds a provider when set |
| `ingress.enabled` | `false` | Expose the service through an Ingress |

The full set is in
[`values.yaml`](https://github.com/state-db/statedb/blob/main/examples/helm/statedb/values.yaml).

The [self-managed guide](/docs/deployment/) covers database requirements, and
[operate statedb](/docs/operations/) covers backups and monitoring.
