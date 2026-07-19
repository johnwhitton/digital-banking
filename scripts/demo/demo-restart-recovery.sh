#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
"$DEMO_SCRIPT_DIR/wait-ready.sh" >/dev/null

run_id=$(demo_read_secret "$DEMO_RUNTIME_DIR/run-id")
work=$DEMO_OUTPUT_DIR/restart-$run_id
mkdir -p "$work"
chmod 700 "$work"
demo_status_json > "$work/initial.json"
demo_assert_clean_initial_state "$work/initial.json"

# Recreate only the control plane with its existing worker seam disabled so the
# accepted parent is deterministically durable and non-terminal at shutdown.
demo_compose stop control-plane >/dev/null
export DEMO_WORKER_ENABLED=false
demo_compose up -d --no-deps --force-recreate control-plane >/dev/null
"$DEMO_SCRIPT_DIR/wait-ready.sh" >/dev/null

request='{"amount":"100","currency":"USD","bankAccountReference":"synthetic-bank:USER_1_BANK_ACCOUNT","settlementNetwork":"ETHEREUM"}'
key=phase6d-restart-acquire-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" /v1/usdzelle/acquisitions "$request" "$key")
workflow_id=$(printf '%s' "$accepted" | jq -er '.workflowId') \
    || demo_die "restart acceptance response is malformed"
demo_assert_equal "$(printf '%s' "$accepted" | jq -er '.status')" ACCEPTED \
    'pre-restart accepted workflow status'
accepted=
demo_curl GET "$DEMO_RUNTIME_DIR/sender-token" \
    "/v1/usdzelle/acquisitions/$workflow_id" > "$work/pre-restart-parent.json"
demo_assert_status_value "$work/pre-restart-parent.json" '.status' ACCEPTED \
    'pre-restart durable workflow status'
demo_status_json > "$work/pre-restart-status.json"
demo_assert_status_value "$work/pre-restart-status.json" '.latestAcquisition.id' \
    "$workflow_id" 'pre-restart workflow identity'
demo_assert_status_value "$work/pre-restart-status.json" '.latestAcquisition.status' \
    ACCEPTED 'pre-restart aggregate workflow status'
demo_assert_status_value "$work/pre-restart-status.json" '.confirmedEffects.withdrawals' \
    0 'pre-restart withdrawal count'
demo_assert_status_value "$work/pre-restart-status.json" '.confirmedEffects.mints' \
    0 'pre-restart mint count'

demo_compose stop control-plane >/dev/null
demo_assert_equal "$(demo_compose ps --status running --services postgres)" postgres 'persistent PostgreSQL service'
demo_assert_equal "$(demo_compose ps --status running --services anvil)" anvil 'persistent Anvil service'
export DEMO_WORKER_ENABLED=true
demo_compose up -d --no-deps --force-recreate control-plane >/dev/null
"$DEMO_SCRIPT_DIR/wait-ready.sh" >/dev/null
demo_wait_parent "/v1/usdzelle/acquisitions/$workflow_id" "$work/parent.json"
demo_status_json > "$work/final.json"
demo_assert_status_value "$work/final.json" '.confirmedEffects.withdrawals' 1 'restart withdrawal count'
demo_assert_status_value "$work/final.json" '.confirmedEffects.mints' 1 'restart mint count'
demo_assert_status_value "$work/final.json" '.latestAcquisition.id' "$workflow_id" 'recovered workflow identity'
demo_assert_status_value "$work/final.json" '.latestAcquisition.status' COMPLETED 'recovered workflow status'
demo_assert_status_value "$work/final.json" '.latestAcquisition.reconciliation' RECONCILED 'recovered workflow reconciliation'

replay=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" /v1/usdzelle/acquisitions "$request" "$key")
demo_assert_equal "$(printf '%s' "$replay" | jq -er '.workflowId')" "$workflow_id" 'restart replay identity'
replay=
demo_status_json > "$work/replay.json"
demo_assert_status_value "$work/replay.json" '.confirmedEffects.withdrawals' 1 'restart replay withdrawal count'
demo_assert_status_value "$work/replay.json" '.confirmedEffects.mints' 1 'restart replay mint count'

jq -n --arg runId "$run_id" --arg workflowId "$workflow_id" \
    '{demo:"restart-recovery",runId:$runId,workflowId:$workflowId,persistentServices:["postgres","anvil"],result:"RECONCILED",duplicateConfirmedEffects:false}' \
    > "$work/summary.json"
chmod 600 "$work"/*.json
printf 'Restart recovery passed: workflow %s resumed with one withdrawal, one mint, and no duplicate confirmed effect. Redacted summary: %s\n' \
    "$workflow_id" "$work/summary.json"
