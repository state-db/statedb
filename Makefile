# Verification and notices live behind ./dev; image and release operations live in bin/.
# Keep Make for workflows that compose scanners or delegate to tool/workbench Makefiles.
.PHONY: \
	analyze analyze-semgrep analyze-deps \
	pulumi-extract pulumi-compare pulumi-discover pulumi-api-test \
	$(PULUMI_RECORDER_TARGETS) \
	pulumi-verify-build pulumi-verify-test \
	pulumi-workbench soak \
	pulumi-compat-sweep

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

analyze:
	./dev check
	@for t in analyze-semgrep analyze-deps; do \
		echo "==> $$t (report only)"; \
		$(MAKE) $$t || true; \
	done

# These use maintained scanner images instead of adding rarely used tools to the pinned toolchain.
analyze-semgrep:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(SEMGREP_IMAGE) \
		semgrep scan $(SEMGREP_RULES) \
		--oss-only --metrics=off --error \
		--exclude=node_modules --exclude=dist --exclude=target

# OSV findings are a triage report: transitive fixes may require a platform upgrade.
analyze-deps:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src $(OSV_IMAGE) \
		scan source -r /src

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
