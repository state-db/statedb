# Pulumi Integrity Verifier

Wraps Pulumi's own `snapshot.VerifyIntegrity` (from
`github.com/pulumi/pulumi/sdk/v3/go/common/snapshot`) in a tiny CLI so tests can
assert that a deployment assembled by state-db is one Pulumi itself would accept.

Input is a `DeploymentV3` JSON object or the `pulumi stack export` envelope
(`{"version": 3, "deployment": {...}}`), from a file argument or stdin.

Exit codes:

- `0` — integrity ok
- `1` — integrity violation; the failing rule is printed on stdout
- `2` — input unusable (not JSON, wrong envelope version)

```bash
./verify deployment.json
pulumi stack export | ./verify
```

`make build` produces the `statedb-pulumi-verify` Docker image. The image's
default command sleeps forever so Java tests (see `PulumiDeploymentAssert` in
`modules/test-support`) can start one container per test run and exec `verify`
per assertion. Java tests build this image themselves via Testcontainers, so
`make build` is only needed for manual use.

The pinned `github.com/pulumi/pulumi/sdk/v3` version in `go.mod` should track
the Pulumi CLI version pinned in `tools/pulumi/recorder/Dockerfile`.

Like `tools/pulumi/recorder`, this tool manages its own execution environment
(host Go + Docker) and is not routed through `./bin/in-container`.

## Normalization note

Recorder fixture normalization rewrites the provider resource `id` and the id
segment embedded in provider references to the same `[resource-id]` placeholder
precisely so that normalized deployments still satisfy the unknown-provider
integrity rule. If verification of a fixture-derived deployment fails with
"unknown provider", suspect a normalization regression in
`tools/pulumi/recorder/main.go#normalizeFixtureString` first.
