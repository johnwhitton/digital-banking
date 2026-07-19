#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

[ "${1-}" = --yes ] || demo_die "reset is destructive; rerun exactly with reset.sh --yes"
[ "$DEMO_PROJECT" = digital-banking-phase6d ] || demo_die "unexpected Compose project"
[ "$DEMO_RUNTIME_DIR" = "$DEMO_ROOT/.demo-runtime" ] || demo_die "unresolved runtime target"
case "$DEMO_RUNTIME_DIR" in "$DEMO_ROOT"/*) ;; *) demo_die "runtime target escaped the repository" ;; esac

printf '%s\n' "Removing only project digital-banking-phase6d containers/network, volumes digital-banking-phase6d-postgres-data and digital-banking-phase6d-anvil-data, and ignored .demo-runtime state."
if [ -f "$DEMO_COMPOSE_ENV" ]; then
    demo_compose down --volumes --remove-orphans
else
    DEMO_UID=$(id -u) DEMO_GID=$(id -g) DEMO_RUNTIME_DIR=$DEMO_RUNTIME_DIR \
        docker compose --project-name "$DEMO_PROJECT" \
        -f "$DEMO_ROOT/compose.yaml" down --volumes --remove-orphans
fi
rm -rf -- "$DEMO_RUNTIME_DIR"
printf '%s\n' "Phase 6D workflow, database, and chain state was destroyed and cannot be recovered. Images, source, .env.local-anvil, Git data, and Maven caches were not removed."
