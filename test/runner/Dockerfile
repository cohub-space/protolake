# Karate OSS + grpcurl. Built locally by run.py on first invocation.
# ~400 MB of unpacked layers, against an ~840 MB download for
# karatelabs/karate-chrome (which we don't need — we don't run UI
# tests). Generated scaffold-once by CoHub.
#
# Why custom: Karate OSS has no built-in gRPC support (gRPC native is a
# commercial Karate Labs add-on requiring a karate.lic file). The OSS
# core (MIT) ships karate.exec(), so we use the open-source path: shell
# out to grpcurl from feature files via karate.exec().
#
# Upstream sources (both MIT, fetched at docker build time):
#   - Karate OSS 2.1.3 — https://github.com/karatelabs/karate (MIT); the
#     standalone JAR, pinned by sha256 because release assets are mutable
#   - grpcurl 1.9.4 — https://github.com/fullstorydev/grpcurl (MIT); built
#     from source on the current Go toolchain, because a release binary
#     carries the Go standard library it was cut with and CVE scanners
#     flag that library (go install verifies the module against
#     sum.golang.org)
#   - Base: eclipse-temurin:21-jre-noble (Eclipse Public License 2.0;
#           Karate 2 needs Java 21)
FROM golang:1.27 AS grpcurl
ARG GRPCURL_VERSION=1.9.4
RUN CGO_ENABLED=0 GOTOOLCHAIN=local GOBIN=/out go install \
      -ldflags "-X main.version=v${GRPCURL_VERSION}" \
      github.com/fullstorydev/grpcurl/cmd/grpcurl@v${GRPCURL_VERSION}

FROM eclipse-temurin:21-jre-noble

ARG KARATE_VERSION=2.1.3
ARG KARATE_SHA256=c0126f3ad907e4e92a9944a0bfa4deae96beae599daf8513f47169516b5714c0

# Keep the OS layer at the distro's current fix level.
RUN apt-get update && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl ca-certificates bash \
    && rm -rf /var/lib/apt/lists/*

COPY --from=grpcurl /out/grpcurl /usr/local/bin/grpcurl

# Karate standalone JAR.
RUN curl -fsSL -o /karate.jar \
      "https://github.com/karatelabs/karate/releases/download/v${KARATE_VERSION}/karate-${KARATE_VERSION}.jar" \
    && echo "${KARATE_SHA256}  /karate.jar" | sha256sum -c -

WORKDIR /test
ENTRYPOINT ["java", "-jar", "/karate.jar"]
