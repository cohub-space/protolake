# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy POM and source separately for better layer caching
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy source code
COPY src/ ./src/

# Build the application
RUN mvn clean package -DskipTests \
    -Daether.concurrency.named.threads=1 \
    -Daether.syncContext.named.discriminator.hostname=localhost \
    -Daether.syncContext.named.factory=noop \
    -Dmaven.repo.local=/build/.m2/repository \
    -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn

# Stage 2: Install Proto Lake tools
FROM ubuntu:24.04 AS tools

RUN apt-get update && apt-get install -y \
    curl \
    git \
    python3 \
    python3-pip \
    nodejs \
    npm \
    && rm -rf /var/lib/apt/lists/*

# Install Bazel 8.3.0 (latest as of June 2025)
RUN curl -fLo /usr/local/bin/bazel https://github.com/bazelbuild/bazel/releases/download/8.3.0/bazel-8.3.0-linux-arm64 \
    && chmod +x /usr/local/bin/bazel

# Install Buf
RUN curl -sSL https://github.com/bufbuild/buf/releases/download/v1.56.0/buf-Linux-aarch64 -o /usr/local/bin/buf \
    && chmod +x /usr/local/bin/buf

# Stage 3: Final runtime image
FROM eclipse-temurin:21-jdk-noble

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    git \
    python3 \
    python3-pip \
    nodejs \
    npm \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy Bazel and Buf from tools stage
COPY --from=tools /usr/local/bin/bazel /usr/local/bin/bazel
COPY --from=tools /usr/local/bin/buf /usr/local/bin/buf

# Create proto-lake user
RUN groupadd -g 1001 protolake && \
    useradd -r -u 1001 -g protolake protolake && \
    mkdir -p /var/proto-lake /home/protolake && \
    chown -R protolake:protolake /var/proto-lake /home/protolake

# Set up local package repositories
RUN mkdir -p /home/protolake/.m2/repository \
             /home/protolake/.cache/pip/simple \
             /home/protolake/.npm \
    && chown -R protolake:protolake /home/protolake

# Copy the application
COPY --from=build --chown=protolake:protolake /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=protolake:protolake /build/target/quarkus-app/*.jar /deployments/app.jar
COPY --from=build --chown=protolake:protolake /build/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=protolake:protolake /build/target/quarkus-app/quarkus/ /deployments/quarkus/

USER protolake
WORKDIR /home/protolake

# Expose ports
EXPOSE 8080 9090

# Set up Java options
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/app.jar"

# Run the application
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]