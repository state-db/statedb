# Release coordinator

`bin/release` launches this TypeScript module with the mise-pinned Bun runtime. The public command
remains:

```text
bin/release [--version X.Y.Z] [--dry-run | --rehearse]
```

The implementation is split by responsibility:

- `release.ts` owns release policy and phase ordering. Its interface is `runRelease(options,
  environment)`.
- `system.ts` adapts that policy to Git, GitHub CLI, Docker Buildx, Cosign, and the filesystem.
- `main.ts` parses the command line and renders terminal errors.
- `release.test.ts` supplies in-memory adapters, so policy and failure behavior are tested without
  contacting GitHub or a registry.

The important rule is that `release.ts` speaks in release operations—find CI evidence, inspect an
image, publish a tag—not shell command strings. Command spelling stays inside `system.ts`.

## Phase order

1. Validate the maintainer environment, Git state, changelog, and increasing SemVer.
2. Resolve the exact successful CI run and download its candidate digests and scanned evidence.
3. Create a temporary local Git tag, build a local image, and run the release gate.
4. Assemble the multi-architecture candidate from digest references and gate it again.
5. Atomically publish the Git tag while asserting that `main` did not advance.
6. Publish and sign the immutable image, move aliases, and create the GitHub Release.
7. Roll the changelog and `.version` forward and push `main`.

Before the Git tag is published, failures remove the temporary local tag. After it is published,
failures become `RecoveryRequired` errors that identify the last completed phase and preserve the
tag for manual reconciliation.

## Verification

Focused tests run through the repository interface:

```text
./dev quick
```

`bin/verify-release-tooling` runs the Bun tests and TypeScript compiler in the credential-free
verification VM, then checks the remaining small Bash adapters.
