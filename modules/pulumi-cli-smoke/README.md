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
