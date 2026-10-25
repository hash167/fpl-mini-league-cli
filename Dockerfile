# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
COPY config.yml ./
ARG APP_BUILD
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar ${APP_BUILD:+-PappBuild=$APP_BUILD}

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ARG OTEL_AGENT_VERSION=2.31.0
ARG OTEL_AGENT_SHA256=d48673b2ff956b26d809bc34243649913d4eefd9191c4e175b686da633e0134b
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
# Pinned agent: fail the build if the download is not the expected jar. ADD from a URL is 0600
# root, so make it readable by the runtime user (65534) or -javaagent fails at startup.
RUN echo "${OTEL_AGENT_SHA256}  /app/opentelemetry-javaagent.jar" | sha256sum -c - \
    && chmod 644 /app/opentelemetry-javaagent.jar
COPY --from=build /src/build/libs/fpl-web-1.0.jar /app/app.jar
COPY --from=build /src/config.yml /app/config.yml
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh && mkdir -p /app/data && chown 65534:65534 /app/data
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/app/entrypoint.sh"]
