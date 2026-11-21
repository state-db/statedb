# Releases

state-db publishes a multi-architecture native OCI image and a digest-pinned AWS
CloudFormation template. It does not publish Maven artifacts, and the Maven
reactor version is independent of the release identity.

The [release coordinator](../tools/release/release.ts), run through
`bin/release`, owns publication from a maintainer machine. The
[release build workflow](../.github/workflows/native.yml) builds and gates the
per-architecture candidates that a release assembles.

## Version identity

`.version` contains the next planned release, and `bin/build-version` combines
it with Git tags, commit distance, and worktree status:

| Repository state | Example identity |
| --- | --- |
| 17 commits after the previous release tag | `0.2.0-dev.17+g4976296` |
| The same commit with uncommitted changes | `0.2.0-dev.17+g4976296.dirty` |
| Exact release tag `v0.2.0` | `0.2.0` |
| Release tag with uncommitted changes | `0.2.0+dirty` |

`.version` holds the next release rather than the last one so that development
builds sort before the release they lead to under SemVer
(`0.2.0-dev.17 < 0.2.0`). For the same reason the coordinator bumps `.version` to
the next minor as soon as it publishes; otherwise the next commit on `main` would
derive a version that sorts before the published release. Build timestamps come
from the commit, so rebuilding an old commit reproduces its identity.

Packaging filters the version, commit, and timestamp into
`build-info.properties`, and the authenticated `GET /api/version` endpoint
returns them.

## Image

[Dockerfile.native](../modules/app/src/main/docker/Dockerfile.native) builds the
image from the repository root in four stages: the Bun web bundle, the Maven
package of Quarkus native-image sources, a Mandrel native compile that checks
Cedar's architecture-specific JNI library against its committed checksum, and a
UBI Micro runtime that runs as UID 1001. Base images are pinned by digest. The
release image covers `linux/amd64` and `linux/arm64`, each built on its own
architecture. [Native image](native-image.md) describes the reachability
contract the build enforces.

## Release build workflow

`native.yml` runs only on manual dispatch, with the release version as input.
Ordinary pushes and pull requests do not run it; `./dev check` and
`./dev signoff` verify ordinary changes. A run executes these jobs:

| Job | What it proves |
| --- | --- |
| PostgreSQL compatibility | The packaged migration chain replays on PostgreSQL 14, 16, and 18 |
| Pulumi compatibility | Smoke and fault tiers pass against each digest-pinned Pulumi CLI image in the matrix |
| Strict metadata negative | An image missing a native registration fails authorization readiness |
| Build and gate (amd64, arm64) | Each architecture builds, passes `bin/release-gate` with the requested version, and passes a Trivy scan |

The build-and-gate job fails on fixable HIGH or CRITICAL findings in the image
and, on amd64, in the dependency-level Java SBOM. It records a CycloneDX
container SBOM per architecture, then pushes the gated image as
`<registry>-candidates:<commit>-<arch>` and uploads its digest with the build
metadata. It then attests build provenance and the container SBOM for the
platform image manifest, signed with the workflow's own identity. The release
index lists that manifest unchanged, so an attestation made here verifies
against the released image; no maintainer key or personal identity signs
anything. The job runs in the `release` environment, so it waits for maintainer
approval and runs only from `main`. The workflow holds `packages: write` for
candidates only. Its artifacts are retained for 14 days, so a release must run
within 14 days of the workflow run it relies on.

`./dev publish-candidate` builds the current Docker architecture from a clean
tracked tree and publishes it under the same candidate naming. It then pulls the
immutable digest and gates those registry bytes, which is what a deployment test
on a remote host needs. It produces no native build evidence and does not
satisfy the workflow requirement for a release.

## Image gate

`bin/release-gate <image>` runs a candidate against PostgreSQL, a mock OIDC
provider, and a real Pulumi CLI. Its steps run in this order:

1. Upgrade: with `--previous`, the previous release starts first, stays live
   while the candidate migrates, and must still complete an authenticated Pulumi
   write lifecycle afterward. Without a previous image the step reports an
   explicit skip.
2. Concurrent cold start: two candidate replicas start together, and the
   applied Flyway history must match the packaged migrations exactly.
3. Image contract: non-root user, AGPL license label, distribution notices, and
   the Cedar native library hash.
4. Native authorization: anonymous requests get 401, and Cedar allows an owner
   and denies a viewer.
5. Console: OIDC login, authenticated API reads, SPA delivery, a forced
   compaction that completes, break-glass login, and `/api/version` reporting the
   expected commit and, when requested, the expected release version.
6. Pulumi lifecycle: `up`, `refresh`, `stack export`, `destroy`, and `stack rm`
   against the [recorder test project](../tools/pulumi/recorder/testproject/Pulumi.yaml).
7. Database outage: readiness returns 503 while PostgreSQL is down, the process
   keeps running, and readiness recovers without a restart.
8. Shutdown: the process exits cleanly within a 10-second SIGTERM grace period.

## Release command

```text
bin/release [--version X.Y.Z] [--dry-run | --rehearse]
```

The maintainer machine needs Docker with buildx, `git`, and `gh`; a
real release that sets `STATEDB_CFN_BUCKET` also needs the AWS CLI. The coordinator
runs these steps in order:

1. Preflight: `main` is checked out, clean, and equal to `origin/main`; the
   version (from `--version` or `.version`) has no tag locally or on `origin` and
   is newer than the latest release tag; `CHANGELOG.md` has a nonempty
   `## Unreleased` section. When `STATEDB_CFN_BUCKET` is set, a real release also
   confirms AWS credentials and access to that bucket, because both are first
   used after the tag.
2. Evidence: the commit has a successful `signoff/ci` status, which only
   `./dev signoff` produces after clean-VM verification, and its latest
   `native.yml` run succeeded. The coordinator downloads that run's candidate
   digests, build metadata, and scanned Java SBOM.
3. Local candidate: it creates a temporary annotated tag, confirms that
   `bin/build-version` resolves to the release version, builds a local image, and
   gates it with `--previous` set to the latest release when one exists.
4. Manifest: it assembles the multi-architecture manifest from the workflow's
   digest references rather than mutable tags, pulls it, and gates it again
   against the exact commit and release version. The index carries the OCI
   source, version, revision, and license annotations, because the registry
   reads a multi-architecture package's description and repository link there.
5. Tag: after rechecking that `HEAD`, the worktree, and `origin/main` are
   unchanged, it pushes the tag atomically together with an assertion on `main`,
   so the tag cannot appear if `main` moved.
6. Publication: it publishes `X.Y.Z`, uploads the versioned CloudFormation
   template (stamped with the gated digest) with a create-only condition, moves `X.Y` and `latest`, creates the GitHub Release
   from the changelog section with the template, Java SBOM, and build evidence,
   and promotes `releases/latest/cloudformation.yaml`. Both bucket uploads are
   skipped when `STATEDB_CFN_BUCKET` is unset; the template is then published
   only as a GitHub Release asset.
7. Rollover: it moves the `Unreleased` notes under the release heading, bumps
   `.version` to the next minor, writes the release to `site/release-version`,
   and commits and pushes all three to `main`. The site fills `{{version}}` in
   its docs from that file, and the change under `site/` triggers its deploy.

`--dry-run` runs steps 1 through 3 and confirms that both candidate digests
still exist in the registry, then stops before assembling the manifest; nothing
is published. `--rehearse` takes the same path but reports failed preconditions
of steps 1 and 2, such as a dirty tree or missing signoff, as warnings so a
development checkout can exercise the rest.

## Failure boundary

Before the tag is pushed, any failure deletes the temporary local tag and leaves
nothing published. Once the tag is on `origin` the release counts as published;
a later failure names the last completed step, and the maintainer reconciles the
remaining steps by hand. The coordinator refuses to point an existing `X.Y.Z`
tag at different bytes, and the versioned template object cannot be overwritten.

## Changelog and artifacts

`CHANGELOG.md` is written by hand as user-visible changes land; release notes are
never generated from commit messages. The coordinator uses the `Unreleased`
section as the GitHub Release body and refuses to release when it is empty. A
release entry states compatibility changes, including the supported Pulumi CLI
range and whether a schema migration is one-way.

The default registry is `ghcr.io/state-db/statedb`, overridable with
`STATEDB_REGISTRY`; candidates go to the same name with a `-candidates` suffix
unless `STATEDB_CANDIDATE_REGISTRY` is set. `X.Y.Z` identifies a release, `X.Y`
follows later patches of that minor, and digest references identify immutable
content. [Migrations](migrations.md) describes the upgrade contract that the
gate's first two steps exercise.
