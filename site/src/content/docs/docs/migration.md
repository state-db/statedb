---
title: Migrating in and out
description: Moving state into statedb from any backend — and out again, in one command.
---

statedb stores state in the CLI's native checkpoint format, not a proprietary
schema. That makes migration symmetric: the same two commands move you in and
move you out.

## Moving in

From a stack on your current backend (a managed cloud, S3, local files —
anywhere):

```sh
# On the old backend
pulumi stack select my-stack
pulumi stack export --file my-stack.checkpoint.json

# Point at statedb and import
pulumi login https://statedb.internal.example.com
pulumi stack init my-stack
pulumi stack import --file my-stack.checkpoint.json
```

Run `pulumi preview` afterwards — it should show no changes. Repeat per stack.

Secrets note: if the stack used a backend-managed secrets provider, re-select a
secrets provider as part of the move (`pulumi stack change-secrets-provider`).
Stacks using passphrase or KMS-based providers carry over as-is.

## Moving out

The same commands, pointed the other way:

```sh
pulumi stack export --file my-stack.checkpoint.json
pulumi login s3://your-bucket        # or any other backend
pulumi stack import --file my-stack.checkpoint.json
```

Export works from day one and is never gated — not by license tier, not by
stack count. Imported checkpoints are validated before persistence, and
tampered or corrupted files are rejected without touching existing state.

## Why we build it this way

A backend that makes leaving expensive has no incentive to stay good. Keeping
the exit at one command means statedb has to keep earning the next
`pulumi up` — that's the deal.
