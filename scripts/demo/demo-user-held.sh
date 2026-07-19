#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime
"$DEMO_SCRIPT_DIR/wait-ready.sh" >/dev/null

run_id=$(demo_read_secret "$DEMO_RUNTIME_DIR/run-id")
work=$DEMO_OUTPUT_DIR/user-held-$run_id
mkdir -p "$work"
chmod 700 "$work"
initial=$work/initial.json
acquired=$work/acquired.json
final=$work/final.json
parent=$work/parent.json

demo_status_json > "$initial"
demo_assert_clean_initial_state "$initial"

request='{"amount":"100","currency":"USD","bankAccountReference":"synthetic-bank:USER_1_BANK_ACCOUNT","settlementNetwork":"ETHEREUM"}'
acquire_key=phase6d-user-held-acquire-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/acquisitions "$request" "$acquire_key")
acquisition_id=$(printf '%s' "$accepted" | jq -er '.workflowId') \
    || demo_die "acquisition acceptance response is malformed"
accepted=
demo_wait_parent "/v1/usdzelle/acquisitions/$acquisition_id" "$parent"
demo_status_json > "$acquired"
demo_assert_status_value "$acquired" '.bankBalancesCents.USER_1_BANK_ACCOUNT' 0 'post-acquisition USER_1 bank cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.RESERVE_CASH_ASSET' 10000 'post-acquisition reserve cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.USDZELLE_CIRCULATING_LIABILITY' 10000 'post-acquisition circulating liability cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.FIAT_RECEIVED_PENDING_MINT_LIABILITY' 0 'post-acquisition pending mint cents'
demo_assert_status_value "$acquired" '.tokenBalancesAtomic.userOne' 10000 'post-acquisition USER_1 token atomic units'
demo_assert_status_value "$acquired" '.tokenBalancesAtomic.totalSupply' 10000 'post-acquisition total supply'
demo_assert_status_value "$acquired" '.confirmedEffects.withdrawals' 1 'post-acquisition withdrawals'
demo_assert_status_value "$acquired" '.confirmedEffects.mints' 1 'post-acquisition mints'
demo_assert_status_value "$acquired" '.latestAcquisition.reconciliation' RECONCILED 'acquisition reconciliation'

replay=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/acquisitions "$request" "$acquire_key")
demo_assert_equal "$(printf '%s' "$replay" | jq -er '.workflowId')" "$acquisition_id" 'acquisition replay identity'
replay=
demo_status_json > "$work/acquisition-replay.json"
demo_assert_status_value "$work/acquisition-replay.json" '.confirmedEffects.withdrawals' 1 'replayed withdrawal count'
demo_assert_status_value "$work/acquisition-replay.json" '.confirmedEffects.mints' 1 'replayed mint count'

redeem_key=phase6d-user-held-redeem-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/redemptions "$request" "$redeem_key")
redemption_id=$(printf '%s' "$accepted" | jq -er '.workflowId') \
    || demo_die "redemption acceptance response is malformed"
accepted=
demo_wait_parent "/v1/usdzelle/redemptions/$redemption_id" "$parent"
demo_status_json > "$final"

demo_assert_status_value "$final" '.bankBalancesCents.USER_1_BANK_ACCOUNT' 10000 'final USER_1 bank cents'
for filter in \
    '.ledgerBalancesCents.RESERVE_CASH_ASSET' \
    '.ledgerBalancesCents.FIAT_RECEIVED_PENDING_MINT_LIABILITY' \
    '.ledgerBalancesCents.USDZELLE_CIRCULATING_LIABILITY' \
    '.ledgerBalancesCents.REDEMPTION_PAYABLE_LIABILITY' \
    '.operationalPositionsCents.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN' \
    '.tokenBalancesAtomic.userOne' '.tokenBalancesAtomic.admin' \
    '.tokenBalancesAtomic.totalSupply'; do
    demo_assert_status_value "$final" "$filter" 0 "final $filter"
done
demo_assert_status_value "$final" '.confirmedEffects.withdrawals' 1 'withdrawal count'
demo_assert_status_value "$final" '.confirmedEffects.mints' 1 'mint count'
demo_assert_status_value "$final" '.confirmedEffects.userTransfers' 0 'user transfer count'
demo_assert_status_value "$final" '.confirmedEffects.custodyTransfers' 1 'custody transfer count'
demo_assert_status_value "$final" '.confirmedEffects.payouts' 1 'payout count'
demo_assert_status_value "$final" '.confirmedEffects.burns' 1 'burn count'
demo_assert_status_value "$final" '.payoutBeforeBurn' true 'payout-before-burn order'
demo_assert_status_value "$final" '.latestRedemption.reconciliation' RECONCILED 'redemption reconciliation'

replay=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/redemptions "$request" "$redeem_key")
demo_assert_equal "$(printf '%s' "$replay" | jq -er '.workflowId')" "$redemption_id" 'redemption replay identity'
replay=
demo_status_json > "$work/replay.json"
demo_assert_status_value "$work/replay.json" '.confirmedEffects.payouts' 1 'replayed payout count'
demo_assert_status_value "$work/replay.json" '.confirmedEffects.burns' 1 'replayed burn count'

jq -n --arg runId "$run_id" --arg acquisitionId "$acquisition_id" \
    --arg redemptionId "$redemption_id" \
    '{demo:"user-held",runId:$runId,acquisitionId:$acquisitionId,redemptionId:$redemptionId,result:"RECONCILED",replay:"SAME_RESOURCE_NO_NEW_EFFECT"}' \
    > "$work/summary.json"
chmod 600 "$work"/*.json
printf 'Demo A passed: acquisition %s, redemption %s, exact zeroed final positions, RECONCILED. Redacted summary: %s\n' \
    "$acquisition_id" "$redemption_id" "$work/summary.json"
