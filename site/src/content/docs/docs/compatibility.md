---
title: How we test compatibility
description: Contract replay, integrity verification, real-CLI smoke tests, and fault injection.
---

Compatibility claims are cheap; test suites are not. statedb's contract is
"the unmodified CLI works," and that contract is enforced in four tiers, each
answering a different failure question.

## Contract replay

Every HTTP exchange from recorded real-CLI sessions — `up`, `preview`,
`refresh`, `destroy`, tags, rename, export/import, and more — is replayed
against the server. Responses must reproduce byte-for-byte, modulo documented
volatile fields (timestamps, generated IDs). If a statedb change alters the
wire protocol, this tier fails before a human ever notices.

## Integrity verification

Every deployment the server assembles in tests is checked with the CLI's own
snapshot integrity verifier. This closes the gap between "we stored the bytes"
and "the CLI would agree to load them": stored state is state the CLI itself
considers coherent.

## Real-CLI smoke

The actual CLI, running in Docker, drives the backend per command family on
every build — stack lifecycle (`init` / `ls` / `rm`, export/import), stack
references and secret masking, tags, history, rename, and the endpoints statedb
deliberately does not implement (which must fail cleanly with `501`, not
mysteriously).

## Fault injection

Adversarial scenarios with the real CLI, each proving a specific guarantee:

| Scenario | Guarantee proven |
| --- | --- |
| CLI killed mid-update (SIGKILL) | Pending-operation breadcrumb recorded; stack recovers after the lease expires |
| Two concurrent `up` runs | Exactly one wins; the other fails cleanly |
| Provider error mid-deploy | Completed resources are retained; a corrected `up` succeeds |
| Compaction under load | Invisible to the CLI; secrets decrypt from a fresh machine |
| Tampered checkpoint import | Rejected before persistence; prior state untouched |

Each run emits a **State Safety Report** listing every scenario and its
outcome. A nightly CI matrix repeats the full set across every supported CLI
version.

## Supported CLI versions

The nightly matrix currently covers the Pulumi CLI 3.242 through 3.253, and new
releases are swept for protocol changes as they appear. OpenTofu's HTTP backend
protocol (`address` / `lock_address` / `unlock_address`) is a planned target;
where the protocols overlap the implementation is shared, and where they
diverge the differences are isolated behind an adapter.
