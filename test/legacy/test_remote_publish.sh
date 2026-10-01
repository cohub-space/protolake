#!/bin/bash

# Proto Lake Remote Publishing End-to-End Test Suite
# Tests remote artifact registry publishing via protolakew CLI flags
# Uses a mock HTTP server to capture upload requests
#
# Prerequisites: Docker, protolake-gazelle at ../../protolake-gazelle (or PROTOLAKE_GAZELLE_SOURCE_PATH)
# Usage: bash test/legacy/test_remote_publish.sh, or ./test/run.py e2e for all three suites

set -uo pipefail

# Run from test/, where the stack's docker-compose.yml and its ./test-*-output
# mounts live; the scripts moved to test/legacy/ but the stack did not.
cd "$(dirname "$0")/.."

# Never publish to a real registry from a developer's shell: drop every publishing
# setting protolakew would forward. The suite sets each destination it means.
unset MAVEN_REPO PYPI_REPO REGISTRY_TOKEN MAVEN_USER MAVEN_PASSWORD \
      NPM_PUBLISH_MODE NPM_REGISTRY_URL NPM_REGISTRY_TOKEN JS_TARGETS

# ============================================================================
# Configuration
# ============================================================================

LAKE_NAME="test_remote_lake"
OUTPUT_DIR="./test-remote-output"
DOCKER_IMAGE="protolake-proto-lake:latest"
GAZELLE_SOURCE_PATH="${PROTOLAKE_GAZELLE_SOURCE_PATH:-$(cd ../../protolake-gazelle 2>/dev/null && pwd)}"
BUILD_TIMEOUT=1200  # 20 minutes for cold build
MOCK_SERVER_PORT=18080
BASE_VOLUME=""  # the scratch local root's bazel output base, named by protolakew
BUILD_PID=""
SUITE_DONE=false  # set once the summary prints; cleanup keeps the output otherwise

# Counters
PASS_COUNT=0
FAIL_COUNT=0

# PIDs to clean up
MOCK_SERVER_PID=""

# ============================================================================
# Helper Functions
# ============================================================================

pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    echo "  PASS: $1"
}

fail() {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "  FAIL: $1"
}

check_file() {
    if [ -f "$1" ]; then
        pass "$2"
    else
        fail "$2 (missing: $1)"
    fi
}

check_file_contains() {
    local file="$1"
    local pattern="$2"
    local desc="$3"
    if [ -f "$file" ] && grep -q "$pattern" "$file"; then
        pass "$desc"
    else
        fail "$desc (pattern '$pattern' not found in $file)"
    fi
}

cleanup() {
    echo ""
    echo "Cleaning up..."
    if [ -n "$MOCK_SERVER_PID" ] && kill -0 "$MOCK_SERVER_PID" 2>/dev/null; then
        kill "$MOCK_SERVER_PID" 2>/dev/null || true
        wait "$MOCK_SERVER_PID" 2>/dev/null || true
        echo "  Stopped mock server (PID $MOCK_SERVER_PID)"
    fi
    # An interrupted or timed-out build: stop it, and the container protolakew runs, before
    # touching the volume that container holds.
    if [ -n "$BUILD_PID" ] && kill -0 "$BUILD_PID" 2>/dev/null; then
        kill "$BUILD_PID" 2>/dev/null || true
        wait "$BUILD_PID" 2>/dev/null || true
    fi
    if [ -n "$BASE_VOLUME" ]; then
        local holders
        holders=$(docker ps -q --filter "volume=$BASE_VOLUME")
        # rm -f stops and removes in one call, so the volume is free when it returns.
        if [ -n "$holders" ]; then docker rm -f $holders >/dev/null 2>&1 || true; fi
        docker volume rm "$BASE_VOLUME" >/dev/null 2>&1 || true
    fi
    # Only a run that finished clean removes its output; any other keeps it for the logs.
    if [ "$SUITE_DONE" = true ] && [ "$FAIL_COUNT" -eq 0 ]; then rm -rf "$OUTPUT_DIR"; fi
}

# Every exit cleans up, a failed or interrupted run included.
trap cleanup EXIT
trap 'exit 130' INT TERM

# ============================================================================
# Mock HTTP Server
# ============================================================================

MOCK_SERVER_SCRIPT=$(cat <<'PYEOF'
import http.server
import json
import os
import sys
import threading

log_file = sys.argv[1]
port = int(sys.argv[2])

class MockRegistryHandler(http.server.BaseHTTPRequestHandler):
    def do_PUT(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b''
        auth = self.headers.get('Authorization', '')
        entry = json.dumps({
            'method': 'PUT',
            'path': self.path,
            'size': len(body),
            'content_type': self.headers.get('Content-Type', ''),
            'auth': auth,
        })
        with open(log_file, 'a') as f:
            f.write(entry + '\n')
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'OK')

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b''
        auth = self.headers.get('Authorization', '')
        entry = json.dumps({
            'method': 'POST',
            'path': self.path,
            'size': len(body),
            'content_type': self.headers.get('Content-Type', ''),
            'auth': auth,
        })
        with open(log_file, 'a') as f:
            f.write(entry + '\n')
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'OK')

    def log_message(self, format, *args):
        pass  # Suppress stderr logging

server = http.server.HTTPServer(('0.0.0.0', port), MockRegistryHandler)
print(f'Mock registry server listening on port {port}', flush=True)
server.serve_forever()
PYEOF
)

# ============================================================================
# Phase 0: Prerequisites
# ============================================================================

echo "============================================"
echo " Proto Lake Remote Publishing E2E Test"
echo "============================================"
echo ""
echo "Phase 0: Checking prerequisites..."

PREREQ_FAIL=false
for cmd in docker python3 curl; do
    if command -v "$cmd" &> /dev/null; then
        echo "  Found: $cmd"
    else
        echo "  MISSING: $cmd"
        PREREQ_FAIL=true
    fi
done

if ! docker compose version &> /dev/null; then
    echo "  MISSING: docker compose (v2)"
    PREREQ_FAIL=true
fi
if [ "$PREREQ_FAIL" = true ]; then
    echo ""
    echo "Missing prerequisites. Install them and retry."
    exit 1
fi

if [ ! -d "$GAZELLE_SOURCE_PATH" ]; then
    echo "  MISSING: protolake-gazelle (set PROTOLAKE_GAZELLE_SOURCE_PATH or check out as sibling at ../../protolake-gazelle)"
    exit 1
fi
echo "  Found: protolake-gazelle at $GAZELLE_SOURCE_PATH"

# ============================================================================
# Phase 1: Docker Build
# ============================================================================

echo ""
echo "Phase 1: Docker build..."

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

echo "  Building Docker image..."
if ! docker compose build 2>&1 | tail -5; then
    fail "Docker build"
    echo "Docker build failed. Aborting."
    exit 1
fi
pass "Docker build"

# ============================================================================
# Phase 2: Start Mock Registry Server
# ============================================================================

echo ""
echo "Phase 2: Starting mock registry server on port $MOCK_SERVER_PORT..."

ABS_OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
# protolakew's local stores (the --install-local half) go to this scratch root,
# not the developer's ~/.m2, pip cache and protolake db.
LOCAL_ROOT="$ABS_OUTPUT_DIR/local-root"
MOCK_LOG="${ABS_OUTPUT_DIR}/mock_server.log"
: > "$MOCK_LOG"

python3 -c "$MOCK_SERVER_SCRIPT" "$MOCK_LOG" "$MOCK_SERVER_PORT" &
MOCK_SERVER_PID=$!
sleep 1

if kill -0 "$MOCK_SERVER_PID" 2>/dev/null; then
    pass "Mock registry server started (PID $MOCK_SERVER_PID)"
else
    fail "Mock registry server failed to start"
    exit 1
fi

# Verify mock server responds
if curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${MOCK_SERVER_PORT}/test" | grep -q "200"; then
    pass "Mock server accepts PUT requests"
else
    fail "Mock server not responding"
    exit 1
fi

# ============================================================================
# Phase 3: Init Lake
# ============================================================================

echo ""
echo "Phase 3: Init lake via CLI..."

INIT_OUTPUT=$(docker run --rm \
    -v "${ABS_OUTPUT_DIR}:/proto-lake" \
    -e "PROTO_LAKE_BASE_PATH=/proto-lake" \
    "$DOCKER_IMAGE" \
    init --name "$LAKE_NAME" \
        --java-group-id com.test.proto \
        --python-package-prefix test_proto \
        --js-package-scope @test 2>&1)
INIT_EXIT=$?

if [ $INIT_EXIT -eq 0 ]; then
    pass "init command exit code 0"
else
    fail "init command exit code $INIT_EXIT"
    echo "  Output: $INIT_OUTPUT"
fi

LAKE_DIR="$OUTPUT_DIR/$LAKE_NAME"
check_file "$LAKE_DIR/protolakew" "protolakew wrapper script"

# ============================================================================
# Phase 4: Create Bundle & Copy Protos
# ============================================================================

echo ""
echo "Phase 4: Create bundle and copy protos..."

export PROTOLAKE_GAZELLE_SOURCE_PATH="$GAZELLE_SOURCE_PATH"
# protolakew runs the image Phase 1 built, not the published latest.
export PROTOLAKE_IMAGE="$DOCKER_IMAGE"

SA_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" create-bundle --name service_a \
    --bundle-prefix company_a.platform \
    --java-artifact-id service-a-proto \
    --python-package-name company_service_a_proto \
    --js-package-name @company/service-a-proto 2>&1)
SA_EXIT=$?
BASE_VOLUME=$(echo "$SA_OUTPUT" | sed -n 's/^protolakew: bazel output base volume: //p' | head -1)

if [ $SA_EXIT -eq 0 ]; then
    pass "create-bundle service_a exit code 0"
else
    fail "create-bundle service_a exit code $SA_EXIT"
    echo "  Output:"
    echo "$SA_OUTPUT" | tail -10 | sed 's/^/    /'
fi

cp -r fixtures/test-protos/company_a/platform/service_a/api "$LAKE_DIR/company_a/platform/service_a/"
cp -r fixtures/test-protos/company_a/platform/service_a/types "$LAKE_DIR/company_a/platform/service_a/"
rm -f "$LAKE_DIR/company_a/platform/service_a/example.proto"

check_file "$LAKE_DIR/company_a/platform/service_a/api/v1/user.proto" "service_a user.proto"

# ============================================================================
# Phase 5: Build with Remote Maven Publish (via CLI flags)
# ============================================================================

echo ""
echo "Phase 5: Build with --maven-repo pointing to mock server (timeout: ${BUILD_TIMEOUT}s)..."

# Determine the host address reachable from inside Docker
# On macOS Docker Desktop, host.docker.internal works
# On Linux, use the docker bridge gateway
if [[ "$(uname)" == "Darwin" ]]; then
    MOCK_HOST="host.docker.internal"
else
    MOCK_HOST=$(docker network inspect bridge -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null || echo "172.17.0.1")
fi
MOCK_MAVEN_URL="http://${MOCK_HOST}:${MOCK_SERVER_PORT}/maven"
MOCK_PYPI_URL="http://${MOCK_HOST}:${MOCK_SERVER_PORT}/pypi"

export PROTOLAKE_BAZEL_TIMEOUT_SECONDS=1200

# Reset the mock log before the build
: > "$MOCK_LOG"

BUILD_LOG="${ABS_OUTPUT_DIR}/build.log"
(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" build --install-local --skip-validation \
    --maven-repo "$MOCK_MAVEN_URL" \
    --pypi-repo "$MOCK_PYPI_URL" \
    --registry-token "test-bearer-token-12345") > "$BUILD_LOG" 2>&1 &
BUILD_PID=$!

# Wait with timeout and progress reporting
ELAPSED=0
BUILD_SUCCEEDED=false
while kill -0 $BUILD_PID 2>/dev/null; do
    if [ $ELAPSED -ge $BUILD_TIMEOUT ]; then
        echo "  Build timed out after ${BUILD_TIMEOUT}s"
        kill $BUILD_PID 2>/dev/null || true
        wait $BUILD_PID 2>/dev/null
        fail "Build completed within timeout"
        echo ""
        echo "  --- Build log (last 50 lines) ---"
        tail -50 "$BUILD_LOG" | sed 's/^/    /'
        echo "  --- End build log ---"
        exit 1
    fi

    if [ $((ELAPSED % 30)) -eq 0 ] && [ $ELAPSED -gt 0 ]; then
        LAST_LINE=$(tail -1 "$BUILD_LOG" 2>/dev/null || echo "...")
        echo "  [${ELAPSED}s] $LAST_LINE"
    fi

    sleep 5
    ELAPSED=$((ELAPSED + 5))
done

wait $BUILD_PID
BUILD_EXIT=$?

if [ $BUILD_EXIT -eq 0 ]; then
    BUILD_SUCCEEDED=true
    pass "Build completed successfully (${ELAPSED}s)"
else
    fail "Build exit code $BUILD_EXIT"
    echo ""
    echo "  --- Build log (last 80 lines) ---"
    tail -80 "$BUILD_LOG" | sed 's/^/    /'
    echo "  --- End build log ---"
fi

# ============================================================================
# Phase 6: Verify Mock Server Received Maven Uploads
# ============================================================================

echo ""
echo "Phase 6: Verify remote publishing requests..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    echo "  Mock server log:"
    cat "$MOCK_LOG" | sed 's/^/    /'
    echo ""

    # --- Maven uploads ---
    echo "  Checking Maven uploads..."

    MAVEN_PUT_COUNT=$(grep -c '"method": "PUT"' "$MOCK_LOG" 2>/dev/null || echo 0)
    # Also check for POST (pypi uses POST)
    MAVEN_PUTS=$(grep '/maven/' "$MOCK_LOG" 2>/dev/null || echo "")

    if [ -n "$MAVEN_PUTS" ]; then
        pass "Mock server received Maven upload requests"
    else
        fail "No Maven upload requests received by mock server"
    fi

    # Check for JAR upload
    if echo "$MAVEN_PUTS" | grep -q '\.jar"'; then
        pass "Maven: JAR file uploaded"
    else
        fail "Maven: no JAR upload found in mock log"
    fi

    # Check for POM upload
    if echo "$MAVEN_PUTS" | grep -q '\.pom"'; then
        pass "Maven: POM file uploaded"
    else
        fail "Maven: no POM upload found in mock log"
    fi

    # Check auth token was sent: as Basic oauth2accesstoken:<token> (the Maven
    # publisher and twine, the form Artifact Registry accepts), or as Bearer
    # (the PyPI publisher's fallback when twine is absent).
    BASIC_AUTH="Basic $(printf 'oauth2accesstoken:%s' 'test-bearer-token-12345' | base64)"
    if grep -q "\"auth\": \"$BASIC_AUTH\"" "$MOCK_LOG" 2>/dev/null \
        || grep -q '"auth": "Bearer test-bearer-token-12345"' "$MOCK_LOG" 2>/dev/null; then
        pass "Registry token sent in the request headers"
    else
        fail "Registry token not found in request headers"
    fi

    # Check correct artifact coordinates in path
    if echo "$MAVEN_PUTS" | grep -q 'service-a-proto'; then
        pass "Maven: correct artifact-id in upload path"
    else
        fail "Maven: artifact-id 'service-a-proto' not found in upload paths"
    fi

    if echo "$MAVEN_PUTS" | grep -q 'com/test/proto'; then
        pass "Maven: correct group-id path in upload"
    else
        fail "Maven: group-id path 'com/test/proto' not found in upload paths"
    fi

    # --- PyPI uploads ---
    echo "  Checking PyPI uploads..."

    # PyPI: twine posts to /pypi (no trailing slash), urllib fallback posts to /pypi/
    PYPI_REQUESTS=$(grep '"/pypi' "$MOCK_LOG" 2>/dev/null || echo "")

    if [ -n "$PYPI_REQUESTS" ]; then
        pass "Mock server received PyPI upload requests"
    else
        fail "No PyPI upload requests received by mock server"
    fi

    # --- Verify upload count ---
    echo "  Checking total upload count..."

    TOTAL_REQUESTS=$(wc -l < "$MOCK_LOG" | tr -d ' ')
    if [ "$TOTAL_REQUESTS" -ge 7 ]; then
        pass "Mock server received $TOTAL_REQUESTS total requests (6 Maven + PyPI)"
    else
        fail "Expected at least 7 requests, got $TOTAL_REQUESTS"
    fi

else
    echo "  Skipping upload verification (build did not succeed)"
fi

# ============================================================================
# Phase 7: Summary
# ============================================================================

echo ""
echo "============================================"
echo " Remote Publishing Test Summary"
echo "============================================"
echo "  Passed: $PASS_COUNT"
echo "  Failed: $FAIL_COUNT"
echo "  Total:  $((PASS_COUNT + FAIL_COUNT))"
echo "============================================"

if [ $FAIL_COUNT -gt 0 ] && [ -f "$BUILD_LOG" ]; then
    echo ""
    echo "Build log saved at: $BUILD_LOG"
    echo "Mock server log saved at: $MOCK_LOG"
fi

SUITE_DONE=true
if [ $FAIL_COUNT -gt 0 ]; then
    exit 1
else
    echo ""
    echo "All remote publishing tests passed!"
    exit 0
fi
