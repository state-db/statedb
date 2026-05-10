.PHONY: build run run-no-build catalog verify clean-fixtures discover

RECORDER_IMAGE ?= pulumi-recorder
FIXTURES_DIR   ?= $(CURDIR)/fixtures
SNAPSHOTS_DIR  ?= $(abspath $(CURDIR)/../../../api-snapshots/pulumi)
ORG            ?= testorg
PROJECT        ?= recorder-test
STACK          ?= dev

build:
	docker build -t $(RECORDER_IMAGE) .

run:
	./run.sh

run-no-build:
	./run.sh --no-build

catalog:
	go run ./catalog \
		-fixtures $(FIXTURES_DIR) \
		-out $(SNAPSHOTS_DIR)/catalog.md \
		-org $(ORG) \
		-project $(PROJECT) \
		-stack $(STACK)

verify:
	go test ./...
	go build ./...

clean-fixtures:
	rm -rf $(FIXTURES_DIR)/by-command $(FIXTURES_DIR)/meta.json

# End-to-end refresh for recorder fixtures + catalog.
discover: run
