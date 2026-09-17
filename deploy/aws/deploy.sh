#!/usr/bin/env bash
# Guided CloudFormation deployment for a state-db test stack.
#
# The defaults deploy the ARM64 candidate built from commit d186ef2 into a new, minimal VPC.
# Run with --help to see every override. This script creates a change set, displays it, and waits
# for an explicit Enter press before AWS starts creating billable resources.
set -euo pipefail

cd "$(dirname "$0")/../.."

readonly DEFAULT_IMAGE='ghcr.io/state-db/statedb-candidates@sha256:57eed59a4eb1c8006823290d4310579a33e5733dc35be6b02a7009f2486eb2e9'
readonly DEFAULT_BOOTSTRAP_IMAGE='public.ecr.aws/docker/library/postgres:18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280'
readonly SOURCE_IMAGE='ghcr.io/state-db/statedb:__VERSION__@sha256:__IMAGE_DIGEST__'

usage() {
  cat <<'EOF'
usage: deploy/aws/deploy.sh

Creates or updates a state-db CloudFormation stack through a reviewable change set.

Common environment overrides:
  AWS_PROFILE                         AWS CLI profile; the normal AWS variables also work
  AWS_REGION                          deployment region (or AWS_DEFAULT_REGION/profile region)
  STATEDB_STACK_NAME                  stack name (default: statedb-test)
  STATEDB_IMAGE_REFERENCE             immutable application image
  STATEDB_BOOTSTRAP_IMAGE_REFERENCE   immutable multi-platform PostgreSQL client image
  STATEDB_CPU_ARCHITECTURE            ARM64 or X86_64 (default: ARM64)
  STATEDB_ORGANIZATION_NAME           initial Pulumi organization (default: default)
  STATEDB_ADMIN_SUBJECTS              comma-separated exact OIDC <issuer>:<subject> identities
  STATEDB_DB_INSTANCE_CLASS           RDS class (default: db.t4g.small)
  STATEDB_DB_ALLOCATED_STORAGE        initial gp3 GiB (default: 80)
  STATEDB_DB_BACKUP_RETENTION_DAYS    automated backup days (default: 1; 0 disables)
  STATEDB_AUTO_APPROVE=true           skip Enter prompts (intended for controlled automation)

Network defaults create 10.42.0.0/16 with two public application subnets and two isolated DB
subnets. Override STATEDB_VPC_CIDR, STATEDB_APP_SUBNET_A_CIDR,
STATEDB_APP_SUBNET_B_CIDR, STATEDB_DB_SUBNET_A_CIDR, or STATEDB_DB_SUBNET_B_CIDR.

To use an existing network, set STATEDB_NETWORK_MODE=UseExisting plus:
  STATEDB_EXISTING_VPC_ID
  STATEDB_EXISTING_APP_SUBNET_A_ID
  STATEDB_EXISTING_APP_SUBNET_B_ID
  STATEDB_EXISTING_DB_SUBNET_A_ID
  STATEDB_EXISTING_DB_SUBNET_B_ID

Also set STATEDB_APPLICATION_SUBNET_TYPE=Public or Private. Private application subnets must
already have NAT or the required VPC endpoints. The stack never changes an existing network.
EOF
}

fail() {
  echo "deploy: $1" >&2
  exit 1
}

pause() {
  if [[ ${STATEDB_AUTO_APPROVE:-false} == true ]]; then
    echo "auto-approved: $1"
    return
  fi
  [[ -t 0 ]] || fail "interactive input is unavailable; rerun in a terminal or set STATEDB_AUTO_APPROVE=true"
  read -r -p "$1 Press Enter to continue, or Ctrl-C to stop. " _
}

command -v aws >/dev/null || fail "AWS CLI v2 is required"
command -v curl >/dev/null || fail "curl is required for the public-image preflight"
[[ ${1:-} != --help && ${1:-} != -h ]] || { usage; exit 0; }
[[ $# -eq 0 ]] || { usage >&2; exit 2; }

region=${AWS_REGION:-${AWS_DEFAULT_REGION:-}}
if [[ -z $region ]]; then
  region=$(aws configure get region 2>/dev/null || true)
fi
[[ -n $region ]] || fail "set AWS_REGION or configure a default AWS CLI region"
readonly region
readonly aws_scope=(--region "$region" --no-cli-pager)
aws_cli() { aws "${aws_scope[@]}" "$@"; }

stack_name=${STATEDB_STACK_NAME:-statedb-test}
image_reference=${STATEDB_IMAGE_REFERENCE:-$DEFAULT_IMAGE}
bootstrap_image_reference=${STATEDB_BOOTSTRAP_IMAGE_REFERENCE:-$DEFAULT_BOOTSTRAP_IMAGE}
cpu_architecture=${STATEDB_CPU_ARCHITECTURE:-ARM64}
organization_name=${STATEDB_ORGANIZATION_NAME:-default}
admin_subjects=${STATEDB_ADMIN_SUBJECTS:-}
db_instance_class=${STATEDB_DB_INSTANCE_CLASS:-db.t4g.small}
db_storage=${STATEDB_DB_ALLOCATED_STORAGE:-80}
db_backup_retention_days=${STATEDB_DB_BACKUP_RETENTION_DAYS:-1}
network_mode=${STATEDB_NETWORK_MODE:-CreateNew}
application_subnet_type=${STATEDB_APPLICATION_SUBNET_TYPE:-Public}

[[ $stack_name =~ ^[A-Za-z][A-Za-z0-9-]{0,127}$ ]] || fail "invalid stack name: $stack_name"
[[ $image_reference =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] ||
  fail "STATEDB_IMAGE_REFERENCE must be an immutable repository@sha256:<digest> reference"
[[ $bootstrap_image_reference =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] ||
  fail "STATEDB_BOOTSTRAP_IMAGE_REFERENCE must be an immutable repository@sha256:<digest> reference"
case "$cpu_architecture" in ARM64|X86_64) ;; *) fail "CPU architecture must be ARM64 or X86_64" ;; esac
case "$network_mode" in CreateNew|UseExisting) ;; *) fail "network mode must be CreateNew or UseExisting" ;; esac
case "$application_subnet_type" in Public|Private) ;; *) fail "application subnet type must be Public or Private" ;; esac
[[ $db_backup_retention_days =~ ^[0-9]+$ ]] && (( db_backup_retention_days <= 35 )) ||
  fail "STATEDB_DB_BACKUP_RETENTION_DAYS must be an integer from 0 through 35"
[[ $network_mode != CreateNew || $application_subnet_type == Public ]] ||
  fail "CreateNew supports Public application subnets; use an existing network for private tasks"

identity=$(aws_cli sts get-caller-identity --query '[Account,Arn]' --output text)
read -r account principal <<<"$identity"
[[ -n $account && -n $principal ]] || fail "AWS STS returned an incomplete caller identity"

parameters=(
  "ParameterKey=OrganizationName,ParameterValue=$organization_name"
  "ParameterKey=DbInstanceClass,ParameterValue=$db_instance_class"
  "ParameterKey=DbAllocatedStorage,ParameterValue=$db_storage"
  "ParameterKey=DbBackupRetentionDays,ParameterValue=$db_backup_retention_days"
  "ParameterKey=ImageReference,ParameterValue=$image_reference"
  "ParameterKey=BootstrapImageReference,ParameterValue=$bootstrap_image_reference"
  "ParameterKey=CpuArchitecture,ParameterValue=$cpu_architecture"
  "ParameterKey=NetworkMode,ParameterValue=$network_mode"
  "ParameterKey=ApplicationSubnetType,ParameterValue=$application_subnet_type"
)

if [[ $network_mode == CreateNew ]]; then
  parameters+=(
    "ParameterKey=VpcCidr,ParameterValue=${STATEDB_VPC_CIDR:-10.42.0.0/16}"
    "ParameterKey=ApplicationSubnetACidr,ParameterValue=${STATEDB_APP_SUBNET_A_CIDR:-10.42.0.0/20}"
    "ParameterKey=ApplicationSubnetBCidr,ParameterValue=${STATEDB_APP_SUBNET_B_CIDR:-10.42.16.0/20}"
    "ParameterKey=DatabaseSubnetACidr,ParameterValue=${STATEDB_DB_SUBNET_A_CIDR:-10.42.32.0/20}"
    "ParameterKey=DatabaseSubnetBCidr,ParameterValue=${STATEDB_DB_SUBNET_B_CIDR:-10.42.48.0/20}"
  )
else
  existing_vpc=${STATEDB_EXISTING_VPC_ID:-}
  app_subnet_a=${STATEDB_EXISTING_APP_SUBNET_A_ID:-}
  app_subnet_b=${STATEDB_EXISTING_APP_SUBNET_B_ID:-}
  db_subnet_a=${STATEDB_EXISTING_DB_SUBNET_A_ID:-}
  db_subnet_b=${STATEDB_EXISTING_DB_SUBNET_B_ID:-}
  [[ -n $existing_vpc && -n $app_subnet_a && -n $app_subnet_b && -n $db_subnet_a && -n $db_subnet_b ]] ||
    fail "UseExisting requires all five STATEDB_EXISTING_* identifiers shown by --help"
  parameters+=(
    "ParameterKey=ExistingVpcId,ParameterValue=$existing_vpc"
    "ParameterKey=ExistingApplicationSubnetAId,ParameterValue=$app_subnet_a"
    "ParameterKey=ExistingApplicationSubnetBId,ParameterValue=$app_subnet_b"
    "ParameterKey=ExistingDatabaseSubnetAId,ParameterValue=$db_subnet_a"
    "ParameterKey=ExistingDatabaseSubnetBId,ParameterValue=$db_subnet_b"
  )
fi

echo
echo "AWS target"
echo "  account:      $account"
echo "  principal:    $principal"
echo "  region:       $region"
echo "Deployment"
echo "  stack:        $stack_name"
echo "  image:        $image_reference"
echo "  bootstrap:    $bootstrap_image_reference"
echo "  architecture: $cpu_architecture"
echo "  replicas:     2 minimum, 4 maximum"
echo "  database:     PostgreSQL 17, $db_instance_class, ${db_storage} GiB gp3 baseline, Single-AZ"
echo "  backups:      $db_backup_retention_days day(s)"
echo "  network:      $network_mode ($application_subnet_type application subnets)"
echo
echo "This stack creates IAM roles, an RDS database, two always-running Fargate tasks, and an HTTPS"
echo "load balancer. These resources incur charges until the teardown script finishes."
pause "Review the account, region, image architecture, and cost-bearing resources."

if [[ $image_reference == ghcr.io/* ]]; then
  ghcr_name=${image_reference#ghcr.io/}
  ghcr_repository=${ghcr_name%@*}
  ghcr_digest=${image_reference##*@}
  echo
  echo "Checking that ECS can pull the GHCR image without your laptop's Docker credentials..."
  token_response=$(curl -fsS \
    "https://ghcr.io/token?service=ghcr.io&scope=repository:${ghcr_repository}:pull") ||
    fail "GHCR did not grant an anonymous pull token; make the candidate package public"
  ghcr_token=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' <<<"$token_response")
  [[ -n $ghcr_token ]] || fail "GHCR response contained no anonymous pull token; make the candidate package public"
  curl -fsSI \
    -H "Authorization: Bearer $ghcr_token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json' \
    "https://ghcr.io/v2/${ghcr_repository}/manifests/${ghcr_digest}" >/dev/null ||
    fail "the candidate digest is not anonymously pullable; make ghcr.io/${ghcr_repository} public"
  echo "Anonymous GHCR pull check passed."
fi

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/statedb-aws-deploy.XXXXXX")
trap 'rm -rf "$temp_dir"' EXIT
template="$temp_dir/cloudformation.yaml"
escaped_image=${image_reference//&/\\&}
escaped_image=${escaped_image//|/\\|}
sed "s|$SOURCE_IMAGE|$escaped_image|" deploy/aws/cloudformation.yaml >"$template"
grep -F "$image_reference" "$template" >/dev/null || fail "failed to stamp the candidate image"
! grep -E '__VERSION__|__IMAGE_DIGEST__' "$template" >/dev/null || fail "release placeholder remains in rendered template"

echo
echo "Validating the rendered template with CloudFormation..."
aws_cli cloudformation validate-template --template-body "file://$template" >/dev/null

if stack_status=$(aws_cli cloudformation describe-stacks --stack-name "$stack_name" \
  --query 'Stacks[0].StackStatus' --output text 2>/dev/null); then
  case "$stack_status" in
    ROLLBACK_COMPLETE)
      fail "stack $stack_name is ROLLBACK_COMPLETE; run deploy/aws/teardown.sh, then deploy again"
      ;;
    UPDATE_ROLLBACK_FAILED)
      echo
      echo "Stack $stack_name is UPDATE_ROLLBACK_FAILED."
      echo "CloudFormation must finish rolling back the failed update before a new change set can run."
      pause "Continue the failed update rollback, then deploy from the last stable stack state?"
      aws_cli cloudformation continue-update-rollback --stack-name "$stack_name"
      echo "Waiting for $stack_name to reach UPDATE_ROLLBACK_COMPLETE..."
      if ! aws_cli cloudformation wait stack-update-rollback-complete --stack-name "$stack_name"; then
        echo "Recent events with reasons:" >&2
        aws_cli cloudformation describe-stack-events --stack-name "$stack_name" \
          --query 'StackEvents[?ResourceStatusReason!=null].[Timestamp,LogicalResourceId,ResourceStatus,ResourceStatusReason] | [0:12]' \
          --output table >&2 || true
        fail "stack rollback did not reach UPDATE_ROLLBACK_COMPLETE"
      fi
      ;;
    *_IN_PROGRESS)
      fail "stack $stack_name is still $stack_status; wait for it to settle before deploying again"
      ;;
  esac
  change_type=UPDATE
  completion_waiter=stack-update-complete
else
  change_type=CREATE
  completion_waiter=stack-create-complete
fi

if [[ ${STATEDB_ADMIN_SUBJECTS+x} ]]; then
  # AWS CLI shorthand uses commas between structure fields, so preserve list commas by escaping
  # them before passing the complete allow-list as one ParameterValue.
  parameters+=("ParameterKey=AdminSubjects,ParameterValue=${admin_subjects//,/\\,}")
elif [[ $change_type == UPDATE ]]; then
  # An ordinary image or infrastructure update must not silently remove every OIDC administrator.
  parameters+=("ParameterKey=AdminSubjects,UsePreviousValue=true")
fi
change_set="guided-$(date -u +%Y%m%dT%H%M%SZ)-$$"

echo "Creating a $change_type change set. This does not execute it..."
change_set_id=$(aws_cli cloudformation create-change-set \
  --stack-name "$stack_name" \
  --change-set-name "$change_set" \
  --change-set-type "$change_type" \
  --description "Guided state-db deployment of $image_reference" \
  --template-body "file://$template" \
  --parameters "${parameters[@]}" \
  --capabilities CAPABILITY_IAM \
  --tags Key=Project,Value=state-db Key=Purpose,Value=deployment-test \
  --query Id --output text)

if ! aws_cli cloudformation wait change-set-create-complete --change-set-name "$change_set_id"; then
  reason=$(aws_cli cloudformation describe-change-set --change-set-name "$change_set_id" --query StatusReason --output text)
  if [[ $reason == *"didn't contain changes"* || $reason == *"No updates"* ]]; then
    echo "CloudFormation found no changes. Nothing was executed."
    exit 0
  fi
  fail "change set creation failed: $reason"
fi

echo
echo "CloudFormation change set"
aws_cli cloudformation describe-change-set \
  --change-set-name "$change_set_id" \
  --query 'Changes[].ResourceChange.{Action:Action,LogicalId:LogicalResourceId,Type:ResourceType,Replacement:Replacement}' \
  --output table
echo
echo "Execution uses normal CloudFormation rollback. Initial creation usually takes 15–25 minutes,"
echo "mostly because RDS must become available before the database bootstrap task runs."
pause "Execute this change set and begin creating or updating the stack?"

aws_cli cloudformation execute-change-set --change-set-name "$change_set_id"
echo "Waiting for $stack_name to finish..."
if ! aws_cli cloudformation wait "$completion_waiter" --stack-name "$stack_name"; then
  echo "Recent events with reasons:" >&2
  aws_cli cloudformation describe-stack-events --stack-name "$stack_name" \
    --query 'StackEvents[?ResourceStatusReason!=null].[Timestamp,LogicalResourceId,ResourceStatus,ResourceStatusReason] | [0:12]' \
    --output table >&2 || true
  fail "stack did not reach its expected completion state"
fi

echo
echo "Deployment complete. Stack outputs:"
aws_cli cloudformation describe-stacks --stack-name "$stack_name" \
  --query 'Stacks[0].Outputs[].{Name:OutputKey,Value:OutputValue}' --output table
console_url=$(aws_cli cloudformation describe-stacks --stack-name "$stack_name" \
  --query "Stacks[0].Outputs[?OutputKey=='ConsoleUrl'].OutputValue | [0]" --output text)
password_command=$(aws_cli cloudformation describe-stacks --stack-name "$stack_name" \
  --query "Stacks[0].Outputs[?OutputKey=='BreakGlassPasswordCommand'].OutputValue | [0]" --output text)
echo
echo "Console: $console_url"
# Print the command, not the password. The stack generated it; nothing needs it on a terminal
# or in shell history until someone actually signs in.
echo "Sign in as admin with the password from:"
echo "  $password_command"
echo "When testing is complete, run deploy/aws/teardown.sh with the same AWS profile, region, and stack name."
