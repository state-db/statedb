---
title: Pulumi CLI compatibility
description: How statedb tests its wire protocol and proves state survives failure.
---

A state backend cannot be approximately compatible. Either the unmodified Pulumi
CLI works against statedb or it doesn't, so the repository tests that claim at
four levels: three covering the normal workflow, and one dedicated to the
failures most likely to damage state.

## Replaying recorded traffic

The contract tests replay HTTP exchanges captured from real CLI sessions,
covering stack operations, updates, tags, history, rename, export, and import.

Responses have to match the recording byte for byte. The comparison ignores only
an explicit list of volatile fields such as timestamps and generated
identifiers, which keeps "we changed the protocol" from ever passing as "the
timestamp moved".

## Verifying every checkpoint

Assembled checkpoints are handed to Pulumi's own snapshot integrity verifier.
This is the check that matters most: it means stored state is state the CLI
itself would agree to load, and it catches invalid resource relationships that a
byte comparison would happily accept.

## Running the real CLI

The smoke tests drive an unmodified CLI against statedb in Docker, across:

- stack creation, listing, export, import, and removal
- previews and updates
- stack references and secret masking
- tags, history, and rename
- unsupported endpoints, which must answer with a clear `501` rather than
  failing in some more creative way

## Injecting failures

The last tier assumes something has already gone wrong and asks what survives.

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

Not supported. Its HTTP backend protocol is a planned addition, and nothing in
statedb implements it today.
