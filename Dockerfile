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
COPY --from=build /src/build/libs/fpl-web-1.0.jar /app/app.jar
COPY --from=build /src/config.yml /app/config.yml
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["java", "-jar", "/app/app.jar", "server", "/app/config.yml"]
