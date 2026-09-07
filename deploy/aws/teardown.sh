#!/usr/bin/env bash
# Guided deletion for the test stack created by deploy.sh.
# The operation cannot be cancelled after it starts. CloudFormation snapshots RDS before deletion.
set -euo pipefail

cd "$(dirname "$0")/../.."

usage() {
  cat <<'EOF'
usage: deploy/aws/teardown.sh

Deletes the state-db CloudFormation test stack and waits for completion.

Environment:
  AWS_PROFILE                 AWS CLI profile; the normal AWS variables also work
  AWS_REGION                  stack region (or AWS_DEFAULT_REGION/profile region)
  STATEDB_STACK_NAME          stack name (default: statedb-test)
  STATEDB_AUTO_APPROVE=true   skip the final Enter prompt

The RDS instance has DeletionPolicy: Snapshot. CloudFormation deletes the live database but keeps
the final snapshot, which continues to incur snapshot-storage charges until separately deleted.
EOF
}

fail() {
  echo "teardown: $1" >&2
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
[[ $stack_name =~ ^[A-Za-z][A-Za-z0-9-]{0,127}$ ]] || fail "invalid stack name: $stack_name"

identity=$(aws_cli sts get-caller-identity --query '[Account,Arn]' --output text)
read -r account principal <<<"$identity"
[[ -n $account && -n $principal ]] || fail "AWS STS returned an incomplete caller identity"

if ! stack_status=$(aws_cli cloudformation describe-stacks --stack-name "$stack_name" \
  --query 'Stacks[0].StackStatus' --output text 2>/dev/null); then
  fail "stack '$stack_name' does not exist in account $account, region $region"
fi

db_identifier=$(aws_cli cloudformation list-stack-resources --stack-name "$stack_name" \
  --query "StackResourceSummaries[?LogicalResourceId=='Database'].PhysicalResourceId | [0]" --output text)

echo
echo "AWS target"
echo "  account:   $account"
echo "  principal: $principal"
echo "  region:    $region"
echo "  stack:     $stack_name"
echo "  status:    $stack_status"
echo
echo "Resources CloudFormation will process:"
aws_cli cloudformation list-stack-resources --stack-name "$stack_name" \
  --query 'StackResourceSummaries[].{LogicalId:LogicalResourceId,Type:ResourceType,Status:ResourceStatus}' \
  --output table
echo
echo "WARNING: stack deletion cannot be cancelled. The application, load balancer, networking created"
echo "by the stack, secrets, and live RDS instance will be deleted. CloudFormation will first create an"
echo "RDS snapshot. That snapshot survives stack deletion and keeps incurring storage charges."
pause "Delete '$stack_name' from account $account in $region?"

aws_cli cloudformation delete-stack --stack-name "$stack_name"
echo "Deletion started. Waiting for CloudFormation and the final RDS snapshot..."
if ! aws_cli cloudformation wait stack-delete-complete --stack-name "$stack_name"; then
  echo "Recent deletion events:" >&2
  aws_cli cloudformation describe-stack-events --stack-name "$stack_name" \
    --query 'StackEvents[?ResourceStatusReason!=null].[Timestamp,LogicalResourceId,ResourceStatus,ResourceStatusReason] | [0:12]' \
    --output table >&2 || true
  fail "stack deletion did not complete"
fi

echo
echo "Stack deletion complete. Live stack resources should no longer incur charges."
if [[ -n $db_identifier && $db_identifier != None ]]; then
  echo "RDS snapshots remaining for $db_identifier:"
  aws_cli rds describe-db-snapshots --db-instance-identifier "$db_identifier" \
    --query 'reverse(sort_by(DBSnapshots,&SnapshotCreateTime))[].{Snapshot:DBSnapshotIdentifier,Status:Status,GiB:AllocatedStorage,Created:SnapshotCreateTime}' \
    --output table || echo "Unable to list snapshots; check the RDS console manually." >&2
fi
echo "Delete the final snapshot separately after you are certain its data is no longer needed."
