#!/usr/bin/env sh
# Populate state-db through the real Pulumi CLI with deterministic, local-only fixtures.
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

apply_revision() {
  project=$1
  stack=$2
  revision=$3
  stack_ref="$ORG/$stack"
  generated_root="$GENERATED_BASE/$project/$stack"

  mkdir -p "$generated_root"
  pulumi config set environment "$stack" --stack "$stack_ref"
  pulumi config set revision "$revision" --stack "$stack_ref"
  pulumi config set servicePassword "workbench-$project-$stack" --secret --stack "$stack_ref"

  if [ "$project" = artifact-pipeline ]; then
    pulumi config set generatedRoot "$generated_root" --stack "$stack_ref"
  fi
  if [ "$project" = application-fleet ]; then
    pulumi config set foundationStack "$ORG/shared-foundation/$stack" --stack "$stack_ref"
  fi

  pulumi stack tag set fixture true --stack "$stack_ref"
  pulumi stack tag set environment "$stack" --stack "$stack_ref"
  pulumi stack tag set owner "$owner" --stack "$stack_ref"

  echo "[fixtures] up $ORG/$project/$stack revision=$revision"
  pulumi up --yes --skip-preview --stack "$stack_ref"
}

while IFS='|' read -r project stacks owner; do
  case "$project" in
    ''|'#'*) continue ;;
  esac

  cd "$PROJECTS_DIR/$project"
  for stack in $stacks; do
    stack_ref="$ORG/$stack"
    created=false
    if ! pulumi stack select "$stack_ref" >/dev/null 2>&1; then
      echo "[fixtures] creating $ORG/$project/$stack"
      pulumi stack init "$stack_ref"
      created=true
    fi

    if [ "$created" = true ]; then
      apply_revision "$project" "$stack" 1
    fi
    apply_revision "$project" "$stack" 2
  done
done < "$FIXTURES_FILE"

echo "[fixtures] all fixture stacks are at revision 2"
