#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
demo_compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
demo_validator "$DEMO_ROOT/scripts/solana/status.sh"
if status=$(demo_status_json 2>/dev/null); then
    printf '%s\n' "$status" | jq '{network,networkIdentity,observationHeight,assetReference,chainId,blockNumber,contractAddress,bankBalancesCents,ledgerBalancesCents,operationalPositionsCents,tokenBalancesAtomic,confirmedEffects,latestAcquisition,latestRedemption,latestSettlement,payoutBeforeBurn}'
else
    printf '%s\n' 'Control-plane status is stopped, starting, or degraded; retained state was not modified.' >&2
    exit 1
fi
