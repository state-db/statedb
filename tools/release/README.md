# Release coordinator

`bin/release` runs the release coordinator with the mise-pinned Bun runtime:

```text
bin/release [--version X.Y.Z] [--dry-run | --rehearse]
```

## Phase order

1. Validate the maintainer environment, Git state, changelog, and increasing SemVer.
2. Resolve the exact successful CI run and download its candidate digests and scanned evidence.
3. Create a temporary local Git tag, build a local image, and run the release gate.
4. Assemble the multi-architecture candidate from digest references and gate it again.
5. Atomically publish the Git tag while asserting that `main` did not advance.
6. Publish and sign the immutable image, move aliases, and create the GitHub Release.
7. Roll the changelog and `.version` forward and push `main`.

Before the Git tag is published, failures remove the temporary local tag. After it is published,
failures identify the last completed phase and preserve the tag for manual reconciliation.

## Verification

Focused tests run through the repository interface:

```text
./dev quick
```

`bin/verify-release-tooling` runs the Bun tests and TypeScript compiler in the credential-free
verification VM, then checks the remaining small Bash adapters.
