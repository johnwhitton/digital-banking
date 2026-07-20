#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_check_prerequisites
printf '%s\n' 'Phase 7F prerequisites are satisfied; no install or pull was performed.'
