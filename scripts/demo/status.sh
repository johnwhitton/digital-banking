#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
demo_compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
if status=$(demo_status_json 2>/dev/null); then
    printf '%s\n' "$status" | jq '{network,chainId,blockNumber,contractAddress,bankBalancesCents,ledgerBalancesCents,operationalPositionsCents,tokenBalancesAtomic,confirmedEffects,latestAcquisition,latestRedemption,latestSettlement,payoutBeforeBurn}'
else
    printf '%s\n' "Demo status: stopped, starting, or degraded. Run wait-ready.sh or Compose logs for the named service." >&2
    exit 1
fi
