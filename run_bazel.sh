#!/bin/bash
# Helper to run bazel commands in docker

# Get absolute paths
TEST_LAKE_ABS=$(pwd)
PROTOLAKE_GAZELLE_ABS="$(cd ../../../protolake-gazelle && pwd)"
DOCKER_IMAGE="protolake-proto-lake:latest"

# Build the command
CMD="bazel $*"

docker run --rm \
    -v "${TEST_LAKE_ABS}:/workspace" \
    -v "${PROTOLAKE_GAZELLE_ABS}:/protolake-gazelle" \
    -v "${HOME}/.m2:/home/protolake/.m2" \
    -v "bazel-disk-cache:/home/protolake/.cache/bazel-disk-cache" \
    -v "bazel-output-base:/home/protolake/.cache/bazel" \
    -w "/workspace" \
    --entrypoint /bin/bash \
    ${DOCKER_IMAGE} \
    -c "$CMD"
