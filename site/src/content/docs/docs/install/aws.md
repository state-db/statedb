---
title: AWS CloudFormation
description: Architecture, parameters, and installation flow of the AWS CloudFormation installer.
---

:::caution[Not yet published]
Do not deploy the template from source. It carries image markers that the
release build replaces with digest-pinned images.
:::

The package is a single CloudFormation stack that owns everything statedb needs
on AWS: the application service, the database, secrets, IAM roles, and network
rules.

## What the stack creates

The default profile provisions two 1-vCPU, 2-GiB tasks on AWS Fargate
behind an Amazon ECS Express Mode service with managed HTTPS. Behind them sits a
private, encrypted PostgreSQL 17 RDS instance with 80 GiB of gp3 storage that
grows automatically to 500 GiB, and seven days of backups.

Two Secrets Manager values hold the browser session secret and initial recovery
access. A one-time task creates the PostgreSQL application role during stack
creation.

The database is Single-AZ by default. Multi-AZ is the first change to make for
production.

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

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `NetworkMode` | `CreateNew` | Create a VPC or use an existing one |
| `ApplicationSubnetType` | `Public` | Public or private application routing |
| `OrganizationName` | `default` | Name of the first statedb organization |
| `AppTaskSize` | `1vCPU-2GB` | Fargate CPU and memory for each application task |
| `DbInstanceClass` | `db.t4g.small` | RDS instance size |
| `DbAllocatedStorage` | `80` | Initial gp3 storage in GiB |

The stack asks for no credential. It generates the recovery password itself
during creation, so there is nothing to prepare before you launch it.

The released template pins immutable image references. Operators can replace both references with approved regional ECR copies.

## Installation flow

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
database. Deletion protection is off in the template, which suits testing but not
production. Enable it before you rely on the stack.

RDS storage grows but never shrinks, so raising `DbAllocatedStorage` sets a new
floor you cannot walk back.
