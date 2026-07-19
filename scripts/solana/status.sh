#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

[ "${1-}" = "" ] || [ "${1-}" = --json ] || solana_die "usage: status.sh [--json]"

state=not_started
if [ -e "$SOLANA_RUNTIME_DIR" ] || [ -L "$SOLANA_RUNTIME_DIR" ]; then
    solana_require_safe_path "$SOLANA_RUNTIME_DIR"
fi
if [ -f "$SOLANA_RUNTIME_DIR/state" ]; then
    solana_require_safe_path "$SOLANA_RUNTIME_DIR/state"
    state=$(sed -n '1p' "$SOLANA_RUNTIME_DIR/state")
fi

rpc_ready=false
if solana_validator_running && [ -x "$SOLANA_CLI" ] \
    && "$SOLANA_CLI" --url "$SOLANA_RPC_URL" cluster-version >/dev/null 2>&1; then
    rpc_ready=true
elif [ "$state" = healthy ]; then
    state=stale
elif [ "$state" = completed ] && [ ! -f "$SOLANA_RUNTIME_DIR/evidence/summary.json" ]; then
    state=stale
fi

jq -n \
    --arg state "$state" \
    --arg rpc "$SOLANA_RPC_URL" \
    --argjson rpcReady "$rpc_ready" \
    '{state:$state,rpc:$rpc,rpcReady:$rpcReady}'
