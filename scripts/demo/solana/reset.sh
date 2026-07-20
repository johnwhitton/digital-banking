#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

[ "${1-}" = --yes ] || demo_die 'reset is destructive; rerun exactly with reset.sh --yes'
[ "$DEMO_PROJECT" = digital-banking-phase7f-solana ] || demo_die 'unexpected Compose project'
[ "$DEMO_RUNTIME_DIR" = "$DEMO_ROOT/.demo-runtime/solana" ] || demo_die 'unexpected runtime target'
demo_require_safe_path "$DEMO_RUNTIME_DIR"
[ ! -L "$DEMO_ROOT/.demo-runtime" ] || demo_die 'refusing symlinked .demo-runtime parent'

printf '%s\n' 'Removing only project digital-banking-phase7f-solana, its PostgreSQL volume/network, and ignored .demo-runtime/solana state. This cannot be recovered.'
if [ -d "$DEMO_RUNTIME_DIR" ]; then
    demo_stop_control_plane
    demo_validator "$DEMO_ROOT/scripts/solana/stop.sh"
fi
if [ -f "$DEMO_COMPOSE_ENV" ]; then
    demo_compose down --volumes --remove-orphans
else
    SOLANA_DEMO_RUNTIME_DIR=$DEMO_RUNTIME_DIR \
    SOLANA_DEMO_POSTGRES_PORT=$SOLANA_DEMO_POSTGRES_PORT \
        docker compose --project-name "$DEMO_PROJECT" \
        -f "$SOLANA_DEMO_COMPOSE_FILE" down --volumes --remove-orphans
fi
rm -rf -- "$DEMO_RUNTIME_DIR"
printf '%s\n' 'Phase 7F state was destroyed. Images, source, Git, Maven caches, .env.local-anvil, Ethereum state, default .solana-runtime, and global Solana configuration were not removed.'
