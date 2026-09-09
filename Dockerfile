# syntax=docker/dockerfile:1

# Build from source with the repository's Maven version and a Java 25 JDK.
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /build
COPY .mvn/ .mvn/
COPY --chmod=755 mvnw ./mvnw
COPY pom.xml ./

# Reuse downloads across source changes; an empty cache is also supported.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/*.jar application.jar

# Spring Boot 4.1 tools mode produces the efficient executable-JAR layout.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Keep compilers, Maven, source code, and download caches out of the runtime.
FROM eclipse-temurin:25-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 gateway \
    && useradd --uid 10001 --gid gateway --no-create-home --shell /usr/sbin/nologin gateway
WORKDIR /application

# Stable dependencies precede application code so their image layers can be reused.
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

# Application files remain root-owned and read-only to the runtime user.
USER 10001:10001
EXPOSE 8080

# Override HEALTHCHECK_URL when changing the management port, path, or scheme.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 4 --noproxy '*' "${HEALTHCHECK_URL:-http://localhost:8080/actuator/health}" > /dev/null || exit 1

# Exec form makes Java PID 1 and allows normal JVM signal handling.
ENTRYPOINT ["java", "-jar", "application.jar"]
