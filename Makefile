.PHONY: \
	FORCE \
	app-dev app-build app-image app-test app-clean \
	release release-gate \
	web-dev \
	format format-check \
	analyze analyze-java analyze-web analyze-semgrep analyze-deps \
	notices notices-check \
	pulumi-extract pulumi-compare pulumi-discover pulumi-api-test \
	$(PULUMI_RECORDER_TARGETS) \
	pulumi-verify-build pulumi-verify-test \
	pulumi-workbench soak \
	pulumi-compat-sweep

IN_CONTAINER := ./bin/in-container
MODULES_POM  := modules/pom.xml

APP_IMAGE ?= state-db:dev
DOCKER_CFG ?= /tmp/state-db-dockercfg

PULUMI_SCOPE         ?= $(CURDIR)/tools/pulumi/api-extract/scopes/state-backend.yaml
PULUMI_SNAPSHOTS_DIR ?= $(CURDIR)/api-snapshots/pulumi

SEMGREP_IMAGE ?= semgrep/semgrep:latest
OSV_IMAGE     ?= ghcr.io/google/osv-scanner:latest
SEMGREP_RULES ?= --config=p/java --config=p/typescript --config=p/react \
                 --config=p/owasp-top-ten --config=p/secrets

PULUMI_RECORDER_TARGETS := \
	pulumi-recorder-run \
	pulumi-recorder-run-no-build \
	pulumi-recorder-catalog \
	pulumi-recorder-verify \
	pulumi-recorder-discover

app-dev:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) -pl app -am quarkus:dev

app-build:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) -pl app -am package -DskipTests

# Host Docker is intentional here; the Maven/Bun toolchains run inside Docker build stages.
app-image:
	DOCKER_CFG='$(DOCKER_CFG)' ./bin/build-app-image '$(APP_IMAGE)'

app-test:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) test

app-clean:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) clean

web-dev:
	$(IN_CONTAINER) bash -lc 'cd modules/web && bun install && bun dev.ts'

# Release scripts build, run, push, and sign containers from a maintainer's host.
release-gate:
ifndef IMAGE
	$(error IMAGE is required, e.g. make release-gate IMAGE=$(APP_IMAGE))
endif
	./bin/release-gate $(IMAGE) $(if $(PREVIOUS),--previous $(PREVIOUS))

release:
	./bin/release $(ARGS)

format:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) spotless:apply
	$(IN_CONTAINER) gofmt -w tools

format-check:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) spotless:check
	@unformatted=$$($(IN_CONTAINER) gofmt -l tools); \
	if [ -n "$$unformatted" ]; then \
		echo "gofmt: the following files need formatting:"; \
		echo "$$unformatted"; \
		exit 1; \
	fi

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

analyze-java:
	$(IN_CONTAINER) mvn -f $(MODULES_POM) compile spotbugs:check

analyze-web:
	$(IN_CONTAINER) bash -lc 'cd modules/web && bun install && bun run lint && bun run typecheck'

# These use maintained scanner images instead of adding rarely used tools to the dev container.
analyze-semgrep:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(SEMGREP_IMAGE) \
		semgrep scan $(SEMGREP_RULES) \
		--oss-only --metrics=off --error \
		--exclude=node_modules --exclude=dist --exclude=target

analyze-deps:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(OSV_IMAGE) \
		scan source -r /src

notices:
	./bin/generate-third-party-notices

notices-check:
	./bin/generate-third-party-notices --check

pulumi-workbench:
ifndef cmd
	$(error cmd is required, e.g. make pulumi-workbench cmd=up)
endif
	$(MAKE) -C workbench/pulumi $(cmd) $(args)

soak:
ifndef cmd
	$(error cmd is required, e.g. make soak cmd=up)
endif
	$(MAKE) -C workbench/soak $(cmd) $(args)

pulumi-extract:
ifndef PULUMI_REF
	$(error PULUMI_REF is required, e.g. make pulumi-extract PULUMI_REF=v3.234.0)
endif
	$(MAKE) -C tools/pulumi/api-extract extract \
		REF=$(PULUMI_REF) \
		PULUMI_SCOPE=$(PULUMI_SCOPE) \
		PULUMI_SNAPSHOTS_DIR=$(PULUMI_SNAPSHOTS_DIR)

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

pulumi-api-test:
	$(MAKE) -C tools/pulumi/api-extract test

$(PULUMI_RECORDER_TARGETS):
	$(MAKE) -C tools/pulumi/recorder $(patsubst pulumi-recorder-%,%,$@)

pulumi-verify-build:
	$(MAKE) -C tools/pulumi/verify build

pulumi-verify-test:
	$(MAKE) -C tools/pulumi/verify verify

pulumi-compat-sweep:
	tools/pulumi/compat/sweep.sh $(if $(TO),--to $(TO)) $(SWEEP_ARGS)

FORCE:
