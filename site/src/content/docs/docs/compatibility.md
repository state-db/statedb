---
title: Pulumi CLI compatibility
description: How statedb tests its wire protocol and proves state survives failure.
---

The unmodified Pulumi CLI either works against statedb or it does not. The
repository tests that claim at four levels: three cover the normal workflow, and
one covers the failures most likely to damage state.

## Replaying recorded traffic

The contract tests replay HTTP exchanges captured from real CLI sessions,
covering stack operations, updates, tags, history, rename, export, and import.

Responses have to match the recording byte for byte. The comparison ignores only
an explicit list of volatile fields such as timestamps and generated
identifiers, so a protocol change cannot pass as a timestamp change.

## Verifying every checkpoint

Assembled checkpoints are handed to Pulumi's own snapshot integrity verifier.
This check means stored state is state the CLI itself would agree to load, and it catches invalid resource relationships that a
byte comparison would happily accept.

## Running the real CLI

The smoke tests drive an unmodified CLI against statedb in Docker, across:

- stack creation, listing, export, import, and removal
- previews and updates
- stack references and secret masking
- tags, history, and rename
- unsupported endpoints, which must answer with a clear `501`

## Injecting failures

The last tier assumes a failure has already happened and checks what survives.

| Scenario | Required outcome |
| --- | --- |
| CLI killed mid-update | The pending operation is recorded, and the stack recovers once the lease expires |
| Two updates started together | One proceeds, the other is rejected |
| Provider fails during an update | Completed resources are kept and a corrected update can proceed |
| State compacted while under load | The CLI sees nothing, and secrets still decrypt |
| Modified checkpoint imported | The import is rejected and current state is untouched |

Every run emits a State Safety Report listing each scenario and its outcome.

## Tested CLI versions

The release gate runs Pulumi CLI `3.234.0` and `3.259.0`. Recorded wire fixtures
and the snapshot verifier come from `3.259.0`. On the database side, migrations
are tested against PostgreSQL 14, 16, and 18.

These are tested reference points, not a guarantee about every release in
between. Before adopting a different CLI version, read its release notes and run
`./dev protocol` against it.

## OpenTofu

Not supported. statedb does not implement the OpenTofu HTTP backend protocol.
