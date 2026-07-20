#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/lib.sh"

demo_check_prerequisites
umask 077
for directory in "$DEMO_RUNTIME_DIR" "$DEMO_OUTPUT_DIR" \
        "$DEMO_RUNTIME_DIR/config" "$DEMO_RUNTIME_DIR/configtree" \
        "$DEMO_RUNTIME_DIR/logs" "$DEMO_RUNTIME_DIR/pids"; do
    demo_prepare_directory "$directory"
done
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

password_property=$DEMO_RUNTIME_DIR/configtree/spring.datasource.password
if [ ! -f "$password_property" ]; then
    cp "$DEMO_RUNTIME_DIR/postgres-password" "$password_property"
fi
cmp -s "$DEMO_RUNTIME_DIR/postgres-password" "$password_property" \
    || demo_die 'database password configuration is inconsistent; run reset.sh --yes'
chmod 600 "$password_property"

cat > "$DEMO_COMPOSE_ENV" <<EOF
SOLANA_DEMO_RUNTIME_DIR=$DEMO_RUNTIME_DIR
SOLANA_DEMO_POSTGRES_PORT=$SOLANA_DEMO_POSTGRES_PORT
EOF
chmod 600 "$DEMO_COMPOSE_ENV"

docker image inspect "$SOLANA_DEMO_POSTGRES_IMAGE" >/dev/null 2>&1 \
    || demo_die 'approved PostgreSQL digest is not cached; no pull was attempted'
JAVA_HOME=$SOLANA_DEMO_JAVA_HOME "$DEMO_ROOT/mvnw" -o -pl control-plane -am \
    -DskipTests package
demo_compose up -d --wait postgres
demo_validator "$DEMO_ROOT/scripts/solana/bootstrap.sh" --provision-only
demo_start_validator
demo_bootstrap_fixture
demo_write_application_config
demo_start_control_plane "${LOCAL_DEMO_WORKER_ENABLED:-true}"
demo_require_runtime
printf '%s\n' 'Phase 7F runtime, private validator fixture, PostgreSQL, and packaged control plane are started.'
