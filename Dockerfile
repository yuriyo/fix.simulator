# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the fat jar with Maven
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy dependency descriptor first so layer is cached when only source changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn package -q -DskipTests

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 – Runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="FIX Exchange Simulator"
LABEL description="FIX 4.4 exchange simulator with order matching engine"

# Create non-root user
RUN addgroup -S fix && adduser -S -G fix fix

# Application directories
RUN mkdir -p /app/config /app/store /app/logs \
    && chown -R fix:fix /app

WORKDIR /app

# Copy fat jar from builder
COPY --from=builder --chown=fix:fix \
    /build/target/fix-simulator-*-SNAPSHOT.jar \
    /app/fix-simulator.jar

# Copy default config (can be overridden with a volume mount)
COPY --chown=fix:fix config/ /app/config/

USER fix

# FIX acceptor port
EXPOSE 9876

# store/ and logs/ are written at runtime – declare as volumes
VOLUME ["/app/store", "/app/logs", "/app/config"]

ENTRYPOINT ["java", "-jar", "/app/fix-simulator.jar"]
CMD ["/app/config/server.cfg", "/app/config/instruments.yml"]
