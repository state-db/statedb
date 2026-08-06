.PHONY: \
	app-dev app-build app-image app-test app-clean \
	release release-gate \
	web-dev \
	format format-check \
	analyze analyze-java analyze-web analyze-semgrep analyze-deps \
	notices notices-check \
	pulumi-extract pulumi-compare pulumi-discover pulumi-api-test \
	pulumi-recorder-run pulumi-recorder-run-no-build pulumi-recorder-catalog pulumi-recorder-verify pulumi-recorder-discover \
	pulumi-verify-build pulumi-verify-test \
	pulumi-compat-sweep \
	pulumi-workbench \
	soak

PULUMI_SCOPE         ?= $(CURDIR)/tools/pulumi/api-extract/scopes/state-backend.yaml
PULUMI_SNAPSHOTS_DIR ?= $(CURDIR)/api-snapshots/pulumi

## -------------------------
## state-db Quarkus app
## -------------------------
## Project build/test/run targets route through ./bin/in-container, which executes the command
## inside the dev container (.devcontainer/devcontainer.json). Running JVM, Maven, Bun, or DB
## targets on the host is not supported. The wrapper is a no-op when invoked from inside the
## container, so nested calls don't bounce.
##
## Deliberate exceptions: app-image, release, and release-gate talk to host Docker directly. They
## build or run their own containers, and the image build's Maven/Bun toolchains live inside Docker
## stages rather than on the host.

IN_CONTAINER := ./bin/in-container

APP_IMAGE ?= state-db:dev

## Empty docker config dir: dodges a credential helper that errors on anonymous pulls.
DOCKER_CFG ?= /tmp/state-db-dockercfg

## Run the app in dev mode with live reload. Quarkus Dev Services
## auto-starts an ephemeral Postgres container on the host's Docker.
## Listens on http://localhost:8080.
app-dev:
	$(IN_CONTAINER) mvn -f modules/pom.xml -pl app -am quarkus:dev

## Build a runnable JAR (target/quarkus-app/quarkus-run.jar).
app-build:
	$(IN_CONTAINER) mvn -f modules/pom.xml -pl app -am package -DskipTests

## Build the runtime image. Multi-stage, so it needs no local JDK, Maven or bun.
## The built image expects a memory limit; see JAVA_OPTS in Dockerfile.jvm.
app-image:
	sh -lc 'mkdir -p $(DOCKER_CFG) && export DOCKER_CONFIG=$(DOCKER_CFG) \
		&& eval "$$(./bin/build-version --env)" \
		&& docker build \
			-f modules/app/src/main/docker/Dockerfile.jvm \
			--build-arg BUILD_VERSION="$$BUILD_VERSION" \
			--build-arg BUILD_COMMIT="$$BUILD_COMMIT" \
			--build-arg SOURCE_DATE_EPOCH="$$SOURCE_DATE_EPOCH" \
			-t $(APP_IMAGE) .'
	@echo "Built $(APP_IMAGE)"

## Run unit + integration tests across all modules.
app-test:
	$(IN_CONTAINER) mvn -f modules/pom.xml test

## Clean all module build outputs.
app-clean:
	$(IN_CONTAINER) mvn -f modules/pom.xml clean

## -------------------------
## Release
## -------------------------
## Built and pushed from a maintainer's machine, never CI. See design/release.md.
## These manage their own containers, so they talk to Docker directly, not via ./bin/in-container.

## Verify a candidate image by running it.
## Usage: make release-gate IMAGE=state-db:dev [PREVIOUS=ghcr.io/state-db/state-db:0.1.0]
release-gate:
ifndef IMAGE
	$(error IMAGE is required, e.g. make release-gate IMAGE=$(APP_IMAGE))
endif
	./bin/release-gate $(IMAGE) $(if $(PREVIOUS),--previous $(PREVIOUS))

## Cut a release: verify, build, gate, push, sign, tag, roll the changelog.
## Usage: make release [ARGS=--dry-run|--rehearse]
release:
	./bin/release $(ARGS)

## Run the web console dev server (bun, http://localhost:5173) with HMR.
## Proxies /api and /pulumi to the Quarkus app on :8080, so run `app-dev` too.
web-dev:
	$(IN_CONTAINER) bash -lc 'cd modules/web && bun install && bun dev.ts'

## -------------------------
## Pulumi Workbench
## -------------------------
## Forward to workbench/pulumi/Makefile.
## Usage: make pulumi-workbench cmd=up args="STACK=scratch"
pulumi-workbench:
ifndef cmd
	$(error cmd is required, e.g. make pulumi-workbench cmd=up)
endif
	$(MAKE) -C workbench/pulumi $(cmd) $(args)

## -------------------------
## Soak / endurance harness
## -------------------------
## Forward to workbench/soak/Makefile.
## Usage: make soak cmd=up args="SOAK_DURATION_HOURS=12 SOAK_RESOURCES=500"
soak:
ifndef cmd
	$(error cmd is required, e.g. make soak cmd=up)
endif
	$(MAKE) -C workbench/soak $(cmd) $(args)

## -------------------------
## Formatting
## -------------------------
## Java + pom.xml + YAML + Markdown via Spotless (Google Java Format).
## Go via gofmt. `mvn verify` enforces Spotless; CI should also run format-check
## to cover gofmt.

## Apply formatting to all source files in place.
format:
	$(IN_CONTAINER) mvn -f modules/pom.xml spotless:apply
	$(IN_CONTAINER) gofmt -w tools

## Verify all source files are formatted. Non-zero exit on any diff.
format-check:
	$(IN_CONTAINER) mvn -f modules/pom.xml spotless:check
	@unformatted=$$($(IN_CONTAINER) gofmt -l tools); \
	if [ -n "$$unformatted" ]; then \
		echo "gofmt: the following files need formatting:"; \
		echo "$$unformatted"; \
		exit 1; \
	fi

## -------------------------
## Static analysis
## -------------------------
## Four passes, each runnable on its own.
##
## Deliberate exception to the dev-container rule: semgrep and osv-scanner are
## not installed in the dev container, and both ship official images. They run
## from the host against a read-only bind mount of the working tree, the same
## way tools/pulumi/recorder manages its own image.

SEMGREP_IMAGE ?= semgrep/semgrep:latest
OSV_IMAGE     ?= ghcr.io/google/osv-scanner:latest
SEMGREP_RULES ?= --config=p/java --config=p/typescript --config=p/react \
                 --config=p/owasp-top-ten --config=p/secrets

## Run every pass, then fail only on the two with an enforced-clean baseline.
##
## analyze-java and analyze-web are gates: both are green today, so a non-zero
## exit means this change introduced something. analyze-semgrep and analyze-deps
## report findings that are not all actionable here — third-party CVEs reachable
## only through a Quarkus platform bump, workflow and Dockerfile hygiene — so
## they print but do not decide the exit code. Run them directly to gate on one.
analyze:
	@rc=0; \
	for t in analyze-java analyze-web; do \
		echo "==> $$t"; \
		$(MAKE) $$t || rc=1; \
	done; \
	for t in analyze-semgrep analyze-deps; do \
		echo "==> $$t (report only)"; \
		$(MAKE) $$t || true; \
	done; \
	exit $$rc

## SpotBugs + find-sec-bugs, also bound to `mvn verify` so CI enforces it.
## Suppressions live in modules/spotbugs-exclude.xml.
analyze-java:
	$(IN_CONTAINER) mvn -f modules/pom.xml compile spotbugs:check

## ESLint (type-aware rules + React Hooks) and the TypeScript compiler.
analyze-web:
	$(IN_CONTAINER) bash -lc 'cd modules/web && bun install && bun run lint && bun run typecheck'

## Semgrep OSS. --oss-only keeps it off the hosted Pro engine (no login, no
## code leaves the machine); --metrics=off suppresses usage telemetry.
analyze-semgrep:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(SEMGREP_IMAGE) \
		semgrep scan $(SEMGREP_RULES) \
		--oss-only --metrics=off --error \
		--exclude=node_modules --exclude=dist --exclude=target

## Known vulnerabilities across pom.xml, bun.lock and go.mod. Exits non-zero on
## any finding, including transitives no direct bump can reach — a report to
## triage, not a gate.
analyze-deps:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(OSV_IMAGE) \
		scan source -r /src

## -------------------------
## Third-party notices
## -------------------------
## THIRD-PARTY-NOTICES.txt attributes the open-source components bundled in
## the server and web console. Regenerate after dependency changes; CI runs
## the check and fails when the committed file is stale.

notices:
	./bin/generate-third-party-notices

notices-check:
	./bin/generate-third-party-notices --check

## -------------------------
## Pulumi API Extractor
## -------------------------

## Extract Pulumi API endpoints for a specific version.
## Usage: make pulumi-extract PULUMI_REF=v3.234.0
## Run without scope filter: make pulumi-extract PULUMI_REF=v3.234.0 PULUMI_SCOPE=
pulumi-extract:
ifndef PULUMI_REF
	$(error PULUMI_REF is required, e.g. make pulumi-extract PULUMI_REF=v3.234.0)
endif
	$(MAKE) -C tools/pulumi/api-extract extract \
		REF=$(PULUMI_REF) \
		PULUMI_SCOPE=$(PULUMI_SCOPE) \
		PULUMI_SNAPSHOTS_DIR=$(PULUMI_SNAPSHOTS_DIR)

## Compare Pulumi API endpoints between two versions.
## Usage: make pulumi-compare FROM=v3.220.0 TO=v3.234.0
pulumi-compare:
ifndef FROM
	$(error FROM is required)
endif
ifndef TO
	$(error TO is required)
endif
	$(MAKE) -C tools/pulumi/api-extract compare \
		FROM=$(FROM) \
		TO=$(TO) \
		PULUMI_SNAPSHOTS_DIR=$(PULUMI_SNAPSHOTS_DIR)

## Extract TO version and compare FROM -> TO in one step.
## Usage: make pulumi-discover FROM=v3.233.0 TO=v3.234.0
pulumi-discover:
ifndef FROM
	$(error FROM is required)
endif
ifndef TO
	$(error TO is required)
endif
	$(MAKE) -C tools/pulumi/api-extract discover \
		FROM=$(FROM) \
		TO=$(TO) \
		REF=$(TO) \
		PULUMI_SCOPE=$(PULUMI_SCOPE) \
		PULUMI_SNAPSHOTS_DIR=$(PULUMI_SNAPSHOTS_DIR)

## Run tests for the Pulumi API extractor tool.
pulumi-api-test:
	$(MAKE) -C tools/pulumi/api-extract test

## -------------------------
## Pulumi Recorder
## -------------------------

## Build + run full recorder capture flow (fixtures + catalog).
pulumi-recorder-run:
	$(MAKE) -C tools/pulumi/recorder run

## Run recorder without rebuilding Docker image.
pulumi-recorder-run-no-build:
	$(MAKE) -C tools/pulumi/recorder run-no-build

## Regenerate catalog from existing fixtures.
pulumi-recorder-catalog:
	$(MAKE) -C tools/pulumi/recorder catalog

## Verify recorder binaries/tests.
pulumi-recorder-verify:
	$(MAKE) -C tools/pulumi/recorder verify

## AI harness style end-to-end recording target.
pulumi-recorder-discover:
	$(MAKE) -C tools/pulumi/recorder discover

## -------------------------
## Pulumi Integrity Verifier
## -------------------------

## Build the integrity verifier Docker image (statedb-pulumi-verify).
pulumi-verify-build:
	$(MAKE) -C tools/pulumi/verify build

## Run tests for the integrity verifier tool.
pulumi-verify-test:
	$(MAKE) -C tools/pulumi/verify verify

## -------------------------
## Pulumi Compatibility Sweep
## -------------------------

## One-command compat check against a Pulumi release: pin audit + source-level
## endpoint diff + trial recorder capture diffed against committed fixtures.
## Outputs stay in the gitignored scratch dir; pass --publish when adopting.
## Usage: make pulumi-compat-sweep [TO=v3.253.0]   (default: latest release)
##        make pulumi-compat-sweep SWEEP_ARGS=--skip-record
##        make pulumi-compat-sweep TO=v3.253.0 SWEEP_ARGS=--publish
pulumi-compat-sweep:
	tools/pulumi/compat/sweep.sh $(if $(TO),--to $(TO)) $(SWEEP_ARGS)
