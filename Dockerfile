# syntax=docker/dockerfile:1.7
FROM maven:3-eclipse-temurin-25@sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp verify dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/runtime-deps

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS runtime
WORKDIR /app

# Install the browser selected by pom.xml, without retaining installer jars,
# Maven, source code or the Maven repository in the runtime image.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/permmath/browsers \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN --mount=from=build,source=/build/target/runtime-deps,target=/opt/installer,ro \
    java -cp '/opt/installer/*' com.microsoft.playwright.CLI install --with-deps chromium --no-shell \
    && rm -rf /var/lib/apt/lists/* /root/.cache \
    && groupadd --gid 10001 permmath \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp/permmath \
        --shell /usr/sbin/nologin permmath \
    && chmod -R a-w /opt/permmath/browsers

COPY --from=build --chmod=0444 /build/target/permmath-service-1.0.jar /app/service.jar
COPY --chmod=0555 docker/entrypoint.sh /usr/local/bin/permmath-entrypoint

ENV PORT=5000 \
    HOME=/tmp/permmath \
    TMPDIR=/tmp/permmath \
    PERMMATH_RENDERING_BROWSER_DIRECTORY=/opt/permmath/browsers \
    PERMMATH_RENDERING_CHROMIUM_SANDBOX=true \
    PERMMATH_IMAGE_CACHE_MAXIMUM_SIZE=256MB \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp/permmath"

USER 10001:10001
EXPOSE 5000
ENTRYPOINT ["/usr/local/bin/permmath-entrypoint"]
