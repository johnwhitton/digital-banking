#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

solana_require_safe_path "$SOLANA_RUNTIME_DIR"
solana_stop_validator
for metadata_file in "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"; do
    solana_require_safe_path "$metadata_file"
done
rm -f -- "$SOLANA_PID_FILE" "$SOLANA_IDENTITY_FILE" "$SOLANA_STARTING_FILE"
solana_write_state stopped
printf '%s\n' "Phase 7A native Solana validator is stopped."
