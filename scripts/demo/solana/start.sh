#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
"$DEMO_SCRIPT_DIR/bootstrap.sh"
"$DEMO_SCRIPT_DIR/wait-ready.sh"
printf 'Ready: API %s, private Solana RPC %s, run %s\n' \
    "$DEMO_API_URL" "$DEMO_RPC_URL" "$(demo_read_secret "$DEMO_RUNTIME_DIR/run-id")"
printf 'Next: Demo A %s/demo-user-held.sh; reset; Demo B %s/demo-settlement-only.sh\n' \
    "$DEMO_SCRIPT_DIR" "$DEMO_SCRIPT_DIR"
