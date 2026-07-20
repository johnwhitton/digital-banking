#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd -P)

for script in prerequisites bootstrap start wait-ready status stop reset \
        demo-user-held demo-settlement-only demo-restart-recovery; do
    file=$SCRIPT_DIR/$script.sh
    [ -f "$file" ] || { printf '%s\n' "missing $file" >&2; exit 1; }
    sh -n "$file"
done

compose=$ROOT/compose.solana-demo.yaml
[ -f "$compose" ]
grep -F '127.0.0.1:${SOLANA_DEMO_POSTGRES_PORT:-15432}:5432' "$compose" >/dev/null
! grep -Eq '^[[:space:]]+(anvil|control-plane):' "$compose"
grep -F 'pull_policy: never' "$compose" >/dev/null

grep -F 'digital-banking-phase7f-solana' "$SCRIPT_DIR/lib.sh" >/dev/null
grep -F '.demo-runtime/solana' "$SCRIPT_DIR/lib.sh" >/dev/null
grep -F 'ps -ww -p' "$SCRIPT_DIR/lib.sh" >/dev/null
grep -F '127.0.0.1' "$SCRIPT_DIR/lib.sh" >/dev/null
grep -F 'demo_compose up -d --wait postgres' "$SCRIPT_DIR/bootstrap.sh" >/dev/null
grep -F ".state == \"healthy\"" "$SCRIPT_DIR/wait-ready.sh" >/dev/null
grep -F ".state == \"healthy\"" "$SCRIPT_DIR/demo-restart-recovery.sh" >/dev/null

if LOCAL_SOLANA_DEMO_API_URL=https://example.invalid \
        sh -c '. "$1"' "$SCRIPT_DIR/source-probe.sh" "$SCRIPT_DIR/lib.sh" \
        >/dev/null 2>&1; then
    printf '%s\n' 'non-loopback demo API URL was accepted' >&2
    exit 1
fi

if SOLANA_SCRIPT_DIR_OVERRIDE=$ROOT/scripts/solana \
        LOCAL_SOLANA_RUNTIME_ROOT=$ROOT/docs \
        sh -c '. "$1"' "$SCRIPT_DIR/source-probe.sh" \
        "$ROOT/scripts/solana/lib.sh" >/dev/null 2>&1; then
    printf '%s\n' 'unapproved Solana runtime root was accepted' >&2
    exit 1
fi

if "$SCRIPT_DIR/reset.sh" >/dev/null 2>&1; then
    printf '%s\n' 'reset unexpectedly accepted without --yes' >&2
    exit 1
fi

! rg -n '0[.]0[.]0[.]0|mainnet|devnet|testnet|api-key|Authorization: Bearer [^$]' \
    "$SCRIPT_DIR" "$compose" --glob '!test.sh' >/dev/null

printf '%s\n' 'Phase 7F shell and configuration safety checks passed.'
