#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

[ "${1-}" = --yes ] || solana_die "reset is destructive; rerun exactly with reset.sh --yes"
[ "$SOLANA_RUNTIME_DIR" = "$SOLANA_ROOT/.solana-runtime" ] || solana_die "unexpected runtime target"

solana_require_safe_path "$SOLANA_RUNTIME_DIR"
solana_stop_validator
rm -rf -- "$SOLANA_RUNTIME_DIR"
printf '%s\n' "Removed only ignored Phase 7A .solana-runtime state. Repository-local tools, Ethereum state, images, caches, source, Git data, and .env.local-anvil were not removed."
