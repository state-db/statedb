---
title: AWS CloudFormation
description: Architecture, parameters, and current status of the AWS CloudFormation installer.
---

:::caution[This installer is not released]
The AWS package is still in development. Do not deploy the template from source:
it carries image markers that a release build has to replace with digest-pinned
images. The source currently lives on the development branch
`codex/aws-packaging-mvp` as engineering evidence, not as a usable installer.
:::

The planned package is a single CloudFormation stack that owns everything
statedb needs on AWS: the application service, the database, secrets, IAM roles,
and network rules.

## What the stack creates

The default profile provisions two 0.25-vCPU, 512-MiB tasks on AWS Fargate
behind an Amazon ECS Express Mode service with managed HTTPS. Behind them sits a
private, encrypted PostgreSQL 17 RDS instance with 80 GiB of gp3 storage that
grows automatically to 500 GiB, and seven days of backups.

Two Secrets Manager values hold the browser session secret and initial recovery
access. A one-time task creates the PostgreSQL application role during stack
creation.

The database is Single-AZ by default. If you are taking this to production,
Multi-AZ is the first change worth making.

## How requests and credentials flow

```text
Pulumi CLI or browser
        |
        v
ECS Express Mode HTTPS endpoint
        |
        v
Two statedb tasks on Fargate
        |
        v
Private PostgreSQL 17 on RDS
```

The application tasks never hold a database password. Their ECS task role mints
short-lived IAM database tokens instead, and the only component that touches the
RDS administrator secret is the one-time task that creates the application role.

The RDS instance has no public address and accepts traffic only from the statedb
task security group.

## Network choices

`NetworkMode=CreateNew` builds a small VPC with two public application subnets
and two isolated database subnets, and no NAT Gateway.

`NetworkMode=UseExisting` takes a VPC and four subnets you supply. Application
subnets may be public or private; private ones need NAT or the relevant VPC
endpoints.

:::caution
Treat VPC and subnet values as creation-time decisions. Changing them later can
replace network resources or the database itself, so read the CloudFormation
change set before applying any such update.
:::

## Parameters worth a decision

| Parameter | Default | Meaning |
| --- | --- | --- |
| `NetworkMode` | `CreateNew` | Create a VPC or use an existing one |
| `ApplicationSubnetType` | `Public` | Public or private application routing |
| `OrganizationName` | `default` | Name of the first statedb organization |
| `DbInstanceClass` | `db.t4g.small` | RDS instance size |
| `DbAllocatedStorage` | `80` | Initial gp3 storage in GiB |

The stack asks for no credential. It generates the recovery password itself
during creation, so there is nothing to prepare before you launch it.

The released template will pin immutable image references. Operators can replace both references with approved regional ECR copies.

## Installation flow, once released

1. Create the stack with `CAPABILITY_IAM`.
2. Wait for RDS and the ECS service to become ready. Expect 15 to 25 minutes,
   most of it RDS.
3. Read `ConsoleUrl`, `PulumiLoginCommand`, and `BreakGlassPasswordCommand` from
   the stack outputs.
4. Run `BreakGlassPasswordCommand` to read the generated recovery password from
   Secrets Manager.
5. Sign in as `admin` with that password and configure an OpenID Connect provider
   immediately. Recovery access disables itself as soon as a provider is
   enabled.

## How recovery access is protected

The stack generates a 48-character random password during creation and stores it
in Secrets Manager, where an operator reads it. The application never receives
that password. It receives only a PBKDF2-SHA256 verifier, held in an SSM
parameter, so a compromised task yields nothing an attacker can sign in with.

The bootstrap Lambda that generates the credential can write both halves and
read the verifier for idempotency, but it cannot read the password or anything
else that would let it reconstruct one. No role in the stack can both obtain the
password and serve the console with it.

Recovery access does not expire. It is honored only while no OpenID Connect
provider is enabled, and disabling that provider row reopens it — which is how
you get back in when an identity provider is unavailable.

## Deletion and storage

CloudFormation snapshots the RDS instance when it deletes or replaces the
database. Deletion protection is off in the development template, which is
appropriate for testing and not for production. Review it before you rely on the
stack.

RDS storage grows but never shrinks, so raising `DbAllocatedStorage` sets a new
floor you cannot walk back.
