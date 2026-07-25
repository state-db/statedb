.PHONY: provider stage build up down destroy logs status artifacts report psql unlock

REPO_ROOT    := ../..
IN_CONTAINER := $(REPO_ROOT)/bin/in-container
COMPOSE_DIR  := workbench/soak

APP_IMAGE ?= state-db:dev
ORG       ?= soak
DB_USER   ?= statedb
DB_NAME   ?= statedb
DB_PORT   ?= 55433
APP_PORT  ?= 28080
MGMT_PORT ?= 29000
PROM_PORT ?= 29090

## Run shape. Defaults model a small self-hosted deployment: 2 CPU / 2GB, four stacks of 300
## resources churned continuously for two days.
SOAK_MEMORY          ?= 2g
SOAK_CPUS            ?= 2.0
SOAK_DURATION_HOURS  ?= 48
SOAK_STACKS          ?= 4
SOAK_RESOURCES       ?= 300
SOAK_PARALLEL        ?= 8
SOAK_PAYLOAD_BYTES   ?= 2048
SOAK_CONSOLE_READS   ?= 1
SOAK_SAMPLE_INTERVAL ?= 60
SOAK_DESTROY_EVERY   ?= 5
SOAK_FAULT_EVERY     ?= 7
SOAK_CONTENTION_EVERY ?= 3
SOAK_PREVIEW_EVERY   ?= 2

## Empty docker config dir: bypasses a credential helper that errors on anonymous pulls.
DOCKER_CFG ?= /tmp/state-db-dockercfg

## Compose reads the run shape from the environment, so tunables are exported, not passed as Make
## variables.
COMPOSE = cd "$$(git rev-parse --show-toplevel)/$(COMPOSE_DIR)" && \
	export DOCKER_CONFIG=$(DOCKER_CFG) APP_IMAGE=$(APP_IMAGE) ORG=$(ORG) \
		DB_USER=$(DB_USER) DB_NAME=$(DB_NAME) DB_PORT=$(DB_PORT) \
		APP_PORT=$(APP_PORT) MGMT_PORT=$(MGMT_PORT) PROM_PORT=$(PROM_PORT) \
		SOAK_MEMORY=$(SOAK_MEMORY) SOAK_CPUS=$(SOAK_CPUS) \
		SOAK_DURATION_HOURS=$(SOAK_DURATION_HOURS) SOAK_STACKS=$(SOAK_STACKS) \
		SOAK_RESOURCES=$(SOAK_RESOURCES) SOAK_PARALLEL=$(SOAK_PARALLEL) \
		SOAK_PAYLOAD_BYTES=$(SOAK_PAYLOAD_BYTES) SOAK_CONSOLE_READS=$(SOAK_CONSOLE_READS) \
		SOAK_SAMPLE_INTERVAL=$(SOAK_SAMPLE_INTERVAL) \
		SOAK_DESTROY_EVERY=$(SOAK_DESTROY_EVERY) SOAK_FAULT_EVERY=$(SOAK_FAULT_EVERY) \
		SOAK_CONTENTION_EVERY=$(SOAK_CONTENTION_EVERY) SOAK_PREVIEW_EVERY=$(SOAK_PREVIEW_EVERY) && \
	mkdir -p "$$DOCKER_CONFIG" && docker compose

## Build the statedbsim provider binary via the workbench's Go builder container.
provider:
	$(MAKE) -C $(REPO_ROOT)/workbench/pulumi scale-provider-build

## Stage build inputs; the repo root as context would drag in workbench/.generated.
stage: provider
	$(IN_CONTAINER) sh -lc 'repo="$$(git rev-parse --show-toplevel)"; \
		cd "$$repo/$(COMPOSE_DIR)"; \
		mkdir -p .build; \
		cp "$$repo/workbench/pulumi/providers/statedbsim/bin/pulumi-resource-statedbsim" .build/; \
		cp "$$repo/workbench/pulumi/projects/scale-simulator/generate.sh" .build/; \
		chmod +x .build/generate.sh'

## Build the app image and the load/sampler image.
build: stage
	$(MAKE) -C $(REPO_ROOT) app-image APP_IMAGE=$(APP_IMAGE)
	$(IN_CONTAINER) sh -lc '$(COMPOSE) build'

## Start the stack. Runs SOAK_DURATION_HOURS then idles so the quiesce window is recorded.
up: build
	$(IN_CONTAINER) sh -lc '$(COMPOSE) up -d'
	@echo ""
	@echo "soak running."
	@echo "  console/API   http://localhost:28080"
	@echo "  metrics       http://localhost:$(MGMT_PORT)/q/metrics"
	@echo "  prometheus    http://localhost:29090"
	@echo "  progress      make soak cmd=logs"
	@echo "  grade the run make soak cmd=report"

## Stop the stack, keeping volumes so the run can still be reported on.
down:
	-$(IN_CONTAINER) sh -lc '$(COMPOSE) down'

## Stop the stack and delete its volumes, including the recorded series.
destroy:
	-$(IN_CONTAINER) sh -lc '$(COMPOSE) down -v'

logs:
	$(IN_CONTAINER) sh -lc '$(COMPOSE) logs -f --tail=100 load sampler app'

## Current state at a glance without waiting for the run to finish.
status:
	@$(IN_CONTAINER) sh -lc '$(COMPOSE) ps'
	@echo ""
	@$(IN_CONTAINER) sh -lc 'curl -fsS --max-time 5 http://host.docker.internal:$(MGMT_PORT)/q/metrics 2>/dev/null \
		| grep -E "^(jvm_memory_used_bytes\{area=\"heap\"|agroal_(active|awaiting)_count|jvm_threads_live)" \
		|| echo "app not reachable on :$(MGMT_PORT)"'

## Copy results out. A throwaway container makes this work whether or not the sampler is running.
artifacts:
	$(IN_CONTAINER) sh -lc 'repo="$$(git rev-parse --show-toplevel)"; \
		cd "$$repo/$(COMPOSE_DIR)"; \
		export DOCKER_CONFIG=$(DOCKER_CFG); \
		mkdir -p artifacts; \
		docker rm -f state-db-soak-copy >/dev/null 2>&1 || true; \
		docker create --name state-db-soak-copy -v state-db-soak-artifacts:/artifacts \
			state-db-soak-load:local true >/dev/null; \
		docker cp state-db-soak-copy:/artifacts/. artifacts/; \
		docker rm state-db-soak-copy >/dev/null'
	@echo "artifacts copied to $(COMPOSE_DIR)/artifacts/"

## Grade the run. Non-zero exit if any check fails.
report: artifacts
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/$(COMPOSE_DIR)" && \
		export DOCKER_CONFIG=$(DOCKER_CFG) && ./report.sh'

psql:
	$(IN_CONTAINER) sh -lc 'DOCKER_CONFIG=$(DOCKER_CFG) docker exec -it state-db-soak-postgres \
		psql -U $(DB_USER) -d $(DB_NAME)'

## Clear a stuck update/lock so a wedged run can continue without a full restart.
unlock:
	$(IN_CONTAINER) sh -lc 'DOCKER_CONFIG=$(DOCKER_CFG) docker exec -i state-db-soak-postgres \
		psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE stack_updates SET status = '\''failed'\'', ended_at = now(), updated_at = now() WHERE status = '\''in-progress'\''" \
		-c "UPDATE stacks SET active_update_id = NULL, updated_at = now() WHERE active_update_id IS NOT NULL" \
		-c "DELETE FROM stack_locks"'
