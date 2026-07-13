.PHONY: build verify

VERIFY_IMAGE ?= statedb-pulumi-verify

build:
	docker build -t $(VERIFY_IMAGE) .

verify:
	go test ./...
	go build ./...
