# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the fat jar with Maven
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy dependency descriptor first so layer is cached when only source changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn package -q -DskipTests

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 – Build the React admin UI
# ─────────────────────────────────────────────────────────────────────────────
FROM node:20-slim AS ui-builder

WORKDIR /ui

COPY admin-ui/package*.json ./
RUN npm ci --silent

COPY admin-ui/ .
RUN npm run build

# ─────────────────────────────────────────────────────────────────────────────
# Stage 3 – Runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

LABEL maintainer="FIX Exchange Simulator"
LABEL description="FIX 4.4 exchange simulator with order matching engine"

# Create non-root user
RUN groupadd -r fix && useradd -r -g fix fix

# Application directories
RUN mkdir -p /app/config /app/store /app/logs \
    && chown -R fix:fix /app

WORKDIR /app

# Copy fat jar from builder
COPY --from=builder --chown=fix:fix \
    /build/target/fix-simulator-*-SNAPSHOT.jar \
    /app/fix-simulator.jar

# Copy React admin UI build output
COPY --from=ui-builder --chown=fix:fix /ui/dist /app/admin-ui/dist

# Copy default config (can be overridden with a volume mount)
COPY --chown=fix:fix config/ /app/config/

USER fix

# FIX acceptor port + admin HTTP/WS port
EXPOSE 9876 8080

# store/ and logs/ are written at runtime – declare as volumes
VOLUME ["/app/store", "/app/logs", "/app/config"]

ENTRYPOINT ["java", "-jar", "/app/fix-simulator.jar"]
CMD ["/app/config/server.cfg", "/app/config/instruments.yml"]
