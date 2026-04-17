#!/bin/bash

# Proto Lake CLI End-to-End Test Suite
# Tests the full CLI pipeline: init -> create-bundle -> build -> publish
# Uses protolakew wrapper script (the real user workflow)

set -uo pipefail

# Ensure we're running from the e2e/ directory
cd "$(dirname "$0")"

# ============================================================================
# Configuration
# ============================================================================

LAKE_NAME="test_lake"
OUTPUT_DIR="./test-cli-output"
DOCKER_IMAGE="protolake-proto-lake:latest"
GAZELLE_SOURCE_PATH="${PROTOLAKE_GAZELLE_SOURCE_PATH:-$(cd ../../protolake-gazelle 2>/dev/null && pwd)}"
BUILD_TIMEOUT=1200  # 20 minutes for cold build

# Counters
PASS_COUNT=0
FAIL_COUNT=0

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

check_dir() {
    if [ -d "$1" ]; then
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
    rm -rf "$OUTPUT_DIR"
    docker volume rm "protolake-cache-${LAKE_NAME}" 2>/dev/null || true
}

# ============================================================================
# Phase 0: Prerequisites
# ============================================================================

echo "============================================"
echo " Proto Lake CLI End-to-End Test Suite"
echo "============================================"
echo ""
echo "Phase 0: Checking prerequisites..."

PREREQ_FAIL=false
for cmd in docker; do
    if command -v "$cmd" &> /dev/null; then
        echo "  Found: $cmd"
    else
        echo "  MISSING: $cmd"
        PREREQ_FAIL=true
    fi
done

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

# Clean previous state
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
docker volume rm "protolake-cache-${LAKE_NAME}" 2>/dev/null || true

echo "  Building Docker image..."
if ! docker-compose build 2>&1 | tail -5; then
    fail "Docker build"
    echo "Docker build failed. Aborting."
    exit 1
fi
pass "Docker build"

# ============================================================================
# Phase 2: Init Lake (via docker run)
# ============================================================================

echo ""
echo "Phase 2: Init lake via CLI..."

ABS_OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

INIT_OUTPUT=$(docker run --rm \
    -v "${ABS_OUTPUT_DIR}:/proto-lake" \
    -e "PROTO_LAKE_BASE_PATH=/proto-lake" \
    "$DOCKER_IMAGE" \
    init --name "$LAKE_NAME" \
        --java-group-id com.example.proto \
        --python-package-prefix example_proto \
        --js-package-scope @example 2>&1)
INIT_EXIT=$?

if [ $INIT_EXIT -eq 0 ]; then
    pass "init command exit code 0"
else
    fail "init command exit code $INIT_EXIT"
    echo "  Output: $INIT_OUTPUT"
fi

echo "  Init output (last 5 lines):"
echo "$INIT_OUTPUT" | tail -5 | sed 's/^/    /'

# Verify lake structure
LAKE_DIR="$OUTPUT_DIR/$LAKE_NAME"
check_file "$LAKE_DIR/lake.yaml" "Lake lake.yaml"
check_file "$LAKE_DIR/MODULE.bazel" "Lake MODULE.bazel"
check_file "$LAKE_DIR/BUILD.bazel" "Lake root BUILD.bazel"
check_dir "$LAKE_DIR/tools" "Lake tools directory"
check_file "$LAKE_DIR/protolakew" "protolakew wrapper script"

if [ -f "$LAKE_DIR/protolakew" ]; then
    if [ -x "$LAKE_DIR/protolakew" ]; then
        pass "protolakew is executable"
    else
        fail "protolakew is not executable"
    fi
fi

# Verify lake.yaml contents
check_file_contains "$LAKE_DIR/lake.yaml" "$LAKE_NAME" "lake.yaml has lake name"
check_file_contains "$LAKE_DIR/lake.yaml" "com.example.proto" "lake.yaml has Java group_id"

# ============================================================================
# Phase 3: Create Bundles (via protolakew)
# ============================================================================

echo ""
echo "Phase 3: Create bundles via protolakew..."

export PROTOLAKE_GAZELLE_SOURCE_PATH="$GAZELLE_SOURCE_PATH"
export PROTOLAKE_IMAGE="$DOCKER_IMAGE"

# Create service_a
echo "  Creating service_a..."
SA_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew create-bundle --name service_a \
    --bundle-prefix company_a.platform \
    --java-artifact-id service-a-proto \
    --python-package-name company_service_a_proto \
    --js-package-name @company/service-a-proto 2>&1)
SA_EXIT=$?

if [ $SA_EXIT -eq 0 ]; then
    pass "create-bundle service_a exit code 0"
else
    fail "create-bundle service_a exit code $SA_EXIT"
    echo "  Output:"
    echo "$SA_OUTPUT" | tail -10 | sed 's/^/    /'
fi

# Create service_b
echo "  Creating service_b..."
SB_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew create-bundle --name service_b \
    --bundle-prefix company_b.apps \
    --java-artifact-id service-b-proto \
    --python-package-name company_service_b_proto \
    --js-package-name @company/service-b-proto 2>&1)
SB_EXIT=$?

if [ $SB_EXIT -eq 0 ]; then
    pass "create-bundle service_b exit code 0"
else
    fail "create-bundle service_b exit code $SB_EXIT"
    echo "  Output:"
    echo "$SB_OUTPUT" | tail -10 | sed 's/^/    /'
fi

# Verify bundle.yaml files
check_file "$LAKE_DIR/company_a/platform/service_a/bundle.yaml" "service_a bundle.yaml"
check_file "$LAKE_DIR/company_b/apps/service_b/bundle.yaml" "service_b bundle.yaml"

# Verify bundle.yaml contents
check_file_contains "$LAKE_DIR/company_a/platform/service_a/bundle.yaml" "service_a" "service_a bundle.yaml has name"
check_file_contains "$LAKE_DIR/company_a/platform/service_a/bundle.yaml" "service-a-proto" "service_a bundle.yaml has artifact_id"
check_file_contains "$LAKE_DIR/company_b/apps/service_b/bundle.yaml" "service_b" "service_b bundle.yaml has name"
check_file_contains "$LAKE_DIR/company_b/apps/service_b/bundle.yaml" "service-b-proto" "service_b bundle.yaml has artifact_id"

# ============================================================================
# Phase 4: Copy Proto Files
# ============================================================================

echo ""
echo "Phase 4: Copy proto files..."

cp -r test-protos/company_a/platform/service_a/api "$LAKE_DIR/company_a/platform/service_a/"
cp -r test-protos/company_a/platform/service_a/types "$LAKE_DIR/company_a/platform/service_a/"
cp -r test-protos/company_b/apps/service_b/api "$LAKE_DIR/company_b/apps/service_b/"

# Remove auto-generated example.proto files
rm -f "$LAKE_DIR/company_a/platform/service_a/example.proto"
rm -f "$LAKE_DIR/company_b/apps/service_b/example.proto"

check_file "$LAKE_DIR/company_a/platform/service_a/api/v1/user.proto" "service_a user.proto"
check_file "$LAKE_DIR/company_a/platform/service_a/types/v1/common.proto" "service_a common.proto"
check_file "$LAKE_DIR/company_b/apps/service_b/api/v1/order.proto" "service_b order.proto"

echo ""
echo "  Lake structure:"
if command -v tree &> /dev/null; then
    tree "$LAKE_DIR" -I "bazel-*" --noreport 2>/dev/null | head -40
else
    find "$LAKE_DIR" -type f \( -name "*.proto" -o -name "*.yaml" -o -name "BUILD.bazel" -o -name "MODULE.bazel" -o -name "protolakew" \) | sort | head -40
fi

# ============================================================================
# Phase 5: Build Lake (via protolakew)
# ============================================================================

echo ""
echo "Phase 5: Build lake via protolakew (timeout: ${BUILD_TIMEOUT}s)..."

export PROTOLAKE_BAZEL_TIMEOUT_SECONDS=1200

# Run build with timeout tracking
BUILD_LOG="${ABS_OUTPUT_DIR}/build.log"
(cd "$LAKE_DIR" && ./protolakew build --install-local --skip-validation) > "$BUILD_LOG" 2>&1 &
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
        cleanup
        exit 1
    fi

    # Show progress every 30 seconds
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

# Show key build output
echo ""
echo "  Build phases:"
grep -E "^\[protolake\]|Phase|Build succeeded|Build failed|command succeeded" "$BUILD_LOG" 2>/dev/null | head -20 | sed 's/^/    /'

# ============================================================================
# Phase 6: Verify Generated BUILD Files
# ============================================================================

echo ""
echo "Phase 6: Verify generated BUILD files..."

SA_BUILD="$LAKE_DIR/company_a/platform/service_a/BUILD.bazel"
SB_BUILD="$LAKE_DIR/company_b/apps/service_b/BUILD.bazel"

check_file "$SA_BUILD" "service_a bundle BUILD.bazel"
check_file "$SB_BUILD" "service_b bundle BUILD.bazel"

# Check subdirectory BUILD files
check_file "$LAKE_DIR/company_a/platform/service_a/api/v1/BUILD.bazel" "service_a api/v1 BUILD.bazel"
check_file "$LAKE_DIR/company_a/platform/service_a/types/v1/BUILD.bazel" "service_a types/v1 BUILD.bazel"
check_file "$LAKE_DIR/company_b/apps/service_b/api/v1/BUILD.bazel" "service_b api/v1 BUILD.bazel"

# Verify bundle BUILD contents
if [ -f "$SA_BUILD" ]; then
    check_file_contains "$SA_BUILD" "proto_library" "service_a has proto_library"
    check_file_contains "$SA_BUILD" "java_proto_bundle\|java_grpc_library" "service_a has Java rules"
    check_file_contains "$SA_BUILD" "py_proto_bundle\|python_grpc_library" "service_a has Python rules"
    check_file_contains "$SA_BUILD" "js_proto_bundle\|js_grpc_library" "service_a has JS rules"
    check_file_contains "$SA_BUILD" "publish_to_maven" "service_a has publish_to_maven"
    check_file_contains "$SA_BUILD" "publish_to_pypi" "service_a has publish_to_pypi"
    check_file_contains "$SA_BUILD" "publish_to_npm" "service_a has publish_to_npm"
    check_file_contains "$SA_BUILD" "service-a-proto" "service_a has correct artifact_id"

    echo ""
    echo "  service_a BUILD.bazel (first 40 lines):"
    head -40 "$SA_BUILD" | sed 's/^/    /'
fi

if [ -f "$SB_BUILD" ]; then
    check_file_contains "$SB_BUILD" "publish_to_maven" "service_b has publish_to_maven"
    check_file_contains "$SB_BUILD" "service-b-proto" "service_b has correct artifact_id"
fi

# ============================================================================
# Phase 7: Verify Published Artifacts
# ============================================================================

echo ""
echo "Phase 7: Verify published artifacts..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    # --- Java (Maven Local) ---
    echo "  Checking Maven local repository..."
    M2_BASE="$HOME/.m2/repository/com/example/proto"

    if [ -d "$M2_BASE/service-a-proto" ]; then
        pass "Maven: service-a-proto directory exists"

        SA_JAR=$(find "$M2_BASE/service-a-proto" -name "*.jar" 2>/dev/null | head -1)
        if [ -n "$SA_JAR" ]; then
            pass "Maven: service-a-proto JAR exists"
        else
            fail "Maven: service-a-proto JAR not found"
        fi

        SA_POM=$(find "$M2_BASE/service-a-proto" -name "*.pom" 2>/dev/null | head -1)
        if [ -n "$SA_POM" ]; then
            pass "Maven: service-a-proto POM exists"
        else
            fail "Maven: service-a-proto POM not found"
        fi
    else
        fail "Maven: service-a-proto directory not found at $M2_BASE/service-a-proto"
    fi

    if [ -d "$M2_BASE/service-b-proto" ]; then
        pass "Maven: service-b-proto directory exists"
    else
        fail "Maven: service-b-proto directory not found"
    fi

    # --- Python (PyPI Local) ---
    echo "  Checking PyPI local index..."
    PYPI_BASE="$HOME/.cache/pip/simple"

    PY_PACKAGES=$(find "$PYPI_BASE" -name "*.whl" 2>/dev/null || echo "")
    if [ -n "$PY_PACKAGES" ]; then
        pass "PyPI: wheel files found in local index"
        echo "$PY_PACKAGES" | sed 's/^/    /'
    else
        fail "PyPI: no wheel files found"
    fi

    # --- JavaScript (npm file mode) ---
    echo "  Checking npm local packages..."
    NPM_PKG_BASE="$HOME/.proto-lake/npm-packages"

    NPM_PACKAGES=$(find "$NPM_PKG_BASE" -name "package.json" 2>/dev/null || echo "")
    if [ -n "$NPM_PACKAGES" ]; then
        pass "npm: packages found"
        echo "$NPM_PACKAGES" | sed 's/^/    /'
    else
        fail "npm: no packages found in $NPM_PKG_BASE"
    fi
else
    echo "  Skipping artifact verification (build did not succeed)"
fi

# ============================================================================
# Phase 7.5: Test dep show CLI command
# ============================================================================

echo ""
echo "Phase 7.5: Test dep show CLI command..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    # Test 1: Show all snippets for service_a
    DEP_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew dep show service_a 2>&1)
    DEP_EXIT=$?

    if [ $DEP_EXIT -eq 0 ]; then
        pass "dep show exit code 0"
    else
        fail "dep show exit code $DEP_EXIT"
        echo "  Output: $DEP_OUTPUT"
    fi

    # Verify output contains Java Maven snippet
    if echo "$DEP_OUTPUT" | grep -q "com.example.proto"; then
        pass "dep show contains Java group_id"
    else
        fail "dep show missing Java group_id"
    fi

    if echo "$DEP_OUTPUT" | grep -q "service-a-proto"; then
        pass "dep show contains artifact_id"
    else
        fail "dep show missing artifact_id"
    fi

    # Test 2: --plain mode (snippet text only)
    PLAIN_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew dep show service_a --plain 2>&1)
    # Should not contain [protolake] prefix
    if echo "$PLAIN_OUTPUT" | grep -q "\[protolake\]"; then
        fail "dep show --plain contains [protolake] prefix"
    else
        pass "dep show --plain is clean"
    fi

    # Test 3: --json mode
    # Filter out Quarkus startup logs (stderr mixed in by Docker) — extract only the JSON object
    JSON_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew dep show service_a --json 2>/dev/null)
    if echo "$JSON_OUTPUT" | sed -n '/^{/,/^}/p' | jq -r '.snippets' > /dev/null 2>&1; then
        pass "dep show --json produces valid JSON"
    else
        fail "dep show --json invalid JSON"
    fi

    # Test 4: --lang filter
    JAVA_ONLY=$(cd "$LAKE_DIR" && ./protolakew dep show service_a --lang java --plain 2>&1)
    if echo "$JAVA_ONLY" | grep -q "groupId\|implementation("; then
        pass "dep show --lang java shows Java snippet"
    else
        fail "dep show --lang java missing Java snippet"
    fi
else
    echo "  Skipping dep show tests (build did not succeed)"
fi

# ============================================================================
# Phase 8: Summary & Cleanup
# ============================================================================

echo ""
echo "============================================"
echo " CLI Test Summary"
echo "============================================"
echo "  Passed: $PASS_COUNT"
echo "  Failed: $FAIL_COUNT"
echo "  Total:  $((PASS_COUNT + FAIL_COUNT))"
echo "============================================"

if [ $FAIL_COUNT -gt 0 ] && [ -f "$BUILD_LOG" ]; then
    echo ""
    echo "Build log saved at: $BUILD_LOG"
fi

cleanup

if [ $FAIL_COUNT -gt 0 ]; then
    exit 1
else
    echo ""
    echo "All CLI tests passed!"
    exit 0
fi
