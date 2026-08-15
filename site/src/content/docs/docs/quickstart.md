---
title: Quickstart
description: Run statedb from source and point a CLI at it.
---

:::caution
statedb is under active development. A published container image and a
docker-compose evaluation bundle are planned; until then, run from source.
:::

## Prerequisites

- Docker (the build and the dev database run in containers)
- A Postgres 15+ instance for production use

## Run from source

Clone the repository and start the service in dev mode, which brings up an
ephemeral Postgres automatically:

```sh
git clone https://github.com/state-db/statedb.git
cd statedb
devcontainer up --workspace-folder .
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
```

The backend and console are now listening on `http://localhost:8080`.

## Point your CLI at it

```sh
pulumi login http://localhost:8080
```

The CLI opens your browser to approve the login and receives a token. From
there, your normal workflow applies:

```sh
pulumi new aws-typescript
pulumi up
```

Open `http://localhost:8080` in a browser to see the stack, its resources, and
its first update in the console.

## Next steps

- [Deployment](/docs/deployment/) — run it for real: Postgres, env vars,
  replicas, backups.
- [Authentication](/docs/authentication/) — connect your identity provider.
