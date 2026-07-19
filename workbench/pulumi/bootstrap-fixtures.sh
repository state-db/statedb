#!/usr/bin/env sh
# Populate state-db through the real Pulumi CLI with deterministic, local-only fixtures.
#
# Newly created stacks are walked through a choreographed history so the console has a
# realistic variety of runs to render: creates, config-only updates, keeper-driven
# replacements, a refresh, a recorded-but-unapplied preview, an intentionally failed
# release-gate update with a retry, a targeted delete with a restore, and a full
# destroy/rebuild. Re-running the script converges existing stacks to the final
# revision instead of replaying the history.
set -eu

: "${ORG:?}" "${BACKEND_URL:?}" "${TOKEN:?}" "${PULUMI_HOME:?}" "${PULUMI_CONFIG_PASSPHRASE:?}"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES_FILE="$SCRIPT_DIR/fixtures.txt"
PROJECTS_DIR="$SCRIPT_DIR/projects"
GENERATED_BASE="$REPO_ROOT/workbench/.generated/pulumi"

export PULUMI_ACCESS_TOKEN="$TOKEN"
export PULUMI_SKIP_UPDATE_CHECK=true
export PULUMI_SKIP_CONFIRMATIONS=true

pulumi login "$BACKEND_URL" >/dev/null

# configure <revision> — set the standard config and tags on $stack_ref.
configure() {
  pulumi config set environment "$stack" --stack "$stack_ref"
  pulumi config set revision "$1" --stack "$stack_ref"
  case "$project" in
    deploy-simulator)
      pulumi config set stepSeconds 0 --stack "$stack_ref"
      pulumi config set deployNonce seed --stack "$stack_ref"
      ;;
    *)
      pulumi config set servicePassword "workbench-$project-$stack" --secret --stack "$stack_ref"
      ;;
  esac

  if [ "$project" = artifact-pipeline ]; then
    mkdir -p "$GENERATED_BASE/$project/$stack"
    pulumi config set generatedRoot "$GENERATED_BASE/$project/$stack" --stack "$stack_ref"
  fi
  if [ "$project" = application-fleet ]; then
    pulumi config set foundationStack "$ORG/shared-foundation/$stack" --stack "$stack_ref"
  fi

  pulumi stack tag set fixture true --stack "$stack_ref"
  pulumi stack tag set environment "$stack" --stack "$stack_ref"
  pulumi stack tag set owner "$owner" --stack "$stack_ref"
}

deploy() {
  echo "[fixtures] up $ORG/$project/$stack: $1"
  pulumi up --yes --skip-preview --stack "$stack_ref" --message "$1"
}

history_shared_foundation() {
  pulumi config set rotation 1 --stack "$stack_ref"
  configure 1
  deploy "Initial environment build"
  configure 2
  deploy "Refresh environment metadata"
  pulumi config set rotation 2 --stack "$stack_ref"
  configure 3
  deploy "Rotate environment id and database password"
  echo "[fixtures] refresh $ORG/$project/$stack"
  pulumi refresh --yes --skip-preview --stack "$stack_ref" --message "Scheduled drift check"
  # Record a preview of the next rotation without ever applying it.
  pulumi config set rotation 3 --stack "$stack_ref"
  echo "[fixtures] preview $ORG/$project/$stack (pending rotation, never applied)"
  pulumi preview --stack "$stack_ref" --message "Plan credential rotation"
  pulumi config set rotation 2 --stack "$stack_ref"
}

history_artifact_pipeline() {
  pulumi config set publishGate pass --stack "$stack_ref"
  configure 1
  deploy "Initial pipeline bootstrap"
  configure 2
  deploy "Publish refreshed artifact bundle"
  if [ "$stack" = nightly ]; then
    pulumi config set publishGate fail --stack "$stack_ref"
    configure 3
    echo "[fixtures] up $ORG/$project/$stack: Promote nightly through release gate (fails by design)"
    if pulumi up --yes --skip-preview --stack "$stack_ref" --message "Promote nightly through release gate"; then
      echo "[fixtures] expected the release gate to fail" >&2
      exit 1
    fi
    echo "[fixtures] release gate failed as intended"
    pulumi config set publishGate pass --stack "$stack_ref"
    configure 4
    deploy "Retry promotion after gate fix"
  else
    configure 3
    deploy "Rebuild bundle for release"
    echo "[fixtures] targeted destroy of checksum on $ORG/$project/$stack"
    pulumi destroy --yes --skip-preview --stack "$stack_ref" \
      --target "urn:pulumi:$stack::artifact-pipeline::random:index/randomUuid:RandomUuid::checksum" \
      --message "Prune stale checksum"
    configure 4
    deploy "Restore checksum after prune"
  fi
}

history_application_fleet() {
  pulumi config set rotation 1 --stack "$stack_ref"
  configure 1
  deploy "Initial fleet rollout"
  configure 2
  deploy "Fleet configuration refresh"
  pulumi config set rotation 2 --stack "$stack_ref"
  configure 3
  deploy "Recycle worker fleet"
  if [ "$stack" = dev ]; then
    echo "[fixtures] destroy $ORG/$project/$stack (rebuild drill)"
    pulumi destroy --yes --skip-preview --stack "$stack_ref" --message "Tear down dev fleet for rebuild drill"
    configure 3
    deploy "Rebuild dev fleet"
  fi
}

history_deploy_simulator() {
  configure 1
  deploy "Provision deploy simulator"
}

# Bring an existing stack to the same final revision the history above ends on.
converge() {
  case "$project" in
    shared-foundation) pulumi config set rotation 2 --stack "$stack_ref"; configure 3 ;;
    artifact-pipeline) pulumi config set publishGate pass --stack "$stack_ref"; configure 4 ;;
    application-fleet) pulumi config set rotation 2 --stack "$stack_ref"; configure 3 ;;
    deploy-simulator) configure 1 ;;
  esac
  deploy "Converge fixtures"
}

while IFS='|' read -r project stacks owner; do
  case "$project" in
    ''|'#'*) continue ;;
  esac

  cd "$PROJECTS_DIR/$project"
  for stack in $stacks; do
    stack_ref="$ORG/$stack"
    if pulumi stack select "$stack_ref" >/dev/null 2>&1; then
      echo "[fixtures] converging existing $ORG/$project/$stack"
      converge
    else
      echo "[fixtures] creating $ORG/$project/$stack"
      pulumi stack init "$stack_ref"
      case "$project" in
        shared-foundation) history_shared_foundation ;;
        artifact-pipeline) history_artifact_pipeline ;;
        application-fleet) history_application_fleet ;;
        deploy-simulator) history_deploy_simulator ;;
      esac
    fi
  done
done < "$FIXTURES_FILE"

echo "[fixtures] fixture estate is ready"
