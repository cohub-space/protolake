# Karate OSS + grpcurl. Built locally by run.sh on first invocation.
# ~250 MB vs ~800 MB for karatelabs/karate-chrome (which we don't need —
# we don't run UI tests). Generated scaffold-once by CoHub.
#
# Why custom: Karate OSS has no built-in gRPC support (gRPC is a
# commercial Karate Labs add-on). We use the open-source path: shell
# out to grpcurl from feature files via karate.exec().
#
# Pinned versions:
#   - Karate OSS 1.5.2 (latest published in karatelabs/karate-chrome)
#   - grpcurl 1.9.1 (single static binary, multi-arch)
#   - eclipse-temurin:17-jre-jammy (matches Karate's JVM target)
FROM eclipse-temurin:17-jre-jammy

ARG KARATE_VERSION=1.5.2
ARG GRPCURL_VERSION=1.9.1

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates bash \
    && rm -rf /var/lib/apt/lists/*

# grpcurl: single static binary, multi-arch.
RUN ARCH=$(uname -m | sed 's/x86_64/x86_64/;s/aarch64/arm64/') && \
    curl -fsSL "https://github.com/fullstorydev/grpcurl/releases/download/v${GRPCURL_VERSION}/grpcurl_${GRPCURL_VERSION}_linux_${ARCH}.tar.gz" \
      | tar -xz -C /usr/local/bin grpcurl

# Karate fat JAR.
RUN curl -fsSL -o /karate.jar \
      "https://github.com/karatelabs/karate/releases/download/v${KARATE_VERSION}/karate-${KARATE_VERSION}.jar"

WORKDIR /test
ENTRYPOINT ["java", "-jar", "/karate.jar"]
