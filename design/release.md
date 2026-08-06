# state-db Release Spec

This document defines how state-db versions, builds, verifies, and publishes releases.

## Scope

state-db publishes one artifact:

- OCI image: `ghcr.io/state-db/state-db`

The project does not publish Maven artifacts. The Maven reactor remains at
`0.1.0-SNAPSHOT`; release versions come from Git metadata and `.version`, not from poms.

## Release Authority

Releases are cut from a maintainer machine with Docker, `gh`, and `cosign` installed.
GitHub Actions verifies commits but does not publish images and does not hold registry publishing
credentials.

This is an operating rule for the current solo-maintainer project. If release ownership changes,
this section should be revisited.

## Versioning

`.version` contains the next planned release version, for example:

```text
0.2.0
```

`bin/build-version` derives the build identity:

| State                              | Build identity           |
| ---------------------------------- | ------------------------ |
| 17 commits after previous tag      | `0.2.0-dev.17+g4976296`  |
| exact tag `v0.2.0`                 | `0.2.0`                  |
| exact tag with uncommitted changes | `0.2.0+dirty`            |
| dev build with uncommitted changes | `0.2.0-dev.17+g4976296.dirty` |

`.version` holds the next release so pre-release builds sort before the release they are leading
to under SemVer:

```text
0.2.0-dev.17 < 0.2.0
```

After releasing `v0.2.0`, `.version` must be bumped immediately to `0.3.0`. Otherwise the next
commit on `main` would derive `0.2.0-dev.1`, which sorts before the already published `0.2.0`.

Pre-1.0 versioning rule:

- `0.MINOR` is the breaking-change axis.
- Schema, configuration, and Pulumi protocol compatibility changes land in minor releases.
- Patch releases are reserved for security, data-integrity, and compatibility fixes.

## Build Identity Endpoint

The application exposes:

```text
GET /api/version
```

The endpoint is authenticated by the normal `/api/*` console permission.

Response shape:

```json
{
  "commit": "4976296",
  "builtAt": "2026-08-01T09:14:22Z",
  "build": "0.2.0-dev.17+g4976296"
}
```

Values are filtered into `build-info.properties` during packaging from:

- `statedb.build.version`
- `statedb.build.commit`
- `statedb.build.timestamp`

`quarkus.application.version` is not used for release identity.

## Image

The runtime image is built by:

```text
modules/app/src/main/docker/Dockerfile.jvm
```

The Docker build context is the repository root. `.dockerignore` allow-lists only the files needed
for the image build and excludes local build outputs and generated caches.

The image has three stages:

1. `web`: Bun installs with `--frozen-lockfile`, typechecks, and builds the console.
2. `build`: Maven packages the Quarkus fast-jar with `-Dweb.build.skip=true`; it consumes the web
   bundle from stage 1.
3. `runtime`: UBI9 OpenJDK runtime image.

Base images are pinned by digest.

The image is multi-arch:

```text
linux/amd64,linux/arm64
```

The image should be run with a memory limit. Its default `JAVA_OPTS` use `AlwaysPreTouch` and
`MaxRAMPercentage=75`; an unlimited container can try to commit too much host memory at startup.

## Registry Tags

Release publishing writes these tags:

- `X.Y.Z`
- `X.Y`
- `latest`

Example for `0.2.3`:

```text
ghcr.io/state-db/state-db:0.2.3
ghcr.io/state-db/state-db:0.2
ghcr.io/state-db/state-db:latest
```

Documentation should prefer the minor tag (`0.2`) when automatic patch uptake is desired.

## Release Cadence

Default cadence:

- Minor release train: monthly or skipped if nothing user-visible landed.
- Patch release: cut only when a fix needs to ship before the next minor.
- Support window: current and previous minor.

The cadence is intentionally lightweight while this is a solo-maintainer project.

## Release Gates

`bin/release` enforces two gates before publishing.

### Gate 1: CI History

The release commit must satisfy:

1. `ci.yml` succeeded on exactly the release commit.
2. The latest three `nightly.yml` runs succeeded.

`--rehearse` reports these checks instead of enforcing them. A real release enforces them.

### Gate 2: Candidate Image

`bin/release-gate <image>` runs the candidate image and verifies:

1. **Upgrade:** If `--previous` is provided, start the previous release on a fresh database, stop
   it, then start the candidate on the same database. Flyway must converge with no failed rows.
2. **Boot:** Candidate reaches readiness.
3. **Identity:** Candidate reports the expected commit through `/api/version`.
4. **Pulumi lifecycle:** A real Pulumi CLI performs `up`, `refresh`, `export`, `destroy`, and
   stack removal against the candidate.

The Pulumi test project is:

```text
tools/pulumi/recorder/testproject/Pulumi.yaml
```

The gate pins the random provider version from:

```text
tools/pulumi/recorder/Dockerfile
```

## Release Command

Primary command:

```text
bin/release [--version X.Y.Z] [--dry-run | --rehearse]
```

Modes:

- `--dry-run`: runs preflight, CI checks, local tag creation, image build, and image gate; skips
  publish, sign, tag push, GitHub Release creation, and changelog push.
- `--rehearse`: implies `--dry-run`; reports releasability checks that are expected to fail on a
  work-in-progress tree.

## Release Workflow

`bin/release` performs:

1. Preflight:
   - branch is `main`
   - working tree is clean
   - `HEAD == origin/main`
   - release tag does not exist locally or remotely
   - `CHANGELOG.md` has a non-empty `## Unreleased` section
2. Verify CI history.
3. Create annotated local tag `vX.Y.Z`.
4. Verify `bin/build-version` resolves to `X.Y.Z`.
5. Build a loadable single-arch candidate image.
6. Run `bin/release-gate` against the candidate.
7. Build and push the multi-arch image with tags `X.Y.Z`, `X.Y`, and `latest`.
8. Sign the published digest with `cosign`.
9. Push the Git tag.
10. Create the GitHub Release from the changelog section.
11. Move `CHANGELOG.md`'s `## Unreleased` section under the release heading.
12. Bump `.version` to the next minor.
13. Commit and push the changelog/version bump to `main`.

If a failure happens before the tag is pushed, the local tag is removed. After the tag is pushed,
the release is considered published; failures while rolling `.version` must be fixed manually
before more changes merge to `main`.

## Changelog

`CHANGELOG.md` is maintained by hand.

Rules:

- Keep an `## Unreleased` section.
- Add entries as user-visible changes land.
- Do not generate release notes from commit messages.
- `bin/release` refuses to release with an empty `## Unreleased` section.

Each release entry should include:

- supported Pulumi CLI range
- schema version range and whether the migration is one-way
- Free Edition active-stack limit

## Documentation

Release documentation should include:

- current release
- supported minor versions
- upgrade notes by minor
- image tags and recommended tag pinning

## Open Questions

1. Monthly versus six-week minor cadence.
2. Whether to add a CI job that rebuilds released tags and reports reproducibility drift without
   publishing anything.
3. How release ownership changes if there is a second maintainer.
