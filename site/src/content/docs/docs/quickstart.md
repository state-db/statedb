---
title: Quickstart
description: Run statedb from source and point a CLI at it.
---

:::caution
statedb is under active development. A published container image and a
docker-compose evaluation bundle are planned; until then, run from source.
:::

## Prerequisites

- Docker (the dev database and supporting services run in containers)
- mise (installs the pinned Java, Bun, and Go toolchains)
- A Postgres 15+ instance for production use

## Run from source

Clone the repository, install the pinned toolchain, and start the local
workbench in hot-reload mode. The workbench starts Postgres and the mock
identity provider through Docker, then runs the backend and console:

```sh
git clone https://github.com/state-db/statedb.git
cd statedb
brew install mise
mise trust mise.toml
mise install
make pulumi-workbench cmd=app-dev
```

The backend listens on `http://localhost:8080`; the console is at
`http://localhost:5173`.

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

Open `http://localhost:5173` in a browser to see the stack, its resources, and
its first update in the console.

## Next steps

- [Deployment](/docs/deployment/) — run it for real: Postgres, env vars,
  replicas, backups.
- [Authentication](/docs/authentication/) — connect your identity provider.
