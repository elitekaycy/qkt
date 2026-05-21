# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre AS runtime
RUN useradd -r -m -s /usr/sbin/nologin qkt
COPY --from=build /src/build/install/qkt /opt/qkt
ENV PATH="/opt/qkt/bin:$PATH"
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
