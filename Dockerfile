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

# Stage 2: Build the protoc plugins for the API specs a bundle build writes:
# OpenAPI 2.0 from the google.api.http bindings, AsyncAPI 3.1 for
# WebSocket-marked RPCs. They are built from source with a pinned Go, not taken
# from their releases, so a fix to Go's standard library reaches the image
# without waiting for either project to release. go install checks every
# module against Go's checksum database, and the build is reproducible, so each
# binary is pinned by version and by its SHA-256 per platform. The stage runs on
# the build host and cross-compiles, which writes the same bytes as a native
# build. The versions and digests also sit in templates/tools/api_specs.bzl,
# where they key Bazel's cache and are checked against the installed binaries;
# ApiSpecPinsTest keeps the files in step.
FROM --platform=$BUILDPLATFORM golang:1.27.3-bookworm AS plugins

ARG TARGETARCH
ARG PROTOC_GEN_OPENAPIV2_VERSION=2.31.0
ARG PROTOC_GEN_OPENAPIV2_SHA256_AMD64=67d380171115cc58912d568763bc0007494397cd1a6db99aff757298e9812ac4
ARG PROTOC_GEN_OPENAPIV2_SHA256_ARM64=2179d8822db05bbe435eb7f1a1eed56926039cdada65745357476aac32fff5b4
ARG PROTOC_GEN_CONNECT_OPENAPI_VERSION=0.28.0
ARG PROTOC_GEN_CONNECT_OPENAPI_SHA256_AMD64=7c689ed7d699f1eb122555d4528b2b946822e2aef3959c6995f5999dca6716b3
ARG PROTOC_GEN_CONNECT_OPENAPI_SHA256_ARM64=20b2aa3981fa6de81c40eb63d5895cea5363edb8f92e8f1358afc0620338cdee
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-trimpath GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org
RUN case "${TARGETARCH}" in \
      amd64) OPENAPIV2_SHA256="${PROTOC_GEN_OPENAPIV2_SHA256_AMD64}"; \
             CONNECT_SHA256="${PROTOC_GEN_CONNECT_OPENAPI_SHA256_AMD64}";; \
      arm64) OPENAPIV2_SHA256="${PROTOC_GEN_OPENAPIV2_SHA256_ARM64}"; \
             CONNECT_SHA256="${PROTOC_GEN_CONNECT_OPENAPI_SHA256_ARM64}";; \
      *) echo "no pinned protoc plugins for ${TARGETARCH}" >&2; exit 1;; \
    esac \
    && GOOS=linux GOARCH="${TARGETARCH}" go install -ldflags="-s -w" \
      "github.com/grpc-ecosystem/grpc-gateway/v2/protoc-gen-openapiv2@v${PROTOC_GEN_OPENAPIV2_VERSION}" \
    && GOOS=linux GOARCH="${TARGETARCH}" go install -ldflags="-s -w" \
      "github.com/sudorandom/protoc-gen-connect-openapi@v${PROTOC_GEN_CONNECT_OPENAPI_VERSION}" \
    && BIN="$(go env GOPATH)/bin" \
    && if [ "${TARGETARCH}" != "$(go env GOHOSTARCH)" ]; then BIN="${BIN}/linux_${TARGETARCH}"; fi \
    && echo "${OPENAPIV2_SHA256}  ${BIN}/protoc-gen-openapiv2" | sha256sum -c - \
    && echo "${CONNECT_SHA256}  ${BIN}/protoc-gen-connect-openapi" | sha256sum -c - \
    && mkdir -p /out \
    && mv "${BIN}/protoc-gen-openapiv2" /out/protoc-gen-openapiv2 \
    && mv "${BIN}/protoc-gen-connect-openapi" /out/protoc-gen-connect-openapi

# Stage 3: Install Proto Lake tools
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

# The API spec plugins the plugins stage built; running each here checks it
# starts on the image's platform.
COPY --from=plugins /out/protoc-gen-openapiv2 /out/protoc-gen-connect-openapi /usr/local/bin/
RUN /usr/local/bin/protoc-gen-openapiv2 --version \
    && /usr/local/bin/protoc-gen-connect-openapi --version

# Stage 4: Final runtime image
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

# Copy Bazel, Buf and the API spec plugins from tools stage
COPY --from=tools /usr/local/bin/bazel /usr/local/bin/bazel
COPY --from=tools /usr/local/bin/buf /usr/local/bin/buf
COPY --from=tools /usr/local/bin/protoc-gen-openapiv2 /usr/local/bin/protoc-gen-openapiv2
COPY --from=tools /usr/local/bin/protoc-gen-connect-openapi /usr/local/bin/protoc-gen-connect-openapi

# The cohub_api Bazel module lakes import cohub/api/v1/exposure.proto from: each
# lake's MODULE.bazel points a local_path_override here (CohubApiModule). The
# proto is the one protolake itself compiles, so the two never drift.
COPY src/main/bazel/cohub_api/ /opt/protolake/bazel/cohub_api/
COPY src/main/proto/cohub/api/v1/exposure.proto /opt/protolake/bazel/cohub_api/cohub/api/v1/exposure.proto

# Install twine for remote PyPI publishing
RUN pip3 install twine --break-system-packages

# Install pnpm (needed for generating pnpm-lock.yaml at workspace init).
# Pinned to 9.x: the image's nodejs comes from Ubuntu noble's apt (18.x),
# and pnpm 10+ requires Node >= 22.13 — unpinned, workspace init's
# `pnpm install --lockfile-only` fails and lockfiles silently stop
# regenerating.
# Install protoc-gen-es globally (used by es_proto_compile rule for Connect-ES codegen)
RUN npm install -g pnpm@9 @bufbuild/protoc-gen-es@2.11.0

# Create proto-lake user
RUN groupadd -g 1001 protolake && \
    useradd -r -u 1001 -g protolake protolake && \
    mkdir -p /var/proto-lake /home/protolake /proto-lake && \
    chown -R protolake:protolake /var/proto-lake /home/protolake /proto-lake

# Set up local package repositories and cache directories
RUN mkdir -p /home/protolake/.m2/repository \
             /home/protolake/.cache/pip/simple \
             /home/protolake/.cache/bazel \
             /home/protolake/.cache/bazel-disk-cache \
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