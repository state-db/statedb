# Changelog

Release notes are maintained by hand. Add user-visible changes under `## Unreleased` as they
land; `bin/release` moves that section under the version heading and uses it as the GitHub Release
body.

## Unreleased

## 0.1.0 — 2026-11-20

First public release. statedb is at version 0.x: a minor release can include breaking changes, and
this file states each one.

### Added

- Pulumi state backend: an unmodified Pulumi CLI stores stacks, checkpoints, update history, and
  engine events in your PostgreSQL database, with stack locking and recovery of interrupted updates.
- Web console for stacks, resources, and update history, and for administering organizations,
  users, identity providers, and service tokens.
- Sign-in through OpenID Connect, service account tokens for CI, and organization roles enforced
  with Cedar policies.
- Pulumi's `service` secrets provider, backed by a keyset stored in the database.
- Optional GitHub App that reports previews and updates on pull requests.
- Native image for `linux/amd64` and `linux/arm64` at `ghcr.io/state-db/statedb`, with build
  provenance and SBOM attestations, and Java and container SBOMs attached to the release.
- AWS CloudFormation template, attached to the release, for two ECS replicas and encrypted RDS
  PostgreSQL with ECS task-role database authentication.

### Compatibility

- Tested with Pulumi CLI `3.234.0` and `3.259.0`.
- Requires PostgreSQL 14 or newer with LZ4; migrations are tested on PostgreSQL 14, 16, and 18.
- This release creates the schema. There is no earlier release to upgrade from.
