.PHONY: help extract compare discover test

ROOT_DIR ?= $(abspath ../../..)
PULUMI_SCOPE ?= $(ROOT_DIR)/tools/pulumi/api-extract/scopes/state-backend.yaml
PULUMI_SNAPSHOTS_DIR ?= $(ROOT_DIR)/api-snapshots/pulumi

help:
	@echo "api-extract targets:"
	@echo "  make -C tools/pulumi/api-extract extract REF=v3.234.0 [PULUMI_SCOPE=...]"
	@echo "  make -C tools/pulumi/api-extract compare FROM=v3.233.0 TO=v3.234.0"
	@echo "  make -C tools/pulumi/api-extract discover FROM=v3.233.0 TO=v3.234.0"
	@echo "  make -C tools/pulumi/api-extract test"

extract:
ifndef REF
	$(error REF is required, e.g. REF=v3.234.0)
endif
	go run . extract \
		--ref=$(REF) \
		--output=$(PULUMI_SNAPSHOTS_DIR)/$(REF) \
		$(if $(PULUMI_SCOPE),--scope=$(PULUMI_SCOPE))

compare:
ifndef FROM
	$(error FROM is required)
endif
ifndef TO
	$(error TO is required)
endif
	go run . compare \
		--from=$(FROM) \
		--to=$(TO) \
		--snapshots-dir=$(PULUMI_SNAPSHOTS_DIR)

# One command for "discover what changed".
discover: extract compare
	@true

test:
	go test ./...
