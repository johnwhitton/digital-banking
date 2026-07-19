#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

AGAVE_ARCHIVE=solana-release-aarch64-apple-darwin.tar.bz2
AGAVE_URL=https://github.com/anza-xyz/agave/releases/download/v4.1.2/$AGAVE_ARCHIVE
AGAVE_SHA256=51a44318e6fb8be0cfa69cdfdb3252f4c76a5eb2866740694e91de3d2fc5a75b
SPL_TOKEN_ARCHIVE=spl-token-cli-5.6.1.crate
SPL_TOKEN_URL=https://static.crates.io/crates/spl-token-cli/$SPL_TOKEN_ARCHIVE
SPL_TOKEN_SHA256=b6d6ae3d3c38f4b85eee2762fb1e8da27f789c08f0b22f3c4b77c0593afdffa3

provision_only=false
case "${1-}" in
    '') ;;
    --provision-only) provision_only=true ;;
    *) solana_die "usage: bootstrap.sh [--provision-only]" ;;
esac

verify_sha256() {
    expected=$1
    file=$2
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || solana_die "checksum mismatch for $(basename "$file")"
}

install_agave() {
    if [ -x "$SOLANA_CLI" ] && [ -x "$SOLANA_KEYGEN" ] && [ -x "$SOLANA_TEST_VALIDATOR" ] \
        && "$SOLANA_CLI" --version | grep -F "solana-cli $SOLANA_AGAVE_VERSION" >/dev/null; then
        return 0
    fi
    archive=$SOLANA_TOOLS_DIR/$AGAVE_ARCHIVE
    solana_require_safe_path "$archive"
    solana_require_safe_path "$archive.tmp"
    solana_require_safe_path "$SOLANA_TOOLS_DIR/agave"
    rm -rf -- "$SOLANA_TOOLS_DIR/agave"
    rm -f -- "$archive.tmp"
    curl --fail --location --show-error --output "$archive.tmp" "$AGAVE_URL"
    mv "$archive.tmp" "$archive"
    verify_sha256 "$AGAVE_SHA256" "$archive"
    solana_prepare_directory "$SOLANA_TOOLS_DIR/agave" 700
    tar -xjf "$archive" --strip-components=1 -C "$SOLANA_TOOLS_DIR/agave"
    "$SOLANA_CLI" --version | grep -F "solana-cli $SOLANA_AGAVE_VERSION" >/dev/null \
        || solana_die "unexpected Agave version"
}

install_spl_token() {
    if [ -x "$SOLANA_SPL_TOKEN" ] && "$SOLANA_SPL_TOKEN" --version | grep -F "spl-token-cli $SOLANA_SPL_TOKEN_VERSION" >/dev/null; then
        return 0
    fi
    archive=$SOLANA_TOOLS_DIR/$SPL_TOKEN_ARCHIVE
    source_dir=$SOLANA_TOOLS_DIR/src/spl-token-cli-$SOLANA_SPL_TOKEN_VERSION
    solana_require_safe_path "$archive"
    solana_require_safe_path "$archive.tmp"
    solana_require_safe_path "$SOLANA_TOOLS_DIR/spl-token"
    solana_require_safe_path "$source_dir"
    rm -rf -- "$SOLANA_TOOLS_DIR/spl-token" "$source_dir"
    rm -f -- "$archive.tmp"
    curl --fail --location --show-error --output "$archive.tmp" "$SPL_TOKEN_URL"
    mv "$archive.tmp" "$archive"
    verify_sha256 "$SPL_TOKEN_SHA256" "$archive"
    tar -xzf "$archive" -C "$SOLANA_TOOLS_DIR/src"
    CARGO_HOME=$SOLANA_TOOLS_DIR/cargo-home cargo install \
        --path "$source_dir" --locked --root "$SOLANA_TOOLS_DIR/spl-token"
    "$SOLANA_SPL_TOKEN" --version | grep -F "spl-token-cli $SOLANA_SPL_TOKEN_VERSION" >/dev/null \
        || solana_die "unexpected spl-token version"
}

solana_require_host_tools
umask 077
solana_prepare_directory "$SOLANA_TOOLS_DIR" 700
solana_prepare_directory "$SOLANA_TOOLS_DIR/src" 700
solana_prepare_directory "$SOLANA_TOOLS_DIR/cargo-home" 700
solana_prepare_directory "$SOLANA_RUNTIME_DIR" 700
solana_prepare_directory "$SOLANA_RUNTIME_DIR/keys" 700
solana_prepare_directory "$SOLANA_RUNTIME_DIR/evidence" 700

install_agave
install_spl_token
solana_require_native_tools

for role in fee-payer admin-mint-authority admin-redemption-owner user-1 user-2 usdzelle-mint negative-wrong-mint; do
    key=$(solana_key_path "$role")
    solana_require_safe_path "$key"
    if [ ! -f "$key" ]; then
        "$SOLANA_KEYGEN" new --silent --no-bip39-passphrase --outfile "$key" >/dev/null
    fi
    [ -f "$key" ] && [ ! -L "$key" ] || solana_die "expected a regular key file: $key"
    chmod 600 "$key"
    solana_require_mode "$key" 600
done

addresses_tmp=$SOLANA_RUNTIME_DIR/public-addresses.json.tmp
solana_require_safe_path "$addresses_tmp"
solana_require_safe_path "$addresses_tmp.next"
solana_require_safe_path "$SOLANA_RUNTIME_DIR/public-addresses.json"
rm -f -- "$addresses_tmp" "$addresses_tmp.next" "$SOLANA_RUNTIME_DIR/public-addresses.json"
jq -n '{}' > "$addresses_tmp"
for role in fee-payer admin-mint-authority admin-redemption-owner user-1 user-2 usdzelle-mint negative-wrong-mint; do
    address=$(solana_address "$role")
    jq --arg role "$role" --arg address "$address" '. + {($role): $address}' \
        "$addresses_tmp" > "$addresses_tmp.next"
    mv "$addresses_tmp.next" "$addresses_tmp"
done
mv "$addresses_tmp" "$SOLANA_RUNTIME_DIR/public-addresses.json"
chmod 600 "$SOLANA_RUNTIME_DIR/public-addresses.json"
solana_require_mode "$SOLANA_RUNTIME_DIR/public-addresses.json" 600

if [ "$provision_only" = true ]; then
    printf '%s\n' "Phase 7A native Solana tooling and local keys are provisioned."
    exit 0
fi

bootstrap_complete=false
bootstrap_cleanup() {
    if [ "$bootstrap_complete" = false ] && solana_validator_pid_alive; then
        if ! (solana_stop_validator); then
            printf '%s\n' "Phase 7A Solana: validator cleanup failed; ownership metadata was preserved for inspection." >&2
        fi
    fi
}
trap bootstrap_cleanup 0
trap 'exit 1' 1 2 15

solana_start_validator
solana_wait_ready
solana_record_identity
"$SOLANA_TEST_VALIDATOR" --version | grep -F "solana-test-validator $SOLANA_AGAVE_VERSION" >/dev/null
"$SOLANA_SPL_TOKEN" --version | grep -F "spl-token-cli $SOLANA_SPL_TOKEN_VERSION" >/dev/null
solana_write_state healthy
bootstrap_complete=true
printf '%s\n' "Phase 7A native Solana validator is healthy at $SOLANA_RPC_URL."
