.PHONY: db-up app check-app login init preview up destroy sql psql force-unlock reset

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
BACKEND_URL  ?= http://localhost:8080/pulumi

PULUMI_ENV := export \
	PULUMI_ACCESS_TOKEN=$(TOKEN) \
	PULUMI_CONFIG_PASSPHRASE=workbench \
	PULUMI_HOME="$$(pwd)/../.pulumi-home" \
	PULUMI_SKIP_UPDATE_CHECK=true \
	PULUMI_SKIP_CONFIRMATIONS=true

## Start or reuse the stable Postgres container used by the Pulumi workbench.
db-up:
	@$(IN_CONTAINER) sh -lc 'if docker inspect $(DB_CONTAINER) >/dev/null 2>&1; then \
		docker start $(DB_CONTAINER) >/dev/null; \
	else \
		docker run --name $(DB_CONTAINER) \
			-e POSTGRES_DB=$(DB_NAME) \
			-e POSTGRES_USER=$(DB_USER) \
			-e POSTGRES_PASSWORD=$(DB_PASSWORD) \
			-p $(DB_PORT):5432 \
			-v $(DB_VOLUME):/var/lib/postgresql/data \
			-d $(DB_IMAGE) >/dev/null; \
	fi'
	@$(IN_CONTAINER) sh -lc 'for i in $$(seq 1 30); do docker exec $(DB_CONTAINER) pg_isready -U $(DB_USER) -d $(DB_NAME) >/dev/null 2>&1 && exit 0; sleep 1; done; echo "Postgres did not become ready"; exit 1'
	@echo "Workbench Postgres is ready on localhost:$(DB_PORT)"

## Run the app in dev mode against the stable workbench Postgres database.
app: db-up
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)" && env \
		QUARKUS_DATASOURCE_DEVSERVICES_ENABLED=false \
		QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:$(DB_PORT)/$(DB_NAME) \
		QUARKUS_DATASOURCE_USERNAME=$(DB_USER) \
		QUARKUS_DATASOURCE_PASSWORD=$(DB_PASSWORD) \
		STATE_DB_BOOTSTRAP_ORG_NAME=$(ORG) \
		STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME="Acme Workbench" \
		STATE_DB_BOOTSTRAP_ADMIN_EMAIL=admin@example.test \
		STATE_DB_BOOTSTRAP_ADMIN_DISPLAY_NAME="Workbench Admin" \
		STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH=$(TOKEN_HASH) \
		mvn -f modules/pom.xml -pl app -am -Dquarkus.test.continuous-testing=disabled quarkus:dev'

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
	$(IN_CONTAINER) sh -lc 'cd "$$(git rev-parse --show-toplevel)/workbench/pulumi" && mkdir -p ../.pulumi-home && PULUMI_ACCESS_TOKEN=$(TOKEN) PULUMI_HOME="$$(pwd)/../.pulumi-home" PULUMI_SKIP_UPDATE_CHECK=true pulumi login $(BACKEND_URL)'

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

## Delete the workbench DB container, volume, and local Pulumi home.
reset:
	-$(IN_CONTAINER) sh -lc 'docker rm -f $(DB_CONTAINER)'
	-$(IN_CONTAINER) sh -lc 'docker volume rm -f $(DB_VOLUME)'
	$(IN_CONTAINER) sh -lc 'rm -rf "$$(git rev-parse --show-toplevel)/workbench/.pulumi-home"'
