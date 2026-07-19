.PHONY: infra-up infra-down db-up idp-up idp-down console-seed app app-stop check-app login init preview up destroy fixtures-up fixtures-destroy churn-preview churn-up sql psql force-unlock reset glass-on glass-off

REPO_ROOT := ../..
IN_CONTAINER := $(REPO_ROOT)/bin/in-container

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

## deploy-simulator churn runs: per-step sleep and target stack.
CHURN_SECONDS   ?= 6
CHURN_STACK     ?= dev
CHURN_STACK_REF ?= $(ORG)/$(CHURN_STACK)

## Compose owns stable workbench infrastructure. The mock IdP is exposed only to the Bun dev server,
## which proxies /default/* on the existing browser-facing :5173 origin. Quarkus and the browser
## therefore use the same issuer without another VS Code forwarded port.
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

## Shell invocation that seeds the console admin into $(ORG) and the static workbench CLI token
## (the product mints CLI tokens through a console session; the workbench seeds one directly for
## non-interactive make targets). Shared by `app` (background) and `console-seed`. The script path
## is resolved inside the dev container.
SEED_CONSOLE = DB_CONTAINER=$(DB_CONTAINER) DB_USER=$(DB_USER) DB_NAME=$(DB_NAME) ORG=$(ORG) \
	OIDC_ISSUER=$(OIDC_ISSUER) ADMIN_SUB=$(ADMIN_SUB) TOKEN_HASH=$(TOKEN_HASH) \
	sh "$$(git rev-parse --show-toplevel)/workbench/pulumi/seed-console.sh"

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
	@$(IN_CONTAINER) sh -lc 'export DOCKER_CONFIG=$(IDP_DOCKER_CFG) \
		DB_IMAGE=$(DB_IMAGE) DB_VOLUME=$(DB_VOLUME) DB_PORT=$(DB_PORT) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) \
		IDP_UPSTREAM_PORT=$(IDP_UPSTREAM_PORT); \
		mkdir -p "$$DOCKER_CONFIG"; \
		for container in state-db-workbench-postgres state-db-workbench-idp; do \
			if docker inspect "$$container" >/dev/null 2>&1 && \
				[ "$$(docker inspect "$$container" --format "{{index .Config.Labels \"com.docker.compose.project\"}}")" != state-db-workbench ]; then \
				docker rm -f "$$container" >/dev/null; \
			fi; \
			done; \
		docker volume create $(DB_VOLUME) >/dev/null; \
		docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" up -d --wait --build; \
		for i in $$(seq 1 30); do curl -fsS http://host.docker.internal:$(IDP_UPSTREAM_PORT)/default/.well-known/openid-configuration >/dev/null && exit 0; sleep 1; done; \
		echo "Mock IdP did not become ready"; exit 1'
	@echo "Workbench infrastructure is ready (Postgres :$(DB_PORT), mock IdP behind :$(CONSOLE_PORT)/default)"

## Stop infrastructure while preserving the Postgres volume.
infra-down:
	-$(IN_CONTAINER) sh -lc 'export DOCKER_CONFIG=$(IDP_DOCKER_CFG) DB_VOLUME=$(DB_VOLUME); docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" down'

## Compatibility aliases for the previous granular startup commands.
db-up: infra-up
idp-up: infra-up

idp-down:
	-$(IN_CONTAINER) sh -lc 'export DOCKER_CONFIG=$(IDP_DOCKER_CFG); docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" stop idp'

## Seed workbench OIDC users as members of org $(ORG) so /api/user shows orgs.
## Idempotent; `app` runs this automatically in the background, but you can re-run it standalone.
console-seed: db-up
	$(IN_CONTAINER) sh -lc '$(SEED_CONSOLE)'

## Run the full console dev stack against the stable workbench Postgres: the mock IdP, Quarkus in dev
## mode (hot reload, :8080), and the bun console dev server (HMR, :$(CONSOLE_PORT)) proxying /api and
## /pulumi to Quarkus. Open the console at http://localhost:$(CONSOLE_PORT). The console OIDC provider
## is auto-provisioned from the STATE_DB_OIDC_BOOTSTRAP_* vars; sign in as "$(ADMIN_SUB)",
## "developer", or "viewer". Ctrl-C stops Quarkus and the console dev server (the IdP and DB stay up; `cmd=reset`
## tears everything down). The Pulumi CLI targets :8080 directly and is unaffected.
app: infra-up
	$(MAKE) app-stop
	$(IN_CONTAINER) bash -lc 'cd "$$(git rev-parse --show-toplevel)" || exit 1; \
		CONSOLE_PID=; SEED_PID=; APP_PID=; WATCHDOG_PID=; \
		cleanup() { \
			trap - EXIT INT TERM; \
			[ -n "$$WATCHDOG_PID" ] && kill $$WATCHDOG_PID 2>/dev/null || true; \
			kill $$CONSOLE_PID $$SEED_PID $$APP_PID 2>/dev/null || true; \
			sleep 1; \
			kill -KILL $$CONSOLE_PID $$SEED_PID $$APP_PID 2>/dev/null || true; \
			wait $$CONSOLE_PID $$SEED_PID $$APP_PID $$WATCHDOG_PID 2>/dev/null || true; \
		}; \
		trap cleanup EXIT INT TERM; \
		( cd modules/web && bun install >/dev/null 2>&1 && \
			exec env PORT=$(CONSOLE_PORT) OIDC_PROXY_TARGET=http://host.docker.internal:$(IDP_UPSTREAM_PORT) bun dev.ts ) & \
		CONSOLE_PID=$$!; \
		( $(SEED_CONSOLE) ) & \
		SEED_PID=$$!; \
		env \
			QUARKUS_DATASOURCE_DEVSERVICES_ENABLED=false \
			QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:$(DB_PORT)/$(DB_NAME) \
			QUARKUS_DATASOURCE_USERNAME=$(DB_USER) \
			QUARKUS_DATASOURCE_PASSWORD=$(DB_PASSWORD) \
			QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING=true \
			QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST=true \
			QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_PREFIX=true \
			STATE_DB_BOOTSTRAP_ORG_NAME=$(ORG) \
			STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME="Acme Workbench" \
			STATE_DB_OIDC_BOOTSTRAP_NAME=$(OIDC_PROVIDER) \
			STATE_DB_OIDC_BOOTSTRAP_ISSUER=$(OIDC_ISSUER) \
			STATE_DB_OIDC_BOOTSTRAP_CLIENT_ID=$(OIDC_CLIENT_ID) \
			STATE_DB_OIDC_BOOTSTRAP_CLIENT_SECRET=$(OIDC_CLIENT_SECRET) \
			STATE_DB_OIDC_DEFAULT_PROVIDER=$(OIDC_PROVIDER) \
			STATE_DB_SESSION_ENCRYPTION_SECRET=state-db-workbench-session-secret-32-bytes \
				STATE_DB_LOCAL_GLASS_PASSWORD_HASH='\''$(GLASS_PASSWORD_HASH)'\'' \
			STATE_DB_ADMIN_SUBJECTS=$(OIDC_ISSUER):$(ADMIN_SUB) \
			mvn -f modules/pom.xml -pl app -am -Dquarkus.test.continuous-testing=disabled quarkus:dev & \
		APP_PID=$$!; \
		RUNNER_PID=$$$$; \
		( while kill -0 $$RUNNER_PID 2>/dev/null; do sleep 1; done; \
			kill $$CONSOLE_PID $$SEED_PID $$APP_PID 2>/dev/null || true; \
			sleep 1; \
			kill -KILL $$CONSOLE_PID $$SEED_PID $$APP_PID 2>/dev/null || true ) & \
		WATCHDOG_PID=$$!; \
		wait -n $$CONSOLE_PID $$APP_PID; \
		STATUS=$$?; \
		cleanup; \
		exit $$STATUS'

## Stop stale foreground workbench processes inside the dev container. VS Code
## port forwarding follows live listeners, so orphaned dev processes keep
## :5173/:8080/:5005 busy even after the terminal that launched them is gone.
app-stop:
	-$(IN_CONTAINER) sh -lc 'pkill -TERM -f "[b]un dev.ts" || true; \
		pkill -TERM -f "[q]uarkus:dev" || true; \
		pkill -TERM -f "[s]tate-db-app-dev.jar" || true; \
		sleep 1; \
		pkill -KILL -f "[b]un dev.ts" || true; \
		pkill -KILL -f "[q]uarkus:dev" || true; \
		pkill -KILL -f "[s]tate-db-app-dev.jar" || true'

## Fail early with a useful message if the state-db app is not running.
check-app:
	@$(IN_CONTAINER) sh -lc 'curl -fs --max-time 2 -H "Authorization: token $(TOKEN)" "$(BACKEND_URL)/api/capabilities" >/dev/null || { \
		echo ""; \
		echo "state-db is not reachable at $(BACKEND_URL), or the workbench token was not accepted."; \
		echo "Start the workbench app in another terminal first:"; \
		echo "  make pulumi-workbench cmd=app"; \
		echo ""; \
		exit 1; \
	}'

## Log the local Pulumi CLI into the workbench backend.
login: check-app
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && mkdir -p ../.pulumi-home && PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT) pulumi login $(BACKEND_URL)'

## Create/select the workbench stack.
init: login
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi stack select $(STACK_REF) || pulumi stack init $(STACK_REF)'

## Run a real Pulumi preview against state-db.
preview: init
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi preview'

## Run a real Pulumi deployment against state-db.
up: init
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi up --yes --skip-preview'

## Destroy the workbench stack resources through Pulumi.
destroy: init
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && $(PULUMI_ENV); pulumi destroy --yes --skip-preview'

## Create or update the complete local-only fixture estate. The app must already be running.
fixtures-up: check-app
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && \
		export PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_CONFIG_PASSPHRASE=workbench PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
		ORG=$(ORG) BACKEND_URL=$(BACKEND_URL) TOKEN=$(TOKEN) ./bootstrap-fixtures.sh'

## Destroy resources in every fixture stack, preserving the empty stacks for inspection/reuse.
fixtures-destroy: check-app
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && \
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
		done < fixtures.txt'

## Kick off a long-running preview on the deploy-simulator fixture project. A fresh
## deployNonce makes every simulated step report a change, and the invoke-based checks
## each sleep CHURN_SECONDS during the preview itself, so the run stays in-progress
## while you watch it live in the console.
churn-preview: check-app
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi/projects/deploy-simulator" && \
		export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
			PULUMI_HOME="$$(git rev-parse --show-toplevel)/workbench/.pulumi-home" \
			PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
		pulumi login $(BACKEND_URL) >/dev/null; \
		pulumi stack select $(CHURN_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(CHURN_STACK_REF); \
		pulumi config set stepSeconds $(CHURN_SECONDS); \
		pulumi config set deployNonce "churn-$$(date +%s)"; \
		pulumi preview --message "Slow churn preview"'

## Deploy the simulator with a fresh nonce: every rollout step sleeps CHURN_SECONDS and
## logs its progress, so the update stays in-progress with visible resource churn.
churn-up: check-app
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi/projects/deploy-simulator" && \
		export PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_CONFIG_PASSPHRASE=workbench \
			PULUMI_HOME="$$(git rev-parse --show-toplevel)/workbench/.pulumi-home" \
			PULUMI_SKIP_UPDATE_CHECK=true PULUMI_SKIP_CONFIRMATIONS=true PULUMI_CONSOLE_DOMAIN=localhost:$(CONSOLE_PORT); \
		pulumi login $(BACKEND_URL) >/dev/null; \
		pulumi stack select $(CHURN_STACK_REF) >/dev/null 2>&1 || pulumi stack init $(CHURN_STACK_REF); \
		pulumi config set stepSeconds $(CHURN_SECONDS); \
		pulumi config set deployNonce "churn-$$(date +%s)"; \
		pulumi up --yes --skip-preview --message "Slow churn deployment"'

## Disable the mock OIDC provider row so the break-glass login is live (password: "password").
## Break-glass only works while zero providers are enabled; note the app re-enables the row on
## every restart (OidcProviderBootstrap upserts enabled=true from env), so re-run after restarts.
glass-on:
	$(IN_CONTAINER) sh -lc 'docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE oidc_providers SET enabled = false, updated_at = now() WHERE name = '\''$(OIDC_PROVIDER)'\''"'
	@echo "Break-glass is live: http://localhost:$(CONSOLE_PORT)/console/login — password: password"

## Re-enable the mock OIDC provider row (break-glass self-destructs again).
glass-off:
	$(IN_CONTAINER) sh -lc 'docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE oidc_providers SET enabled = true, updated_at = now() WHERE name = '\''$(OIDC_PROVIDER)'\''"'
	@echo "OIDC provider \"$(OIDC_PROVIDER)\" re-enabled; break-glass is gated off"

## Print curated database inspection queries for the current workbench DB.
sql:
	$(IN_CONTAINER) sh -lc 'docker exec -i $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME) < "$$(git rev-parse --show-toplevel)/workbench/pulumi/queries.sql"'

## Open an interactive psql shell for the workbench DB.
psql:
	$(IN_CONTAINER) sh -lc 'docker exec -it $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME)'

## Clear interrupted workbench updates and locks after a failed experimental run.
force-unlock:
	$(IN_CONTAINER) sh -lc 'docker exec -i $(DB_CONTAINER) psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(DB_NAME) \
		-c "UPDATE stack_updates SET status = '\''failed'\'', ended_at = now(), updated_at = now(), message = COALESCE(message, '\''workbench force-unlock'\'') WHERE status = '\''in-progress'\''" \
		-c "UPDATE stacks SET active_update_id = NULL, updated_at = now() WHERE active_update_id IN (SELECT id FROM stack_updates WHERE status IN ('\''failed'\'', '\''cancelled'\''))" \
		-c "DELETE FROM stack_locks"'

## Delete all workbench infrastructure, its Postgres volume, and the local Pulumi home.
reset:
	-$(IN_CONTAINER) sh -lc 'export DOCKER_CONFIG=$(IDP_DOCKER_CFG) DB_VOLUME=$(DB_VOLUME); docker compose -f "$$(git rev-parse --show-toplevel)/workbench/pulumi/compose.yml" down -v --remove-orphans'
	-$(IN_CONTAINER) sh -lc 'docker volume rm -f $(DB_VOLUME)'
	$(IN_CONTAINER) sh -lc 'rm -rf "$$(git rev-parse --show-toplevel)/workbench/.pulumi-home"'
