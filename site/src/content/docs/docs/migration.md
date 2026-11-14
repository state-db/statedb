---
title: Migrate stacks
description: Move Pulumi stack state into statedb, and back out again.
---

statedb stores state in the CLI's own checkpoint format, so migration in either
direction uses `pulumi stack export` and `pulumi stack import`, with no
conversion step or statedb-specific tooling.

Migrate one stack at a time; you do not have to move everything at once.

## Before you start

- Back up the source backend.
- Note the stack's current secrets provider.
- Keep the source backend available until you have verified the imported stack.
- Pick a window with no active updates on the stack.

## Moving a stack in

Export from wherever the stack lives today:

```sh
pulumi stack select my-stack
pulumi stack export --file my-stack.checkpoint.json
```

Then create the destination stack on statedb and import into it:

```sh
pulumi login https://statedb.example.com/pulumi
pulumi stack init my-stack
pulumi stack import --file my-stack.checkpoint.json
```

Finally, confirm the result:

```sh
pulumi preview
```

A clean preview with no unexpected changes is the signal that the migration
worked. Anything else needs investigation before you delete the source copy.

## Secrets

Passphrase and external KMS providers travel with the checkpoint and need no
attention. A backend-managed secrets provider is the case that requires work,
since the old backend's key does not come with the file.

Use `pulumi stack change-secrets-provider` when the destination cannot use the
current provider, and test decryption on the imported stack before removing the
source. Secrets can fail without any visible error, so verify them explicitly; a clean
preview does not cover them.

## Moving a stack out

Use the same two commands in the other direction:

```sh
pulumi stack export --file my-stack.checkpoint.json
pulumi login s3://your-bucket
pulumi stack init my-stack
pulumi stack import --file my-stack.checkpoint.json
pulumi preview
```

On the way in, state-db validates a checkpoint before it replaces stored state.
