#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
demo_stop_control_plane
demo_validator "$DEMO_ROOT/scripts/solana/stop.sh"
demo_compose stop postgres >/dev/null
printf '%s\n' 'Phase 7F processes stopped; named PostgreSQL volume, validator ledger, configuration, and redacted output are preserved.'
