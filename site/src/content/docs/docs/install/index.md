---
title: Choose an installation
description: Compare the available ways to install statedb and what every installation needs.
---

statedb is a single service backed by a single PostgreSQL database. Every
path uses that shape. There is no second web tier, queue, or object store to
provision.

## Available paths

| Path | Status | Best for |
| --- | --- | --- |
| [Docker Compose](/docs/quickstart/) | Available | Evaluating statedb on your own machine |
| [Kubernetes](/docs/install/kubernetes/) | Available | A cluster you already run, via the Helm chart |
| [Self-managed](/docs/deployment/) | Available | Any other container platform or VM |
| [AWS CloudFormation](/docs/install/aws/) | Available | A managed AWS install on ECS and RDS |
| [Google Cloud](/docs/install/gcp/) | No installer | GKE or Cloud Run, using the Helm chart or self-managed guide |
| [Microsoft Azure](/docs/install/azure/) | No installer | AKS or Container Apps, using the Helm chart or self-managed guide |

## What every installation needs

- **PostgreSQL 14 or newer, built with LZ4.** The official PostgreSQL images and
  the major managed services all include it. statedb checks for LZ4 before it
  touches the schema and refuses to start without it.
- **One or more statedb replicas**, all pointed at that database.
- **HTTPS in front of port `8080`**, which carries both the Pulumi API and the
  console.
- **Port `9000` kept private.** It exposes health and metrics endpoints and is
  not meant to face the internet.
- **A stable session-encryption secret**, identical across every replica.
  Browser sessions are encrypted with it, so replicas that disagree will log
  users out as their requests move between pods.
- **An OpenID Connect provider** for everyday console access. The local recovery
  login is for an IdP outage only.

## After it starts

1. Open the console URL your installation reports.
2. Configure an OpenID Connect provider before you let anyone else in.
3. Create a CLI token from the **Tokens** page.
4. Run `pulumi login https://statedb.example.com/pulumi`.
5. Confirm the CLI is talking to the right backend with
   `pulumi whoami --verbose`.

[Authentication and access](/docs/authentication/) covers steps 2 and 3 in
detail.
