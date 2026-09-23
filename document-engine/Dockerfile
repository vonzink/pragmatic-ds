# engine-api — Java 21, Spring Boot 3.5.
#
# Base image is Eclipse Temurin (GPL-2.0 WITH Classpath-exception-2.0). The
# Classpath Exception permits linking without copyleft; every OpenJDK build is
# GPLv2+CE and there is no permissive JDK. See docs/LICENSING.md 4.1.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Warm the Gradle cache on build files alone, so a source-only change does not
# re-resolve every dependency.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY platform/build.gradle.kts platform/
COPY ingestion/build.gradle.kts ingestion/
COPY orchestration/build.gradle.kts orchestration/
COPY parsing/build.gradle.kts parsing/
COPY classification/build.gradle.kts classification/
COPY extraction/build.gradle.kts extraction/
COPY review/build.gradle.kts review/
COPY app/build.gradle.kts app/
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY . .
RUN ./gradlew --no-daemon :app:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root. The API holds borrower data; it does not need root to serve HTTP.
RUN groupadd --system docengine \
 && useradd --system --gid docengine --home /app docengine \
 && mkdir -p /data/blobs \
 && chown -R docengine:docengine /app /data

COPY --from=build --chown=docengine:docengine /src/app/build/libs/*.jar /app/app.jar

USER docengine
EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
