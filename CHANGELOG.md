# Changelog

Release notes are maintained by hand. Add user-visible changes under `## Unreleased` as they
land; `bin/release` moves that section under the version heading and uses it as the GitHub Release
body.

## Unreleased

### Added

- Maintainer-run release tooling for building, gating, signing, and publishing the Docker image.
- Authenticated `/api/version` endpoint that reports the running build identity.

### Changed

- Runtime images are now built end-to-end inside Docker with separate console, Maven, and runtime
  stages.
- Build identity is derived from `.version`, Git tags, and the source commit instead of the Maven
  project version.

### Fixed

- Clean checkout console builds no longer install the npm `bun` package that shadows Bun's built-in
  runtime module.
- Third-party notice generation now excludes the repository's `app.statedb` modules.
