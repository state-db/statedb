#!/usr/bin/env bash
# Generate a large local-only Pulumi YAML program backed by the statedbsim provider.
set -euo pipefail

: "${SCALE_OUT:?}"

resources="${SCALE_RESOURCES:-250}"
depth="${SCALE_DEPTH:-8}"
fanout="${SCALE_FANOUT:-4}"
revision="${SCALE_REVISION:-1}"
payload_bytes="${SCALE_PAYLOAD_BYTES:-1024}"
diff_delay_ms="${SCALE_DIFF_DELAY_MS:-0}"
create_delay_ms="${SCALE_CREATE_DELAY_MS:-0}"
create_jitter_ms="${SCALE_CREATE_JITTER_MS:-0}"
update_delay_ms="${SCALE_UPDATE_DELAY_MS:-0}"
delete_delay_ms="${SCALE_DELETE_DELAY_MS:-0}"
replacement_key="${SCALE_REPLACEMENT_KEY:-stable}"
fail_index="${SCALE_FAIL_INDEX:--1}"
fail_mode="${SCALE_FAIL_MODE:-none}"
fail_operation="${SCALE_FAIL_OPERATION:-create}"
fail_every="${SCALE_FAIL_EVERY:-0}"
fail_seed="${SCALE_FAIL_SEED:-scale}"

if (( resources < 1 )); then
  echo "SCALE_RESOURCES must be >= 1" >&2
  exit 1
fi
if (( depth < 1 )); then
  echo "SCALE_DEPTH must be >= 1" >&2
  exit 1
fi
if (( fanout < 1 )); then
  echo "SCALE_FANOUT must be >= 1" >&2
  exit 1
fi

mkdir -p "$SCALE_OUT"
out="$SCALE_OUT/Pulumi.yaml"

{
  cat <<YAML
name: scale-simulator
runtime: yaml
description: Generated local-only high-cardinality stack for state-db workbench scale testing.
resources:
  statedbsim-provider:
    type: pulumi:providers:statedbsim
    defaultProvider: true
    options:
      version: 0.1.0
YAML

  for ((i = 0; i < resources; i++)); do
    printf -v name "node-%04d" "$i"
    level=$(( i * depth / resources ))
    if (( level >= depth )); then
      level=$(( depth - 1 ))
    fi

    resource_fail_mode="none"
    resource_fail_modulo=0
    resource_fail_key=""
    if (( fail_index == i )); then
      resource_fail_mode="$fail_mode"
      resource_fail_key="${fail_seed}-${name}-${fail_operation}"
    elif (( fail_every > 0 )); then
      resource_fail_mode="hash"
      resource_fail_modulo="$fail_every"
    fi

    cat <<YAML
  ${name}:
    type: statedbsim:index:SimulatedResource
    properties:
      sequence: ${i}
      level: ${level}
      revision: ${revision}
      replacementKey: "${replacement_key}"
      payloadBytes: ${payload_bytes}
      diffDelayMs: ${diff_delay_ms}
      createDelayMs: ${create_delay_ms}
      createJitterMs: ${create_jitter_ms}
      updateDelayMs: ${update_delay_ms}
      deleteDelayMs: ${delete_delay_ms}
      failureMode: "${resource_fail_mode}"
      failureOperation: "${fail_operation}"
      failureModulo: ${resource_fail_modulo}
      failureSeed: "${fail_seed}"
      failOnceKey: "${resource_fail_key}"
YAML

    parent=-1
    if (( i > 0 )); then
      previous_level=$(( level - 1 ))
      if (( previous_level < 0 )); then
        previous_level=0
      fi
      parent=$(( i - fanout ))
      if (( parent < 0 )); then
        parent=0
      fi
      while (( parent > 0 && (parent * depth / resources) > previous_level )); do
        parent=$(( parent - 1 ))
      done
    fi

    if (( parent >= 0 && parent != i )); then
      printf -v parent_name "node-%04d" "$parent"
      cat <<YAML
    options:
      parent: \${${parent_name}}
      dependsOn:
        - \${${parent_name}}
YAML
      if (( i > 1 && i % fanout == 0 )); then
        extra=$(( i - 2 ))
        if (( extra != parent )); then
          printf -v extra_name "node-%04d" "$extra"
          cat <<YAML
        - \${${extra_name}}
YAML
        fi
      fi
    fi
  done

  cat <<YAML
outputs:
  resourceCount: ${resources}
  depth: ${depth}
  fanout: ${fanout}
  revision: ${revision}
  payloadBytes: ${payload_bytes}
  sampleDigest: \${node-0000.digest}
YAML
} > "$out"

echo "[scale-simulator] wrote $out (${resources} resources, depth=${depth}, fanout=${fanout})"
