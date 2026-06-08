# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist
# A minimal runtime image: only the JDK modules qkt actually loads. jdeps reports
# java.base, java.logging, java.naming, java.xml, jdk.httpserver; jdk.crypto.ec (TLS
# cipher suites for HTTPS) and jdk.unsupported (sun.misc.Unsafe, used by okio/kotlin)
# are loaded reflectively so jdeps can't see them and must be named explicitly.
RUN "$JAVA_HOME/bin/jlink" \
        --add-modules java.base,java.logging,java.naming,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.unsupported \
        --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
        --output /javaruntime

FROM debian:12-slim AS runtime
RUN useradd -r -m -s /usr/sbin/nologin qkt
ENV JAVA_HOME=/opt/java/runtime
ENV PATH="/opt/java/runtime/bin:/opt/qkt/bin:$PATH"
# The jlink runtime carries its own cacerts, so HTTPS trust needs no OS ca-certificates.
COPY --from=build /javaruntime $JAVA_HOME
COPY --from=build /src/build/install/qkt /opt/qkt
ENV QKT_STATE_DIR=/var/lib/qkt
RUN mkdir -p /var/lib/qkt /strategies && chown -R qkt:qkt /var/lib/qkt /strategies
USER qkt
# Run from the user's home, not a bind-mounted directory. A host-side remount
# of a bind mount (e.g. a deploy tool re-cloning the repo behind /strategies)
# invalidates the container's working directory and breaks `docker exec` with
# a runc "current working directory is outside of container mount namespace
# root" error — which fails the healthcheck even though the daemon is fine.
WORKDIR /home/qkt
EXPOSE 40000-50000
ENTRYPOINT ["qkt", "daemon", "--load-dir", "/strategies"]

# ── Developer image ─────────────────────────────────────────────────────────
# The runtime plus an editor and a shell, for authoring and testing strategies
# inside a container — `qkt` and `vim`/`nano` on PATH, a writable `/work`, and a
# bash default so you can `docker run -dit` it and `docker exec` in. Published as
# the `:dev` tag. Kept separate from the runtime so the production trading image
# stays minimal and editor-free; do not deploy `:dev` to prod.
FROM runtime AS dev
USER root
# $QKT_HOME points `qkt editor install` at the bundled syntax files.
ENV QKT_HOME=/opt/qkt
RUN apt-get update \
    && apt-get install -y --no-install-recommends vim nano less ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# Pre-install .qkt syntax highlighting for vim. Best-effort: a distribution built
# without the editor bundle must not fail the image build.
RUN qkt editor install vim --yes || echo "qkt: skipped baking vim syntax (editor bundle absent)"
RUN mkdir -p /work && chmod 0777 /work
WORKDIR /work
# Reset the daemon entrypoint — the dev image is a shell you live in, not a
# long-lived daemon. `docker run qkt:dev` drops to bash; `docker run qkt:dev qkt
# backtest x.qkt` runs the CLI directly.
ENTRYPOINT []
CMD ["bash"]
