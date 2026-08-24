# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
COPY config.yml ./
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /src/build/libs/fpl-web-1.0.jar /app/app.jar
COPY --from=build /src/config.yml /app/config.yml
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh && mkdir -p /app/data && chown 65534:65534 /app/data
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/app/entrypoint.sh"]
