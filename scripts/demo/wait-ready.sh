#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"
demo_require_runtime

started=$(date +%s)
timeout=${DEMO_READY_TIMEOUT_SECONDS:-120}
while :; do
    postgres=$(demo_compose ps --status running --services postgres 2>/dev/null || true)
    anvil=$(demo_compose ps --status running --services anvil 2>/dev/null || true)
    control=$(demo_compose ps --status running --services control-plane 2>/dev/null || true)
    deployment_ok=false
    [ -s "$DEMO_RUNTIME_DIR/deployment.env" ] && deployment_ok=true
    chain_ok=false
    chain=$(demo_compose exec -T anvil cast chain-id \
        --rpc-url http://127.0.0.1:8545 2>/dev/null || true)
    [ "$chain" = 31337 ] && chain_ok=true
    health_ok=false
    demo_compose exec -T control-plane wget -q -O /dev/null \
        http://127.0.0.1:8080/actuator/health/readiness \
        >/dev/null 2>&1 && health_ok=true
    status_ok=false
    if [ "$health_ok" = true ]; then
        demo_status_json >/dev/null 2>&1 && status_ok=true
    fi
    ports_ok=false
    bindings=$(docker inspect "$DEMO_PROJECT-anvil-1" \
        "$DEMO_PROJECT-control-plane-1" 2>/dev/null || true)
    printf '%s' "$bindings" | jq -e '
        .[0].HostConfig.PortBindings["8545/tcp"]
            == [{"HostIp":"127.0.0.1","HostPort":"8545"}] and
        .[1].HostConfig.PortBindings["8080/tcp"]
            == [{"HostIp":"127.0.0.1","HostPort":"8080"}]
    ' >/dev/null 2>&1 && ports_ok=true
    if [ "$postgres" = postgres ] && [ "$anvil" = anvil ] \
            && [ "$control" = control-plane ] && [ "$deployment_ok" = true ] \
            && [ "$chain_ok" = true ] && [ "$health_ok" = true ] \
            && [ "$status_ok" = true ] && [ "$ports_ok" = true ]; then
        printf '%s\n' "PostgreSQL, Anvil, deployment, control plane, readiness, and demo status are ready."
        exit 0
    fi
    now=$(date +%s)
    if [ $((now - started)) -ge "$timeout" ]; then
        printf 'Readiness timeout: postgres=%s anvil=%s deployment=%s control-plane=%s chain=%s health=%s status=%s ports=%s\n' \
            "${postgres:-stopped}" "${anvil:-stopped}" "$deployment_ok" \
            "${control:-stopped}" "$chain_ok" "$health_ok" "$status_ok" \
            "$ports_ok" >&2
        exit 1
    fi
    sleep 2
done
