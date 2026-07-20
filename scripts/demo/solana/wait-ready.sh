#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime

started=$(date +%s)
timeout=${SOLANA_DEMO_READY_TIMEOUT_SECONDS:-180}
solana=$DEMO_ROOT/.solana-tools/agave/bin/solana
spl=$DEMO_ROOT/.solana-tools/spl-token/bin/spl-token
while :; do
    postgres=$(demo_compose ps --status running --services postgres 2>/dev/null || true)
    validator=false
    if demo_validator "$DEMO_ROOT/scripts/solana/status.sh" 2>/dev/null \
            | jq -e '.state == "healthy" and .rpc == "http://127.0.0.1:8899"' >/dev/null 2>&1; then
        validator=true
    fi
    cluster=false
    expected=$(jq -er '.clusterIdentity' "$DEMO_RUNTIME_DIR/cluster.json" 2>/dev/null || true)
    actual=$($solana --url "$DEMO_RPC_URL" genesis-hash 2>/dev/null || true)
    [ -n "$expected" ] && [ "$actual" = "$expected" ] && cluster=true
    mint=false
    mint_address=$(demo_metadata_value '.mintAddress' 2>/dev/null || true)
    supply=$($spl --url "$DEMO_RPC_URL" supply "$mint_address" 2>/dev/null || true)
    case "$supply" in ''|*[!0-9]*) ;; *) mint=true ;; esac
    health=false
    curl --fail --silent --max-time 3 "$DEMO_API_URL/actuator/health/readiness" \
        >/dev/null 2>&1 && health=true
    status=false
    [ "$health" = true ] && demo_status_json >/dev/null 2>&1 && status=true
    ports=false
    binding=$(docker inspect "$DEMO_PROJECT-postgres-1" \
        --format '{{json .HostConfig.PortBindings}}' 2>/dev/null || true)
    printf '%s' "$binding" | jq -e --arg port "$SOLANA_DEMO_POSTGRES_PORT" \
        '.["5432/tcp"] == [{"HostIp":"127.0.0.1","HostPort":$port}]' \
        >/dev/null 2>&1 && ports=true
    if [ "$postgres" = postgres ] && [ "$validator" = true ] \
            && [ "$cluster" = true ] && [ "$mint" = true ] \
            && [ "$health" = true ] && [ "$status" = true ] \
            && [ "$ports" = true ]; then
        printf '%s\n' 'PostgreSQL, private Agave, classic-SPL fixture, control plane, local identity, and status are ready.'
        exit 0
    fi
    now=$(date +%s)
    if [ $((now - started)) -ge "$timeout" ]; then
        printf 'Readiness timeout: postgres=%s validator=%s cluster=%s mint=%s health=%s status=%s ports=%s\n' \
            "${postgres:-stopped}" "$validator" "$cluster" "$mint" \
            "$health" "$status" "$ports" >&2
        exit 1
    fi
    sleep 2
done
