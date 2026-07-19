# Eclipse Temurin Java 25.0.2+10 on Alpine 3.23, pinned by approved index digest.
FROM eclipse-temurin:25.0.2_10-jre-alpine-3.23@sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888

LABEL org.opencontainers.image.title="Digital Banking local demo control plane" \
      org.opencontainers.image.description="Non-production reference environment; local chain and synthetic data only" \
      org.opencontainers.image.licenses="Apache-2.0 AND GPL-2.0-with-classpath-exception"

WORKDIR /app
COPY --chmod=0444 control-plane/target/digital-banking-control-plane-0.1.0-SNAPSHOT.jar /app/application.jar
COPY --chmod=0555 scripts/demo/control-plane-entrypoint.sh /app/entrypoint.sh

USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/entrypoint.sh"]
