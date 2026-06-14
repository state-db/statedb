# Pulumi-compatible CLI Smoke Tests

This module runs slower, opt-in integration tests against the real Pulumi CLI in
a Docker container. Keep normal development on the fast app tests; run this when
you want an end-to-end check against Pulumi itself:

```bash
./bin/in-container mvn -f modules/pom.xml -pl pulumi-cli-smoke -am verify -DskipPulumiCliSmoke=false
```

The dev container uses Docker-outside-of-Docker, so Testcontainers can start the
Pulumi sibling container while still honoring the repo rule that project commands
run through `./bin/in-container`.

## Layout

Tests are split by command family, one class per area, all extending the shared
`PulumiSmokeBase` harness (identity seeding, container builders, exec/JSON
helpers). The base is `abstract` and has no `IT` suffix, so failsafe does not run
it directly.

| Class | Covers |
|---|---|
| `StackLifecycleSmokeIT` | `stack init` / `ls` / `rm`, `export` / `import` |
| `StackReferenceSmokeIT` | `StackReference` outputs + secret masking |
| `StackMetadataSmokeIT` | `stack tag set/ls/get/rm`, bare `stack`, `graph`, `history`, `select`, `rename` |
| `UnsupportedFeatureSmokeIT` | endpoints we 501 (`schedule list`, `webhook list`) |

### Adding a test

Put it in the matching `*SmokeIT` class (create a new `*SmokeIT extends
PulumiSmokeBase` for a new command family). **Each `@Test` spins its own Pulumi
container**, so group related assertions into one method rather than splitting
per command — a tag round-trip belongs in a single method, not four. Reach for
the base helpers (`recorderProjectContainer`, `singleProjectContainer`,
`exposeBackend`, `execResult` / `execJson` / `execAllowingFailure`) instead of
re-deriving container setup.
