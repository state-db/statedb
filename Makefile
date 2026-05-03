.PHONY: pulumi-extract pulumi-compare

PULUMI_SCOPE        ?= $(CURDIR)/tools/pulumi/api-extract/scopes/state-backend.yaml
PULUMI_SNAPSHOTS_DIR ?= $(CURDIR)/api-snapshots/pulumi

## Extract Pulumi API endpoints for a specific version.
## Usage: make pulumi-extract PULUMI_REF=v3.234.0
## Run without scope filter: make pulumi-extract PULUMI_REF=v3.234.0 PULUMI_SCOPE=
pulumi-extract:
ifndef PULUMI_REF
	$(error PULUMI_REF is required, e.g. make pulumi-extract PULUMI_REF=v3.234.0)
endif
	cd tools/pulumi/api-extract && go run . extract \
		--ref=$(PULUMI_REF) \
		--output=$(PULUMI_SNAPSHOTS_DIR)/$(PULUMI_REF) \
		$(if $(PULUMI_SCOPE),--scope=$(PULUMI_SCOPE))

## Compare Pulumi API endpoints between two versions.
## Usage: make pulumi-compare FROM=v3.220.0 TO=v3.234.0
pulumi-compare:
ifndef FROM
	$(error FROM is required)
endif
ifndef TO
	$(error TO is required)
endif
	cd tools/pulumi/api-extract && go run . compare \
		--from=$(FROM) \
		--to=$(TO) \
		--snapshots-dir=$(PULUMI_SNAPSHOTS_DIR)
