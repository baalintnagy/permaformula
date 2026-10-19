# syntax=docker/dockerfile:1.7
FROM node:24-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend ./
COPY renderer-core/src/main/resources/mathjax /build/renderer-core/src/main/resources/mathjax
RUN npm run build

FROM maven:3-eclipse-temurin-25@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525 AS build
WORKDIR /build
COPY pom.xml ./
COPY render-contract ./render-contract
COPY renderer-core ./renderer-core
COPY renderer-service ./renderer-service
COPY permaformula-api ./permaformula-api
COPY --from=frontend-build /build/frontend/dist ./frontend/dist
RUN mvn -B -ntp verify dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/runtime-deps

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS base
WORKDIR /app
ENV HOME=/tmp/permaformula TMPDIR=/tmp/permaformula \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp/permaformula"

FROM base AS renderer
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/permaformula/browsers PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN --mount=from=build,source=/build/renderer-service/target/runtime-deps,target=/opt/installer,ro \
    TMPDIR=/tmp HOME=/root java -Djava.io.tmpdir=/tmp -cp '/opt/installer/*' com.microsoft.playwright.CLI install --with-deps chromium --no-shell \
    && rm -rf /var/lib/apt/lists/* /root/.cache \
    && groupadd --gid 10002 renderer \
    && useradd --uid 10002 --gid 10002 --no-create-home --home-dir /tmp/permaformula --shell /usr/sbin/nologin renderer \
    && mkdir -p /run/permaformula && chown 10002:10002 /run/permaformula && chmod 0750 /run/permaformula \
    && chmod -R a-w /opt/permaformula/browsers
COPY --from=build --chmod=0444 /build/renderer-service/target/renderer-service-1.0.jar /app/service.jar
COPY --from=build /build/renderer-service/target/runtime-deps/ /app/lib/
COPY --chmod=0555 docker/renderer-entrypoint.sh /usr/local/bin/renderer-entrypoint
ENV RENDERER_SOCKET=/run/permaformula/render.sock RENDERER_CHROMIUM_SANDBOX=true RENDERER_TIMEOUT_SECONDS=30
USER 10002:10002
ENTRYPOINT ["/usr/local/bin/renderer-entrypoint"]

FROM base AS runtime
RUN groupadd --gid 10001 permaformula \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp/permaformula --shell /usr/sbin/nologin permaformula
COPY --from=build --chmod=0444 /build/permaformula-api/target/permaformula-api-1.0.jar /app/service.jar
COPY --chmod=0555 docker/entrypoint.sh /usr/local/bin/permaformula-entrypoint
ENV PORT=5000 PERMAFORMULA_RENDERING_SOCKET=/run/permaformula/render.sock PERMAFORMULA_IMAGE_CACHE_MAXIMUM_SIZE=256MB \
    PERMAFORMULA_IMAGE_CACHE_PERSISTENCE_BACKEND=postgres
USER 10001:10001
EXPOSE 5000
ENTRYPOINT ["/usr/local/bin/permaformula-entrypoint"]
