# state-db Release Spec

This document defines how state-db versions, builds, verifies, and publishes releases.

## Scope

state-db publishes one artifact:

- OCI image: `ghcr.io/state-db/statedb`

The project does not publish Maven artifacts. The Maven reactor remains at
`0.1.0-SNAPSHOT`; release versions come from Git metadata and `.version`, not from poms.

## Release Authority

Releases are cut from a maintainer machine with Docker, `gh`, and `cosign` installed.
GitHub Actions verifies commits and publishes commit-addressed architecture candidates to the
candidates package. It cannot publish release tags and does not hold release-signing credentials.

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
modules/app/src/main/docker/Dockerfile.native
```

The Docker build context is the repository root. `.dockerignore` allow-lists only the files needed
for the image build and excludes local build outputs and generated caches.

The image has four stages:

1. `web`: Bun installs with `--frozen-lockfile`, typechecks, and builds the console.
2. `build`: Maven packages Quarkus native-image sources with `-Dweb.build.skip=true`; it consumes
   the web bundle from stage 1.
3. `native`: pinned Mandrel compiles the executable and verifies Cedar's architecture-specific JNI
   library against its committed upstream checksum.
4. `runtime`: a minimal UBI Micro image runs the executable as numeric UID 1001.

Base images are pinned by digest.

The image is multi-arch:

```text
linux/amd64,linux/arm64
```

Run the image with an explicit memory limit and a writable `/tmp` tmpfs. The runtime filesystem may
otherwise be read-only.

## Registry Tags

Release publishing writes these tags:

- `X.Y.Z`
- `X.Y`
- `latest`

Example for `0.2.3`:

```text
ghcr.io/state-db/statedb:0.2.3
ghcr.io/state-db/statedb:0.2
ghcr.io/state-db/statedb:latest
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

1. `./dev signoff` recreated the verification VM and published `signoff/ci` on
   exactly the unchanged release commit after credential-free isolated verification.
2. The manually dispatched `native.yml` release build succeeded on exactly the release commit. It
   runs the Pulumi CLI compatibility matrix and builds and gates amd64 and arm64 candidates.

`--rehearse` reports these checks instead of enforcing them. A real release enforces them.

`native.yml` is `workflow_dispatch` only. Dispatch it against the release commit with the version
that `bin/release` will publish, then let it finish:

```text
gh workflow run native.yml --ref main -f version=0.2.0
```

The workflow embeds and gates that version in both architectures. Its successful run satisfies
gate 1 and publishes the per-architecture candidates used by the release manifest. Ordinary pushes
and pull requests consume no GitHub Actions minutes.

### Gate 2: Candidate Image

`bin/release-gate <image>` runs the candidate image and verifies:

1. upgrade from the previous compatible release;
2. simultaneous two-replica cold start and migration convergence;
3. non-root/read-only image, notices, and Cedar library integrity;
4. anonymous rejection plus Cedar owner-allow/viewer-deny;
5. OIDC and break-glass login, authenticated projections, SPA delivery, forced compaction, and
   build identity;
6. a real Pulumi `up`, `refresh`, `export`, `destroy`, and stack removal;
7. database-outage readiness degradation and recovery without process restart;
8. SIGTERM completion within the shutdown grace period.

For release candidates, the gate also requires `/api/version` to report both the exact source
commit and the requested final release version.

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

- `--dry-run`: runs preflight, CI checks, local tag creation, image build, and image gate. It
  downloads the successful CI run's evidence and verifies that both digest-pinned architecture
  candidates still exist, but skips candidate-manifest creation, publish, sign, tag push, GitHub
  Release creation, and changelog push.
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
7. Download the successful workflow run's candidate digests, native build evidence, and scanned
   Java SBOM. Assemble the manifest from digest references rather than mutable candidate tags,
   pull it, and repeat the release gate while checking both commit and release version.
8. Recheck that HEAD, the worktree, and `origin/main` are unchanged, then atomically push the Git
   tag while asserting the expected `main` value.
9. Copy the gated manifest to `X.Y.Z`, `X.Y`, and `latest`, then sign its digest with `cosign`.
10. Create the GitHub Release from the changelog section and CI-produced evidence.
11. Move `CHANGELOG.md`'s `## Unreleased` section under the release heading.
12. Bump `.version` to the next minor.
13. Commit and push the changelog/version bump to `main`.

If a failure happens before the tag is pushed, the local tag is removed. The script refuses to
overwrite an existing `X.Y.Z` image tag with a different digest. After the Git tag is pushed, the
release is considered published; failures while publishing artifacts or rolling `.version` must be
fixed manually before more changes merge to `main`.

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
