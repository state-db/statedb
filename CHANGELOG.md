# Changelog

Release notes are maintained by hand. Add user-visible changes under `## Unreleased` as they
land; `bin/release` moves that section under the version heading and uses it as the GitHub Release
body.

## Unreleased

### Added

- Maintainer-run release tooling for building, gating, signing, and publishing the Docker image.
- Authenticated `/api/version` endpoint that reports the running build identity, derived from
  `.version`, Git tags, and the source commit.
- Signed amd64 and arm64 native-image releases, built inside Docker, with Cedar authorization
  readiness checks, strict reachability validation, vulnerability scanning, and Java/container SBOMs.
- AWS CloudFormation packaging for two ECS Express Mode replicas, encrypted RDS PostgreSQL, and
  ECS task-role database authentication without a long-lived application password.
