#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

demo_check_prerequisites
umask 077
mkdir -p "$DEMO_RUNTIME_DIR" "$DEMO_OUTPUT_DIR"
chmod 700 "$DEMO_RUNTIME_DIR" "$DEMO_OUTPUT_DIR"

for file in postgres-password sender-token operator-token; do
    if [ ! -f "$DEMO_RUNTIME_DIR/$file" ]; then
        openssl rand -hex 32 > "$DEMO_RUNTIME_DIR/$file"
    fi
    chmod 600 "$DEMO_RUNTIME_DIR/$file"
done
if [ ! -f "$DEMO_RUNTIME_DIR/run-id" ]; then
    openssl rand -hex 8 > "$DEMO_RUNTIME_DIR/run-id"
fi
chmod 600 "$DEMO_RUNTIME_DIR/run-id"

[ -f "$DEMO_RUNTIME_DIR/deployment.env" ] || : > "$DEMO_RUNTIME_DIR/deployment.env"
chmod 600 "$DEMO_RUNTIME_DIR/deployment.env"

cat > "$DEMO_COMPOSE_ENV" <<EOF
DEMO_UID=$(id -u)
DEMO_GID=$(id -g)
DEMO_RUNTIME_DIR=$DEMO_RUNTIME_DIR
EOF
chmod 600 "$DEMO_COMPOSE_ENV"

postgres_image='postgres:17.10-alpine3.23@sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4'
foundry_image='ghcr.io/foundry-rs/foundry:v1.5.1@sha256:3a70bfa9bd2c732a767bb60d12c8770b40e8f9b6cca28efc4b12b1be81c7f28e'
temurin_image='eclipse-temurin:25.0.2_10-jre-alpine-3.23@sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888'
docker image inspect "$postgres_image" >/dev/null 2>&1 \
    || demo_die "approved PostgreSQL digest is not cached; do not pull without separate approval"
docker image inspect --format '{{json .RepoDigests}}' "$postgres_image" 2>/dev/null \
    | grep -F 'sha256:8189a1f6e40904781fc9e2612687877791d21679866db58b1de996b31fc312e4' >/dev/null \
    || demo_die "cached PostgreSQL image does not expose the approved digest"

for specification in \
    "$foundry_image|sha256:3a70bfa9bd2c732a767bb60d12c8770b40e8f9b6cca28efc4b12b1be81c7f28e" \
    "$temurin_image|sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888"; do
    image=${specification%%|*}
    digest=${specification##*|}
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        docker pull "$image"
    fi
    docker image inspect --format '{{json .RepoDigests}}' "$image" 2>/dev/null \
        | grep -F "$digest" >/dev/null \
        || demo_die "approved Foundry or Temurin digest is unavailable after pull"
done

build_java_home=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
(cd "$DEMO_ROOT/contracts/evm" && forge build --offline)
JAVA_HOME=$build_java_home "$DEMO_ROOT/mvnw" -o -pl control-plane -am -DskipTests package
demo_compose build --pull=false control-plane
demo_require_runtime
printf '%s\n' "Phase 6D runtime material and application image are ready; services remain stopped."
