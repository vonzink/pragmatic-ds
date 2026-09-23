# syntax=docker/dockerfile:1

# ---- Build stage: compile the Spring Boot fat jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Resolve dependencies first so they cache across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Build the application (tests run in CI, not in the image build).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Runtime stage: slim JRE, non-root, health-checked ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r app && useradd -r -g app -u 1001 app \
 && mkdir -p /app/data/documents /app/data/packs \
 && chown -R app:app /app

# Application jar and the domain packs it loads by relative path at runtime.
COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar
COPY --chown=app:app packs ./packs

USER app
# 8080 = application traffic; 9090 = internal management port (health + Prometheus
# metrics under the prod profile). Publish 8080 only — keep 9090 on the internal
# monitoring network.
EXPOSE 8080 9090

# Default to the prod profile so the image ships secure-by-default; override
# SPRING_PROFILES_ACTIVE for local experimentation. MaxRAMPercentage lets the
# JVM size the heap from the container's cgroup memory limit.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Health lives on the management port under the prod profile (the image default).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:9090/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
