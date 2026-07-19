#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
"$DEMO_SCRIPT_DIR/bootstrap.sh"
demo_compose up -d
"$DEMO_SCRIPT_DIR/wait-ready.sh"
printf 'Ready: API %s, Ethereum RPC %s, chain 31337, contract %s, run %s\n' \
    "$DEMO_API_URL" "$DEMO_RPC_URL" "$(demo_safe_contract_address)" \
    "$(demo_read_secret "$DEMO_RUNTIME_DIR/run-id")"
printf 'Next: %s/demo-user-held.sh or %s/demo-settlement-only.sh\n' \
    "$DEMO_SCRIPT_DIR" "$DEMO_SCRIPT_DIR"
