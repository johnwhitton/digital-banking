#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
demo_compose stop
printf '%s\n' "Phase 6D containers stopped; named database/chain volumes and ignored runtime files are preserved."
