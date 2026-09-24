.PHONY: infra-up infra-down db-up console-seed app app-dev app-stop build-image build-image-if-missing check-app login init preview up destroy fixtures-up fixtures-destroy churn-preview churn-up scale-provider-test scale-provider-build scale-provider-install scale-generate scale-preview scale-up scale-fault-up scale-destroy sql psql force-unlock reset glass-on glass-off

REPO_ROOT := ../..

DB_CONTAINER ?= state-db-workbench-postgres
DB_VOLUME    ?= state-db-workbench-postgres-data
DB_IMAGE     ?= postgres:16
DB_PORT      ?= 55432
DB_NAME      ?= statedb
DB_USER      ?= statedb
DB_PASSWORD  ?= statedb
ORG          ?= acme
STACK        ?= dev
STACK_REF    ?= $(ORG)/$(STACK)
TOKEN        ?= state-db-workbench-token
TOKEN_HASH   ?= 5ffbb9af69ff36cfa7e28cea7b3da597ed0d0489113674b3eb3a6c2b7e386ef5
## Console break-glass password is literally "password" (dev-only; bcrypt via BcryptUtil).
## Break-glass is gated off while the mock IdP provider row is enabled — toggle with
## `cmd=glass-on` / `cmd=glass-off`.
GLASS_PASSWORD_HASH ?= $$2a$$10$$T56PF8UE80K1VeLu6iMJjOLW68rR.xHzHUppcyvcd2ZAudpNUgn9O
BACKEND_URL  ?= http://localhost:8080/pulumi
## Preflight budget. Instant locally, but a remote BACKEND_URL pays a TLS handshake
## that measured 2-4s cold.
CHECK_TIMEOUT ?= 10

## `cmd=app` runs this image, building it the first time it's missing (see
## build-image-if-missing). Rebuild after code changes with `cmd=build-image`.
STATEDB_IMAGE ?= state-db:workbench

## deploy-simulator churn runs: per-step sleep and target stack.
CHURN_SECONDS   ?= 6
CHURN_STACK     ?= dev
CHURN_STACK_REF ?= $(ORG)/$(CHURN_STACK)

## High-cardinality local provider scale runs.
SIM_PROVIDER_VERSION       ?= 0.1.0
SIM_PROVIDER_BUILDER_IMAGE ?= golang:1.26-bookworm
## The host Pulumi CLI execs this plugin, so the build targets the host OS/arch; the
## container default would install a Linux binary. Tests still run natively in the container.
SIM_PROVIDER_GOOS          ?= $(shell uname -s | tr 'A-Z' 'a-z')
SIM_PROVIDER_GOARCH        ?= $(shell uname -m | sed -e 's/^x86_64$$/amd64/' -e 's/^aarch64$$/arm64/')
SIM_PROVIDER_OUT           ?= bin/pulumi-resource-statedbsim
SCALE_STACK                ?= scale
SCALE_STACK_REF            ?= $(ORG)/$(SCALE_STACK)
SCALE_RESOURCES            ?= 250
SCALE_DEPTH                ?= 8
SCALE_FANOUT               ?= 4
SCALE_REVISION             ?= 1
SCALE_PAYLOAD_BYTES        ?= 1024
SCALE_DIFF_DELAY_MS        ?= 0
## Creates are throttled so a run resembles a real cloud deploy instead of finishing instantly.
## First-up wall time is roughly SCALE_RESOURCES * avg_create_delay / SCALE_PARALLEL, where
## avg_create_delay = SCALE_CREATE_DELAY_MS + SCALE_CREATE_JITTER_MS / 2. The defaults land a
## 1000-resource `scale-up` at ~2 minutes (1000 * 1.0s / 8). Set both delays to 0 for instant runs.
SCALE_PARALLEL             ?= 8
SCALE_CREATE_DELAY_MS      ?= 600
SCALE_CREATE_JITTER_MS     ?= 800
SCALE_UPDATE_DELAY_MS      ?= 0
SCALE_DELETE_DELAY_MS      ?= 0
SCALE_REPLACEMENT_KEY      ?= stable
SCALE_FAIL_EVERY           ?= 0
SCALE_FAIL_SEED            ?= scale
SCALE_FAULT_INDEX          ?= 42
SCALE_FAULT_MODE           ?= once
SCALE_FAULT_OPERATION      ?= create

## Compose owns stable workbench infrastructure. The mock IdP is exposed only to the Bun dev server,
## which proxies /default/* on the existing browser-facing :5173 origin, so Quarkus and the browser
## use the same issuer without another forwarded port.
CONSOLE_PORT  ?= 5173
IDP_UPSTREAM_PORT ?= 58081
## Empty docker config dir: bypasses a credential helper that errors on anonymous ghcr.io pulls.
IDP_DOCKER_CFG ?= /tmp/state-db-workbench-dockercfg

OIDC_PROVIDER      ?= mock
OIDC_ISSUER        ?= http://localhost:$(CONSOLE_PORT)/default
OIDC_CLIENT_ID     ?= console
OIDC_CLIENT_SECRET ?= console-secret
## Sign in to the mock IdP with this subject to be treated as an admin in /api/user.
ADMIN_SUB          ?= admin

## PULUMI_CONSOLE_DOMAIN points every CLI-printed permalink (e.g. "View Live: ...")
## at the console dev server instead of the CLI's built-in default (localhost:3000),
## which nothing in the workbench listens on.
PULUMI_ENV := export \
	PULUMI_ACCESS_TOKEN=$(TOKEN) \
	PULUMI_CONFIG_PASSPHRASE=workbench \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" \
	PULUMI_SKIP_UPDATE_CHECK=true \
	PULUMI_SKIP_CONFIRMATIONS=true \
	PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT)

## Start the stable Postgres and mock IdP services declared in compose.yml.
infra-up:
	@export DOCKER_CONFIG=$(IDP_DOCKER_CFG) \
		DB_IMAGE=$(DB_IMAGE) DB_VOLUME=$(DB_VOLUME) DB_PORT=$(DB_PORT) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		IDP_UPSTREAM_PORT=$(IDP_UPSTREAM_PORT); \
	mkdir -p "$$DOCKER_CONFIG"; \
	postgres_state="$$(docker inspect -f '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $(DB_CONTAINER) 2>/dev/null || true)"; \
	idp_running="$$(docker inspect -f '{{.State.Running}}' state-db-workbench-idp 2>/dev/null || true)"; \
	if [ "$$postgres_state" = "true healthy" ] && [ "$$idp_running" = "true" ] \
		&& curl -fsS http://localhost:$(IDP_UPSTREAM_PORT)/default/.well-known/openid-configuration >/dev/null 2>&1; then \
		echo "Workbench infrastructure is already ready (Postgres :$(DB_PORT), mock IdP :$(IDP_UPSTREAM_PORT))"; \
		exit 0; \
	fi; \
	docker volume create $(DB_VOLUME) >/dev/null; \
	docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" up -d --wait --build; \
	for i in $$(seq 1 30); do curl -fsS http://localhost:$(IDP_UPSTREAM_PORT)/default/.well-known/openid-configuration >/dev/null && exit 0; sleep 1; done; \
	echo "Mock IdP did not become ready"; exit 1
	@echo "Workbench infrastructure is ready (Postgres :$(DB_PORT), mock IdP behind :$(CONSOLE_PORT)/default)"

## Stop infrastructure while preserving the Postgres volume.
infra-down:
	-export DOCKER_CONFIG=$(IDP_DOCKER_CFG) DB_VOLUME=$(DB_VOLUME); \
	docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" down

## Compatibility alias for the previous granular startup command.
db-up: infra-up

## Seed workbench OIDC users as members of org $(ORG) so /api/user shows orgs.
## Idempotent; `app`/`app-dev` run this automatically in the background, but you can re-run it standalone.
console-seed: db-up
	DB_CONTAINER=$(DB_CONTAINER) DB_USER=$(DB_USER) DB_NAME=$(DB_NAME) ORG=$(ORG) \
		OIDC_ISSUER=$(OIDC_ISSUER) ADMIN_SUB=$(ADMIN_SUB) TOKEN_HASH=$(TOKEN_HASH) \
		sh "$$(git rev-parse --show-toplevel)/workbench/pulumi/seed-console.sh"

## Build (or rebuild) the image `cmd=app` runs. Run this again after code changes —
## `cmd=app` only builds automatically when the image is missing entirely.
build-image:
	"$$(git rev-parse --show-toplevel)/bin/build-native-app-image" $(STATEDB_IMAGE)

build-image-if-missing:
	@docker image inspect $(STATEDB_IMAGE) >/dev/null 2>&1 || { \
		echo "==> $(STATEDB_IMAGE) not built yet — building now (first run only, several minutes)"; \
		$(MAKE) build-image; \
	}

## Run the full stack from a built image (default, fast to start, matches what ships).
## Builds $(STATEDB_IMAGE) automatically the first time; `cmd=build-image` to refresh it.
app: build-image-if-missing
	@$(MAKE) app-stop >/dev/null 2>&1 || true
	STATEDB_IMAGE=$(STATEDB_IMAGE) ORG=$(ORG) CONSOLE_PORT=$(CONSOLE_PORT) \
		IDP_UPSTREAM_PORT=$(IDP_UPSTREAM_PORT) DB_PORT=$(DB_PORT) DB_IMAGE=$(DB_IMAGE) \
		DB_VOLUME=$(DB_VOLUME) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		"$$(git rev-parse --show-toplevel)/workbench/pulumi/dev-host.sh"

## Run the full stack in Quarkus dev mode (hot reload) instead of a built image.
app-dev:
	@$(MAKE) app-stop >/dev/null 2>&1 || true
	ORG=$(ORG) CONSOLE_PORT=$(CONSOLE_PORT) IDP_UPSTREAM_PORT=$(IDP_UPSTREAM_PORT) \
		DB_PORT=$(DB_PORT) DB_IMAGE=$(DB_IMAGE) DB_VOLUME=$(DB_VOLUME) \
		DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		"$$(git rev-parse --show-toplevel)/workbench/pulumi/dev-host.sh"

## Stop stale foreground workbench processes. VS Code port forwarding (and just
## forgetting a terminal) can keep :5173/:8080/:5005/:9000 busy after the run
## that started them is gone.
app-stop:
	-docker stop state-db-workbench-app >/dev/null 2>&1 || true
	-pkill -TERM -f "[b]un dev.ts" || true
	-pkill -TERM -f "[q]uarkus:dev" || true
	-pkill -TERM -f "[s]tate-db-app-dev.jar" || true
	-sleep 1
	-pkill -KILL -f "[b]un dev.ts" || true
	-pkill -KILL -f "[q]uarkus:dev" || true
	-pkill -KILL -f "[s]tate-db-app-dev.jar" || true

## Fail early with a useful message if the state-db app is not running.
check-app:
	@curl -fs --max-time $(CHECK_TIMEOUT) -H "Authorization: token $(TOKEN)" "$(BACKEND_URL)/api/capabilities" >/dev/null || { \
		echo ""; \
		echo "state-db is not reachable at $(BACKEND_URL), or the workbench token was not accepted."; \
		echo "Start the workbench app in another terminal first:"; \
		echo "  make pulumi-workbench cmd=app"; \
		echo ""; \
		exit 1; \
	}

## Log the local Pulumi CLI into the workbench backend.
login: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && mkdir -p ../.pulumi-home && PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT) pulumi login $(BACKEND_URL)

## Create/select the workbench stack.
init: login
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi stack select $(STACK_REF) || pulumi stack init $(STACK_REF)

## Run a real Pulumi preview against statedb.
preview: init
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi preview

## Run a real Pulumi deployment against statedb.
up: init
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi up --yes --skip-preview

## Destroy the workbench stack resources through Pulumi.
destroy: init
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi destroy --yes --skip-preview

## Create or update the complete local-only fixture estate. The app must already be running.
fixtures-up: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && \
	export PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_CONFIG_PASSPHRASE=workbench PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	ORG=$(ORG) BACKEND_URL=$(BACKEND_URL) TOKEN=$(TOKEN) ./bootstrap-fixtures.sh

## Destroy resources in every fixture stack, preserving the empty stacks for inspection/reuse.
fixtures-destroy: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true \
	PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	while IFS="|" read -r project stacks owner; do \
		case "$$project" in ""|\#*) continue ;; esac; \
		for stack in $$stacks; do \
			echo "[fixtures] destroy $(ORG)/$$project/$$stack"; \
			(cd "projects/$$project" && pulumi destroy --yes --skip-preview --stack "$(ORG)/$$stack"); \
		done; \
	done < fixtures.txt

## Kick off a long-running preview on the deploy-simulator fixture project. A fresh
## deployNonce makes every simulated step report a change, and the invoke-based checks
## each sleep CHURN_SECONDS during the preview itself, so the run stays in-progress
## while you watch it live in the console.
churn-preview: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi/projects/deploy-simulator" && \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$(git rev-parse --show-toplevel)/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(CHURN_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(CHURN_STACK_REF); \
	pulumi config set stepSeconds $(CHURN_SECONDS); \
	pulumi config set deployNonce "churn-$$(date +%s)"; \
	pulumi preview --message "Slow churn preview"

## Deploy the simulator with a fresh nonce: every rollout step sleeps CHURN_SECONDS and
## logs its progress, so the update stays in-progress with visible resource churn.
churn-up: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi/projects/deploy-simulator" && \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$(git rev-parse --show-toplevel)/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(CHURN_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(CHURN_STACK_REF); \
	pulumi config set stepSeconds $(CHURN_SECONDS); \
	pulumi config set deployNonce "churn-$$(date +%s)"; \
	pulumi up --yes --skip-preview --message "Slow churn deployment"

## Run a command in a Go builder container mounted at the repo root (host has Pulumi but
## not necessarily Go, so statedbsim builds and tests happen here regardless). $(1) runs
## in the provider directory via `sh -c`.
define go-builder-run
	@mkdir -p $(REPO_ROOT)/workbench/.generated/go/pkg/mod $(REPO_ROOT)/workbench/.generated/go/build-cache $(IDP_DOCKER_CFG)
	DOCKER_CONFIG=$(IDP_DOCKER_CFG) docker run --rm \
		-u "$$(id -u):$$(id -g)" \
		-e GOMODCACHE=/src/workbench/.generated/go/pkg/mod \
		-e GOCACHE=/src/workbench/.generated/go/build-cache \
		-v "$(abspath $(REPO_ROOT))":/src \
		-w /src/workbench/pulumi/providers/statedbsim \
		$(SIM_PROVIDER_BUILDER_IMAGE) sh -c "$(1)"
endef

## Run statedbsim provider unit tests in an isolated Go builder container.
scale-provider-test:
	$(call go-builder-run,/usr/local/go/bin/go mod tidy && /usr/local/go/bin/go test ./...)

## Build the local-only statedbsim provider binary and write the plugin binary back into the worktree.
scale-provider-build:
	$(call go-builder-run,mkdir -p $(dir $(SIM_PROVIDER_OUT)) && GOOS=$(SIM_PROVIDER_GOOS) GOARCH=$(SIM_PROVIDER_GOARCH) CGO_ENABLED=0 /usr/local/go/bin/go build -o $(SIM_PROVIDER_OUT) .)

## Install the locally built statedbsim provider into the workbench-local Pulumi plugin cache.
scale-provider-install: scale-provider-build
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/pulumi"; \
	mkdir -p ../.pulumi-home; \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" \
		pulumi plugin install resource statedbsim $(SIM_PROVIDER_VERSION) \
			--file providers/statedbsim/bin/pulumi-resource-statedbsim \
			--reinstall

## Generate the scale-simulator Pulumi.yaml under workbench/.generated.
scale-generate:
	repo="$$(git rev-parse --show-toplevel)"; \
	SCALE_OUT="$$repo/workbench/.generated/pulumi/scale-simulator" \
	SCALE_RESOURCES=$(SCALE_RESOURCES) SCALE_DEPTH=$(SCALE_DEPTH) SCALE_FANOUT=$(SCALE_FANOUT) \
	SCALE_REVISION=$(SCALE_REVISION) SCALE_PAYLOAD_BYTES=$(SCALE_PAYLOAD_BYTES) \
	SCALE_DIFF_DELAY_MS=$(SCALE_DIFF_DELAY_MS) SCALE_CREATE_DELAY_MS=$(SCALE_CREATE_DELAY_MS) \
	SCALE_CREATE_JITTER_MS=$(SCALE_CREATE_JITTER_MS) \
	SCALE_UPDATE_DELAY_MS=$(SCALE_UPDATE_DELAY_MS) SCALE_DELETE_DELAY_MS=$(SCALE_DELETE_DELAY_MS) \
	SCALE_REPLACEMENT_KEY=$(SCALE_REPLACEMENT_KEY) SCALE_FAIL_EVERY=$(SCALE_FAIL_EVERY) \
	SCALE_FAIL_SEED=$(SCALE_FAIL_SEED) \
	"$$repo/workbench/pulumi/projects/scale-simulator/generate.sh"

## Preview the generated high-cardinality stack against statedb.
scale-preview: check-app scale-provider-install scale-generate
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(SCALE_STACK_REF); \
	pulumi preview --message "Scale simulator preview ($(SCALE_RESOURCES) resources)"

## Deploy the generated high-cardinality stack against statedb.
scale-up: check-app scale-provider-install scale-generate
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(SCALE_STACK_REF); \
	pulumi up --yes --skip-preview --parallel $(SCALE_PARALLEL) --message "Scale simulator deployment ($(SCALE_RESOURCES) resources)"

## Deploy a generated stack with one deterministic provider failure.
scale-fault-up: check-app scale-provider-install
	repo="$$(git rev-parse --show-toplevel)"; \
	SCALE_OUT="$$repo/workbench/.generated/pulumi/scale-simulator" \
	SCALE_RESOURCES=$(SCALE_RESOURCES) SCALE_DEPTH=$(SCALE_DEPTH) SCALE_FANOUT=$(SCALE_FANOUT) \
	SCALE_REVISION=$(SCALE_REVISION) SCALE_PAYLOAD_BYTES=$(SCALE_PAYLOAD_BYTES) \
	SCALE_DIFF_DELAY_MS=$(SCALE_DIFF_DELAY_MS) SCALE_CREATE_DELAY_MS=$(SCALE_CREATE_DELAY_MS) \
	SCALE_CREATE_JITTER_MS=$(SCALE_CREATE_JITTER_MS) \
	SCALE_UPDATE_DELAY_MS=$(SCALE_UPDATE_DELAY_MS) SCALE_DELETE_DELAY_MS=$(SCALE_DELETE_DELAY_MS) \
	SCALE_REPLACEMENT_KEY=$(SCALE_REPLACEMENT_KEY) SCALE_FAIL_INDEX=$(SCALE_FAULT_INDEX) \
	SCALE_FAIL_MODE=$(SCALE_FAULT_MODE) SCALE_FAIL_OPERATION=$(SCALE_FAULT_OPERATION) \
	SCALE_FAIL_SEED=$(SCALE_FAIL_SEED) \
	"$$repo/workbench/pulumi/projects/scale-simulator/generate.sh"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(SCALE_STACK_REF); \
	pulumi up --yes --skip-preview --parallel $(SCALE_PARALLEL) --message "Scale simulator fault injection ($(SCALE_RESOURCES) resources)"

## Destroy the generated scale-simulator stack.
scale-destroy: check-app scale-provider-install scale-generate
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF); \
	pulumi destroy --yes --skip-preview --message "Scale simulator destroy"

## Disable the mock OIDC provider row so the break-glass login is live (password: "password").
## Break-glass only works while zero providers are enabled; note the app re-enables the row on
## every restart (OidcProviderBootstrap upserts enabled=true from env), so re-run after restarts.
glass-on:
	docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE oidc_providers SET enabled = false, updated_at = now() WHERE name = '$(OIDC_PROVIDER)'"
	@echo "Break-glass is live: http://localhost:$(CONSOLE_PORT)/console/login — password: password"

## Re-enable the mock OIDC provider row (break-glass self-destructs again).
glass-off:
	docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE oidc_providers SET enabled = true, updated_at = now() WHERE name = '$(OIDC_PROVIDER)'"
	@echo "OIDC provider \"$(OIDC_PROVIDER)\" re-enabled; break-glass is gated off"

## Print curated database inspection queries for the current workbench DB.
sql:
	docker exec -i $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME) < "$$(git rev-parse --show-toplevel)/workbench/pulumi/queries.sql"

## Open an interactive psql shell for the workbench DB.
psql:
	docker exec -it $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME)

## Clear interrupted workbench updates and locks after a failed experimental run.
force-unlock:
	docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE stack_updates SET status = 'failed', ended_at = now(), updated_at = now(), message = COALESCE(message, 'workbench force-unlock') WHERE status = 'in-progress'" \
		-c "UPDATE stacks SET active_update_id = NULL, updated_at = now() WHERE active_update_id IN (SELECT id FROM stack_updates WHERE status IN ('failed', 'cancelled'))" \
		-c "DELETE FROM stack_locks"

## Delete all workbench infrastructure, its Postgres volume, and the local Pulumi home.
reset:
	-export DOCKER_CONFIG=$(IDP_DOCKER_CFG) DB_VOLUME=$(DB_VOLUME); \
	docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" down -v --remove-orphans
	-docker volume rm -f $(DB_VOLUME)
	rm -rf "$$(git rev-parse --show-toplevel)/workbench/.pulumi-home"
