.PHONY: infra-up infra-down db-up console-seed app app-dev app-stop status logs oidc-smoke build-image image-ready check-app login init preview up destroy fixtures-up fixtures-destroy churn-preview churn-up scale-provider-test scale-provider-build scale-provider-install scale-generate scale-preview scale-up scale-fault-up scale-destroy sql psql force-unlock reset glass-on glass-off

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

## `cmd=app` runs this image. The local default is built from this checkout when missing;
## any other ref is pulled and never built.
LOCAL_IMAGE   := state-db:workbench
STATEDB_IMAGE ?= $(LOCAL_IMAGE)
## Anonymous pulls by default (see IDP_DOCKER_CFG); set to ~/.docker for a private registry.
PULL_DOCKER_CONFIG ?= $(IDP_DOCKER_CFG)

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

## Compose owns stable workbench infrastructure, plus the release-mode app under the `release`
## profile. The browser and the app reach the mock IdP at the same localhost:$(IDP_PORT) issuer.
CONSOLE_PORT  ?= 5173
IDP_PORT      ?= 58081
APP_CONTAINER := state-db-workbench-app
## Empty docker config dir: bypasses a credential helper that hangs or errors on anonymous pulls.
IDP_DOCKER_CFG ?= /tmp/state-db-workbench-dockercfg
## The release gate builds this IdP image; the workbench runs it and mounts the login page.
IDP_IMAGE  := $(shell sed -n 's/^FROM //p' $(CURDIR)/mock-oauth2.Dockerfile)
SHIM_IMAGE := alpine/socat:1.8.0.3@sha256:beb4a68d9e4fe6b0f21ea774a0fde6c31f580dde6368939ed70100c5385b015e
## Compose never pulls or builds (an empty DOCKER_CONFIG would also hide its plugin and context).
COMPOSE = export DB_IMAGE=$(DB_IMAGE) DB_VOLUME=$(DB_VOLUME) DB_PORT=$(DB_PORT) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		IDP_IMAGE=$(IDP_IMAGE) SHIM_IMAGE=$(SHIM_IMAGE) IDP_PORT=$(IDP_PORT) ORG=$(ORG) STATEDB_IMAGE=$(STATEDB_IMAGE); \
	docker compose -f "$(CURDIR)/compose.yml" --profile release
## Pull $(1) with $(2) as DOCKER_CONFIG, against the current context's daemon.
pull-image = mkdir -p $(IDP_DOCKER_CFG); \
	DOCKER_HOST="$$(docker context inspect -f '{{.Endpoints.docker.Host}}')" DOCKER_CONFIG=$(2) docker pull $(1)
pull-missing = docker image inspect $(1) >/dev/null 2>&1 || { $(call pull-image,$(1),$(IDP_DOCKER_CFG)); }

## Browser-facing console: the image serves its own on :8080; dev mode uses Bun on :$(CONSOLE_PORT).
CONSOLE_DOMAIN = $(if $(filter true,$(shell docker inspect -f '{{.State.Running}}' $(APP_CONTAINER) 2>/dev/null)),localhost:8080,localhost:$(CONSOLE_PORT))

OIDC_PROVIDER      ?= mock
OIDC_ISSUER        ?= http://localhost:$(IDP_PORT)/default
OIDC_CLIENT_ID     ?= console
OIDC_CLIENT_SECRET ?= console-secret
## Sign in to the mock IdP with this subject to be treated as an admin in /api/user.
ADMIN_SUB          ?= admin

## PULUMI_CONSOLE_DOMAIN points every CLI-printed permalink (e.g. "View Live: ...")
## at the console dev server instead of the CLI's built-in default (localhost:3000),
## which nothing in the workbench listens on.
PULUMI_ENV = export \
	PULUMI_ACCESS_TOKEN=$(TOKEN) \
	PULUMI_CONFIG_PASSPHRASE=workbench \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" \
	PULUMI_SKIP_UPDATE_CHECK=true \
	PULUMI_SKIP_CONFIRMATIONS=true \
	PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN)

## Start the stable Postgres and mock IdP services declared in compose.yml.
infra-up:
	@docker info >/dev/null 2>&1 || { echo "error: Docker is not reachable; the workbench needs host Docker (run outside any agent sandbox)."; exit 1; }
	@$(call pull-missing,$(DB_IMAGE)) && $(call pull-missing,$(IDP_IMAGE))
	@postgres_state="$$(docker inspect -f '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $(DB_CONTAINER) 2>/dev/null || true)"; \
	idp_running="$$(docker inspect -f '{{.State.Running}}' state-db-workbench-idp 2>/dev/null || true)"; \
	if [ "$$postgres_state" = "true healthy" ] && [ "$$idp_running" = "true" ] \
		&& curl -fsS $(OIDC_ISSUER)/.well-known/openid-configuration >/dev/null 2>&1; then \
		echo "Workbench infrastructure is already ready (Postgres :$(DB_PORT), mock IdP :$(IDP_PORT))"; \
		exit 0; \
	fi; \
	docker volume create $(DB_VOLUME) >/dev/null; \
	$(COMPOSE) up -d --wait postgres idp; \
	for i in $$(seq 1 30); do curl -fsS $(OIDC_ISSUER)/.well-known/openid-configuration >/dev/null && exit 0; sleep 1; done; \
	echo "Mock IdP did not become ready"; exit 1
	@echo "Workbench infrastructure is ready (Postgres :$(DB_PORT), mock IdP :$(IDP_PORT))"

## Stop infrastructure (and a release-mode app) while preserving the Postgres volume.
infra-down:
	-$(COMPOSE) down

## Compatibility alias for the previous granular startup command.
db-up: infra-up

## Seed workbench OIDC users as members of org $(ORG) so /api/user shows orgs.
## Idempotent; `app`/`app-dev` run this automatically in the background, but you can re-run it standalone.
console-seed: db-up
	DB_CONTAINER=$(DB_CONTAINER) DB_USER=$(DB_USER) DB_NAME=$(DB_NAME) ORG=$(ORG) \
		OIDC_ISSUER=$(OIDC_ISSUER) ADMIN_SUB=$(ADMIN_SUB) TOKEN_HASH=$(TOKEN_HASH) \
		sh "$$(git rev-parse --show-toplevel)/workbench/pulumi/seed-console.sh"

## Build (or rebuild) the local image from this checkout. Run this again after code changes —
## `cmd=app` only builds automatically when the image is missing entirely.
build-image:
	"$$(git rev-parse --show-toplevel)/bin/build-native-app-image" $(LOCAL_IMAGE)

## Make $(STATEDB_IMAGE) available: build the local default when missing, otherwise pull the ref.
image-ready:
	@if [ "$(STATEDB_IMAGE)" = "$(LOCAL_IMAGE)" ]; then \
		docker image inspect $(LOCAL_IMAGE) >/dev/null 2>&1 || { \
			echo "==> $(LOCAL_IMAGE) not built yet — building now (first run only, several minutes)"; \
			$(MAKE) build-image; \
		}; \
	else \
		echo "==> pulling $(STATEDB_IMAGE)"; \
		$(call pull-image,$(STATEDB_IMAGE),$(PULL_DOCKER_CONFIG)) || { \
			docker image inspect $(STATEDB_IMAGE) >/dev/null 2>&1 || exit 1; \
			echo "warning: pull failed; using the local copy of $(STATEDB_IMAGE)"; \
		}; \
	fi

## Run $(STATEDB_IMAGE) detached with its bundled console on :8080; returns once it is ready.
## Builds $(LOCAL_IMAGE) automatically the first time; `cmd=build-image` to refresh it.
app: image-ready
	@$(MAKE) app-stop >/dev/null 2>&1 || true
	@$(MAKE) infra-up
	@$(call pull-missing,$(SHIM_IMAGE))
	@echo "==> $(STATEDB_IMAGE) on :8080 (detached)"
	@$(COMPOSE) up -d --wait --force-recreate app idp-loopback || { \
		docker logs --tail 100 $(APP_CONTAINER) 2>&1; \
		echo "error: $(STATEDB_IMAGE) did not become ready"; exit 1; \
	}
	@$(MAKE) console-seed
	@echo "==> ready: console http://localhost:8080 (sign in as admin/developer/viewer), backend $(BACKEND_URL)"
	@echo "    logs: cmd=logs · status: cmd=status · stop: cmd=app-stop"

## Run the full stack in Quarkus dev mode (hot reload) in the foreground, console on :$(CONSOLE_PORT).
app-dev:
	@$(MAKE) app-stop >/dev/null 2>&1 || true
	ORG=$(ORG) CONSOLE_PORT=$(CONSOLE_PORT) IDP_PORT=$(IDP_PORT) OIDC_ISSUER=$(OIDC_ISSUER) \
		DB_PORT=$(DB_PORT) DB_IMAGE=$(DB_IMAGE) DB_VOLUME=$(DB_VOLUME) \
		DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		"$$(git rev-parse --show-toplevel)/workbench/pulumi/dev-host.sh"

## Report which mode is running and whether the backend answers. Exit status 0 only when it does.
status:
	@for c in $(DB_CONTAINER) state-db-workbench-idp $(APP_CONTAINER) state-db-workbench-idp-loopback; do \
		printf '%-34s %s\n' "$$c" "$$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $$c 2>/dev/null || echo absent)"; \
	done
	@printf '%-34s %s\n' "quarkus:dev (host)" "$$(pgrep -f '[q]uarkus:dev' >/dev/null && echo running || echo absent)"
	@printf '%-34s %s\n' "bun console (host)" "$$(pgrep -f '[b]un dev.ts' >/dev/null && echo running || echo absent)"
	@if curl -fs --max-time $(CHECK_TIMEOUT) -H "Authorization: token $(TOKEN)" "$(BACKEND_URL)/api/capabilities" >/dev/null; then \
		echo "backend $(BACKEND_URL): up · console http://$(CONSOLE_DOMAIN)"; \
	else \
		echo "backend $(BACKEND_URL): down"; exit 1; \
	fi

## Print the release-mode app's recent logs (dev mode logs go to the terminal running app-dev).
LINES ?= 200
logs:
	@docker logs --tail $(LINES) $(APP_CONTAINER) 2>&1 || echo "$(APP_CONTAINER) is not running; app-dev logs are in the terminal that runs it."

## Sign in to the console headlessly through the real OIDC code flow and check /api/user.
SUBJECT ?= $(ADMIN_SUB)
oidc-smoke:
	@CONSOLE_URL=http://$(CONSOLE_DOMAIN) OIDC_ISSUER=$(OIDC_ISSUER) SUBJECT=$(SUBJECT) ORG=$(ORG) \
		"$$(git rev-parse --show-toplevel)/workbench/pulumi/oidc-smoke.sh"

## Stop the workbench app in either mode. Forgetting a terminal (or an agent's background
## run) can keep :5173/:8080/:5005/:9000 busy after the run that started it is gone.
app-stop:
	-docker rm -f state-db-workbench-idp-loopback $(APP_CONTAINER) >/dev/null 2>&1 || true
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
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && mkdir -p ../.pulumi-home && PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN) pulumi login $(BACKEND_URL)

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
	export PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_CONFIG_PASSPHRASE=workbench PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
	ORG=$(ORG) BACKEND_URL=$(BACKEND_URL) TOKEN=$(TOKEN) ./bootstrap-fixtures.sh

## Destroy resources in every fixture stack, preserving the empty stacks for inspection/reuse.
fixtures-destroy: check-app
	cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true \
	PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
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
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
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
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
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
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(SCALE_STACK_REF); \
	pulumi preview --message "Scale simulator preview ($(SCALE_RESOURCES) resources)"

## Deploy the generated high-cardinality stack against statedb.
scale-up: check-app scale-provider-install scale-generate
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
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
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(SCALE_STACK_REF); \
	pulumi up --yes --skip-preview --parallel $(SCALE_PARALLEL) --message "Scale simulator fault injection ($(SCALE_RESOURCES) resources)"

## Destroy the generated scale-simulator stack.
scale-destroy: check-app scale-provider-install scale-generate
	repo="$$(git rev-parse --show-toplevel)"; \
	cd "$$repo/workbench/.generated/pulumi/scale-simulator"; \
	export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
		PULUMI_HOME="$$repo/workbench/.pulumi-home" \
		PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=$(CONSOLE_DOMAIN); \
	pulumi login $(BACKEND_URL) >/dev/null; \
	pulumi stack select $(SCALE_STACK_REF); \
	pulumi destroy --yes --skip-preview --message "Scale simulator destroy"

## Disable the mock OIDC provider row so the break-glass login is live (password: "password").
## Break-glass only works while zero providers are enabled; note the app re-enables the row on
## every restart (OidcProviderBootstrap upserts enabled=true from env), so re-run after restarts.
glass-on:
	docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE oidc_providers SET enabled = false, updated_at = now() WHERE name = '$(OIDC_PROVIDER)'"
	@echo "Break-glass is live: http://$(CONSOLE_DOMAIN)/console/login — password: password"

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
	-$(COMPOSE) down -v --remove-orphans
	-docker volume rm -f $(DB_VOLUME)
	rm -rf "$$(git rev-parse --show-toplevel)/workbench/.pulumi-home"
