.PHONY: \
	pulumi-extract pulumi-compare pulumi-discover pulumi-api-test \
	pulumi-recorder-run pulumi-recorder-run-no-build pulumi-recorder-catalog pulumi-recorder-verify pulumi-recorder-discover

PULUMI_SCOPE         ?= $(CURDIR)/tools/pulumi/api-extract/scopes/state-backend.yaml
PULUMI_SNAPSHOTS_DIR ?= $(CURDIR)/api-snapshots/pulumi

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
