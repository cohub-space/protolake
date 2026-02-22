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

ARG TARGETARCH

RUN apt-get update && apt-get install -y \
    curl \
    git \
    python3 \
    python3-pip \
    nodejs \
    npm \
    && rm -rf /var/lib/apt/lists/*

# Install Bazel 8.5.1 (multi-arch) — latest LTS
RUN BAZEL_ARCH=$(case "${TARGETARCH}" in arm64) echo "linux-arm64";; amd64) echo "linux-x86_64";; *) echo "linux-${TARGETARCH}";; esac) \
    && curl -fLo /usr/local/bin/bazel "https://github.com/bazelbuild/bazel/releases/download/8.5.1/bazel-8.5.1-${BAZEL_ARCH}" \
    && chmod +x /usr/local/bin/bazel

# Install Buf (multi-arch)
RUN BUF_ARCH=$(case "${TARGETARCH}" in arm64) echo "Linux-aarch64";; amd64) echo "Linux-x86_64";; *) echo "Linux-${TARGETARCH}";; esac) \
    && curl -sSL "https://github.com/bufbuild/buf/releases/download/v1.56.0/buf-${BUF_ARCH}" -o /usr/local/bin/buf \
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
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Copy Bazel and Buf from tools stage
COPY --from=tools /usr/local/bin/bazel /usr/local/bin/bazel
COPY --from=tools /usr/local/bin/buf /usr/local/bin/buf

# Install twine for remote PyPI publishing
RUN pip3 install twine --break-system-packages

# Create proto-lake user
RUN groupadd -g 1001 protolake && \
    useradd -r -u 1001 -g protolake protolake && \
    mkdir -p /var/proto-lake /home/protolake /proto-lake && \
    chown -R protolake:protolake /var/proto-lake /home/protolake /proto-lake

# Set up local package repositories and cache directories
RUN mkdir -p /home/protolake/.m2/repository \
             /home/protolake/.cache/pip/simple \
             /home/protolake/.cache/bazel \
             /home/protolake/.proto-lake/npm-packages \
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