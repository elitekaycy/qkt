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
WORKDIR /strategies
EXPOSE 40000-50000
ENTRYPOINT ["qkt", "daemon", "--load-dir", "/strategies"]
