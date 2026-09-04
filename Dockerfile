# syntax=docker/dockerfile:1

### ---- Build stage ----------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy wrapper and build files first so dependency layers are cached
# independently of application source changes.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Now copy sources and build the boot jar.
COPY src ./src
RUN ./gradlew bootJar --no-daemon

### ---- Runtime stage ----------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system talkdoc && useradd --system --gid talkdoc talkdoc

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN chown -R talkdoc:talkdoc /app

USER talkdoc

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
