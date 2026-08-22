# Pulumi-compatible CLI Smoke & Fault Tests

This module runs slower, opt-in integration tests against the real Pulumi CLI in
a Docker container, in two tiers. Keep normal development on the fast app tests.

**Smoke** — per-command sanity against the real CLI:

```bash
./dev -- modules/mvnw -f modules/pom.xml -pl pulumi-cli-smoke -am verify -DskipPulumiCliSmoke=false
```

**Faults** — adversarial fault-injection scenarios (CLI killed mid-update,
concurrent updates, failed deployments, corrupted imports, compaction/secrets
durability). Each scenario asserts both what the operator sees (CLI exit code +
message) and DB/export invariants, and every export is checked against Pulumi's
own `snapshot.VerifyIntegrity`. Results are written to
`target/state-safety-report.md` — the published State Safety Report:

```bash
./dev -- modules/mvnw -f modules/pom.xml -pl pulumi-cli-smoke -am verify -DskipPulumiCliFaults=false
```

The Pulumi CLI version under test is pinned by the pom's `pulumi.cli.image`
property; the nightly CI matrix overrides it per version:

```bash
./dev -- modules/mvnw ... -Dpulumi.cli.image=pulumi/pulumi:3.242.0
```

These focused commands are the explicit human-local path and require Docker.
The authoritative smoke and fault gate runs both tiers inside the credential-free
verification VM:

```bash
./dev protocol
```

## Layout

Tests are split by command family, one class per area, all extending the shared
`PulumiSmokeBase` harness (identity seeding, container builders, exec/JSON
helpers). The base is `abstract` and has no `IT` suffix, so failsafe does not run
it directly.

| Class | Covers |
|---|---|
| `StackLifecycleSmokeIT` | `stack init` / `ls` / `rm`, `export` / `import` |
| `StackReferenceSmokeIT` | `StackReference` outputs + secret masking |
| `StackMetadataSmokeIT` | `stack tag set/ls/get/rm`, bare `stack`, `graph`, `history`, `select`, `rename`, `change-secrets-provider` |
| `StateEditSmokeIT` | `state protect/unprotect/taint/untaint/rename/delete/repair`, `state move` |
| `ResourceImportSmokeIT` | `import` (re-adopting a resource dropped from state) |
| `ConfigSmokeIT` | `config set/get/rm` incl. `--secret`, `config cp` |
| `BackendInspectionSmokeIT` | `about`, `project ls`, `stack ls --all` / `--organization`, `logs`, `console` |
| `WatchSmokeIT` | `watch` (detached; first deploy must complete and land in history) |
| `UnsupportedFeatureSmokeIT` | endpoints we 501 (`schedule list`, `webhook list`, `org search`, `policy ls`, `env ls`) |

Command-by-command coverage, including what is deliberately unsupported, is
tracked in `design/pulumi-command-coverage.md`.

The fault tier lives in the `faults` package: `*FaultIT` classes extend
`PulumiFaultBase` (process-kill, lease-expiry, DB-poll helpers) and annotate each
scenario with `@FaultScenario("<the guarantee it proves>")` so it lands in the
State Safety Report.

| Class | Scenario |
|---|---|
| `CrashRecoveryFaultIT` | CLI SIGKILLed mid-update; pending-op breadcrumb; recovery after lease expiry |
| `ConcurrentUpdateFaultIT` | two concurrent `pulumi up` runs; exactly one wins |
| `FailedDeploymentFaultIT` | provider error mid-deploy; completed resources retained; corrected up succeeds |
| `SecretsDurabilityFaultIT` | compaction invisible to the CLI; secrets decryptable from a fresh PULUMI_HOME |
| `CorruptedImportFaultIT` | tampered checkpoint rejected pre-persistence; prior state untouched |

### Adding a test

Put it in the matching `*SmokeIT` class (create a new `*SmokeIT extends
PulumiSmokeBase` for a new command family). **Each `@Test` spins its own Pulumi
container**, so group related assertions into one method rather than splitting
per command — a tag round-trip belongs in a single method, not four. Reach for
the base helpers (`recorderProjectContainer`, `singleProjectContainer`,
`exposeBackend`, `execResult` / `execJson` / `execAllowingFailure`) instead of
re-deriving container setup.
