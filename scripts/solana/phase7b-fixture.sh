#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
. "$SCRIPT_DIR/lib.sh"

solana_require_native_tools
solana_validator_running || solana_die "the private validator is not running"

fee_key=$(solana_key_path fee-payer)
authority_key=$(solana_key_path admin-mint-authority)
mint_key=$(solana_key_path usdzelle-mint)
fee_address=$(solana_address fee-payer)
authority_address=$(solana_address admin-mint-authority)
mint_address=$(solana_address usdzelle-mint)
user_address=$(solana_address user-1)
user_2_address=$(solana_address user-2)
redemption_address=$(solana_address admin-redemption-owner)

existing_supply=$(
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
        supply "$mint_address" 2>/dev/null || true
)
[ -z "$existing_supply" ] || solana_die "Phase 7B fixture requires a fresh validator ledger"

"$SOLANA_CLI" --url "$SOLANA_RPC_URL" airdrop 10 "$fee_address" \
    --keypair "$fee_key" >/dev/null
"$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
    --fee-payer "$fee_key" --output json create-token --decimals 2 \
    --mint-authority "$authority_address" "$mint_key" >/dev/null

supply=$(
    "$SOLANA_SPL_TOKEN" --url "$SOLANA_RPC_URL" --program-id "$SOLANA_TOKEN_PROGRAM" \
        supply "$mint_address"
)
[ "$supply" = "0" ] || solana_die "Phase 7B fixture mint supply must begin at zero"

genesis=$(solana_rpc_request getGenesisHash | jq -er '.result')
jq -n --arg genesis "$genesis" --arg mint "$mint_address" \
    --arg user "$user_address" --arg fee "$fee_address" \
    --arg user2 "$user_2_address" --arg authority "$authority_address" \
    --arg redemption "$redemption_address" \
    '{clusterIdentity:$genesis,mintAddress:$mint,destinationOwner:$user,
      transferDestinationOwner:$user2,
      redemptionOwner:$redemption,
      feePayerPublicKey:$fee,mintAuthorityPublicKey:$authority,
      initialSupplyAtomic:"0"}'
