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
request='{"amount":"100","currency":"USD","bankAccountReference":"synthetic-bank:USER_1_BANK_ACCOUNT","settlementNetwork":"SOLANA"}'
acquire_key=phase7f-user-held-acquire-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/acquisitions "$request" "$acquire_key")
acquisition_id=$(printf '%s' "$accepted" | jq -er '.workflowId') \
    || demo_die 'acquisition acceptance response is malformed'
accepted=
demo_wait_parent "/v1/usdzelle/acquisitions/$acquisition_id" "$parent" 600
demo_status_json > "$acquired"

demo_assert_status_value "$acquired" '.bankBalancesCents.USER_1_BANK_ACCOUNT' 0 'hold USER_1 bank cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.RESERVE_CASH_ASSET' 10000 'hold reserve cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.USDZELLE_CIRCULATING_LIABILITY' 10000 'hold circulating liability cents'
demo_assert_status_value "$acquired" '.ledgerBalancesCents.FIAT_RECEIVED_PENDING_MINT_LIABILITY' 0 'hold pending mint cents'
demo_assert_status_value "$acquired" '.tokenBalancesAtomic.userOne' 10000 'hold USER_1 token atomic units'
demo_assert_status_value "$acquired" '.tokenBalancesAtomic.admin' 0 'hold ADMIN custody atomic units'
demo_assert_status_value "$acquired" '.tokenBalancesAtomic.totalSupply' 10000 'hold total supply'
demo_assert_status_value "$acquired" '.confirmedEffects.withdrawals' 1 'hold withdrawal count'
demo_assert_status_value "$acquired" '.confirmedEffects.mints' 1 'hold mint count'
demo_assert_status_value "$acquired" '.latestAcquisition.reconciliation' RECONCILED 'acquisition reconciliation'
printf '%s\n' 'Hold checkpoint: USER_1=10000, ADMIN=0, supply=10000, reserve/liability=10000, RECONCILED.'

replay=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/acquisitions "$request" "$acquire_key")
demo_assert_equal "$(printf '%s' "$replay" | jq -er '.workflowId')" "$acquisition_id" 'acquisition replay identity'
replay=
demo_status_json > "$work/acquisition-replay.json"
demo_assert_status_value "$work/acquisition-replay.json" '.confirmedEffects.withdrawals' 1 'replayed withdrawal count'
demo_assert_status_value "$work/acquisition-replay.json" '.confirmedEffects.mints' 1 'replayed mint count'
demo_assert_status_value "$work/acquisition-replay.json" '.tokenBalancesAtomic.totalSupply' 10000 'replayed supply'

redeem_key=phase7f-user-held-redeem-$run_id
accepted=$(demo_curl POST "$DEMO_RUNTIME_DIR/sender-token" \
    /v1/usdzelle/redemptions "$request" "$redeem_key")
redemption_id=$(printf '%s' "$accepted" | jq -er '.workflowId') \
    || demo_die 'redemption acceptance response is malformed'
accepted=
demo_wait_parent "/v1/usdzelle/redemptions/$redemption_id" "$parent" 600
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
for effect in withdrawals mints custodyTransfers payouts burns; do
    demo_assert_status_value "$work/replay.json" ".confirmedEffects.$effect" 1 "replayed $effect count"
done
demo_assert_status_value "$work/replay.json" '.tokenBalancesAtomic.totalSupply' 0 'replayed final supply'

jq -n --arg runId "$run_id" --arg acquisitionId "$acquisition_id" \
    --arg redemptionId "$redemption_id" \
    '{demo:"A-user-held",runId:$runId,amount:"USD 100.00",atomicUnits:"10000",acquisitionId:$acquisitionId,redemptionId:$redemptionId,hold:{userOne:"10000",admin:"0",supply:"10000"},final:{userOne:"0",admin:"0",supply:"0"},payoutBeforeBurn:true,result:"RECONCILED",replay:"SAME_RESOURCE_NO_NEW_EFFECT"}' \
    > "$work/summary.json"
chmod 600 "$work"/*.json
printf 'Demo A passed: acquisition %s, hold 10000, redemption %s, payout-before-burn, zero final supply/custody, RECONCILED. Summary: %s\n' \
    "$acquisition_id" "$redemption_id" "$work/summary.json"
