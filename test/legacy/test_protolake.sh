#!/bin/bash

# Proto Lake End-to-End Test Suite
# Tests the full pipeline: .proto files -> Gazelle -> Buf -> Bazel -> Package -> Local Publish

set -uo pipefail

# Ensure we're running from the e2e/ directory
cd "$(dirname "$0")"

# ============================================================================
# Configuration
# ============================================================================

LAKE_ID="test_lake"
LAKE_OUTPUT_DIR="./test-lake-output"
DOCKER_IMAGE="protolake-proto-lake:latest"
CONTAINER_NAME="proto-lake-service"
GRPC_PORT=9050
HTTP_PORT=8085
HEALTH_URL="http://localhost:${HTTP_PORT}/q/health"
MAX_BUILD_WAIT=1200  # 20 minutes for cold build (C++ gRPC compilation can take 10+ min)
POLL_INTERVAL=5

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

check() {
    local desc="$1"
    shift
    if "$@" > /dev/null 2>&1; then
        pass "$desc"
    else
        fail "$desc"
    fi
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

run_in_docker() {
    docker exec "$CONTAINER_NAME" bash -c "$1"
}

grpc_call() {
    local method="$1"
    local data="$2"
    grpcurl -plaintext -d "$data" "localhost:${GRPC_PORT}" "$method"
}

save_logs() {
    echo ""
    echo "--- Docker logs (last 100 lines) ---"
    docker-compose logs --tail=100 proto-lake 2>/dev/null || true
    echo "--- End Docker logs ---"
}

cleanup() {
    echo ""
    echo "Cleaning up..."
    docker-compose down --remove-orphans 2>/dev/null || true
}

# ============================================================================
# Phase 0: Prerequisites
# ============================================================================

echo "============================================"
echo " Proto Lake End-to-End Test Suite"
echo "============================================"
echo ""
echo "Phase 0: Checking prerequisites..."

PREREQ_FAIL=false
for cmd in jq grpcurl docker docker-compose; do
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
    echo "  brew install jq grpcurl"
    exit 1
fi

# ============================================================================
# Phase 1: Docker Build & Start
# ============================================================================

echo ""
echo "Phase 1: Docker build & start..."

# Clean previous state
rm -rf "$LAKE_OUTPUT_DIR"
mkdir -p "$LAKE_OUTPUT_DIR"
docker-compose down --remove-orphans 2>/dev/null || true

echo "  Building Docker image..."
if ! docker-compose build 2>&1 | tail -5; then
    fail "Docker build"
    echo "Docker build failed. Aborting."
    exit 1
fi
pass "Docker build"

echo "  Starting services..."
docker-compose up -d
pass "Docker compose up"

# Wait for health check
echo "  Waiting for service health..."
ELAPSED=0
while [ $ELAPSED -lt 60 ]; do
    if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
        pass "Service healthy (${ELAPSED}s)"
        break
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

if [ $ELAPSED -ge 60 ]; then
    fail "Service health check (timeout after 60s)"
    save_logs
    cleanup
    exit 1
fi

# ============================================================================
# Phase 2: Create Lake
# ============================================================================

echo ""
echo "Phase 2: Create lake..."

CREATE_LAKE_RESP=$(grpc_call "protolake.v1.LakeService/CreateLake" "{
  \"lake\": {
    \"name\": \"$LAKE_ID\",
    \"display_name\": \"Test Proto Lake\",
    \"description\": \"E2E test lake\"
  }
}" 2>&1)

if [ $? -eq 0 ]; then
    pass "CreateLake RPC"
else
    fail "CreateLake RPC"
    echo "  Response: $CREATE_LAKE_RESP"
fi

# Verify on-disk structure
LAKE_DIR="$LAKE_OUTPUT_DIR/$LAKE_ID"
check_file "$LAKE_DIR/MODULE.bazel" "Lake MODULE.bazel"
check_file "$LAKE_DIR/BUILD.bazel" "Lake root BUILD.bazel"
check_dir "$LAKE_DIR/tools" "Lake tools directory"
check_file "$LAKE_DIR/lake.yaml" "Lake configuration"

# ============================================================================
# Phase 3: Create Bundles
# ============================================================================

echo ""
echo "Phase 3: Create bundles..."

# Bundle: service_a
echo "  Creating service_a..."
SA_RESP=$(grpc_call "protolake.v1.BundleService/CreateBundle" "{
  \"parent\": \"lakes/$LAKE_ID\",
  \"bundle\": {
    \"name\": \"lakes/$LAKE_ID/bundles/service_a\",
    \"display_name\": \"Service A Proto Bundle\",
    \"description\": \"Platform service with user and common types\",
    \"bundle_prefix\": \"company_a.platform\",
    \"version\": \"1.0.0\",
    \"config\": {
      \"languages\": {
        \"java\": {
          \"enabled\": true,
          \"group_id\": \"com.company.proto\",
          \"artifact_id\": \"service-a-proto\"
        },
        \"python\": {
          \"enabled\": true,
          \"package_name\": \"company_service_a_proto\"
        },
        \"javascript\": {
          \"enabled\": true,
          \"package_name\": \"@company/service-a-proto\"
        }
      }
    }
  }
}" 2>&1)

if [ $? -eq 0 ]; then
    pass "CreateBundle service_a"
else
    fail "CreateBundle service_a: $SA_RESP"
fi

# Bundle: service_b
echo "  Creating service_b..."
SB_RESP=$(grpc_call "protolake.v1.BundleService/CreateBundle" "{
  \"parent\": \"lakes/$LAKE_ID\",
  \"bundle\": {
    \"name\": \"lakes/$LAKE_ID/bundles/service_b\",
    \"display_name\": \"Service B Proto Bundle\",
    \"description\": \"Apps service that depends on Service A types\",
    \"bundle_prefix\": \"company_b.apps\",
    \"version\": \"1.0.0\",
    \"config\": {
      \"languages\": {
        \"java\": {
          \"enabled\": true,
          \"group_id\": \"com.company.proto\",
          \"artifact_id\": \"service-b-proto\"
        },
        \"python\": {
          \"enabled\": true,
          \"package_name\": \"company_service_b_proto\"
        },
        \"javascript\": {
          \"enabled\": true,
          \"package_name\": \"@company/service-b-proto\"
        }
      }
    }
  }
}" 2>&1)

if [ $? -eq 0 ]; then
    pass "CreateBundle service_b"
else
    fail "CreateBundle service_b: $SB_RESP"
fi

# Verify bundle.yaml files
check_file "$LAKE_DIR/company_a/platform/service_a/bundle.yaml" "service_a bundle.yaml"
check_file "$LAKE_DIR/company_b/apps/service_b/bundle.yaml" "service_b bundle.yaml"

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

# Enable fat_jar for service_b to test both thin and fat JAR modes
sed -i '' '/^      group_id: "com.company.proto"/a\
      fat_jar: true' "$LAKE_DIR/company_b/apps/service_b/bundle.yaml"

check_file "$LAKE_DIR/company_a/platform/service_a/api/v1/user.proto" "service_a user.proto"
check_file "$LAKE_DIR/company_a/platform/service_a/types/v1/common.proto" "service_a common.proto"
check_file "$LAKE_DIR/company_b/apps/service_b/api/v1/order.proto" "service_b order.proto"

echo ""
echo "  Lake structure:"
if command -v tree &> /dev/null; then
    tree "$LAKE_DIR" -I "bazel-*" --noreport 2>/dev/null | head -40
else
    find "$LAKE_DIR" -type f \( -name "*.proto" -o -name "*.yaml" -o -name "BUILD.bazel" -o -name "MODULE.bazel" \) | sort | head -40
fi

# ============================================================================
# Phase 5: Build Lake with install_local=true
# ============================================================================

echo ""
echo "Phase 5: Build lake (with install_local=true)..."

BUILD_RESP=$(grpc_call "protolake.v1.LakeService/BuildLake" "{
  \"name\": \"lakes/$LAKE_ID\",
  \"install_local\": {\"should_install\": true}
}" 2>&1)

if [ $? -ne 0 ]; then
    fail "BuildLake RPC"
    echo "  Response: $BUILD_RESP"
    save_logs
    cleanup
    exit 1
fi

OPERATION_NAME=$(echo "$BUILD_RESP" | jq -r '.name' 2>/dev/null)
if [ -z "$OPERATION_NAME" ] || [ "$OPERATION_NAME" = "null" ]; then
    fail "Extract operation name"
    echo "  Response: $BUILD_RESP"
    save_logs
    cleanup
    exit 1
fi

pass "BuildLake initiated (operation: $OPERATION_NAME)"

# Poll for completion
echo "  Polling for build completion (timeout: ${MAX_BUILD_WAIT}s)..."
ELAPSED=0
BUILD_SUCCEEDED=false
LAST_PHASE=""

while [ $ELAPSED -lt $MAX_BUILD_WAIT ]; do
    OP_STATUS=$(grpc_call "google.longrunning.Operations/GetOperation" "{
      \"name\": \"$OPERATION_NAME\"
    }" 2>&1)

    if [ $? -ne 0 ]; then
        fail "GetOperation RPC"
        break
    fi

    IS_DONE=$(echo "$OP_STATUS" | jq -r '.done' 2>/dev/null)
    CURRENT_PHASE=$(echo "$OP_STATUS" | jq -r '.metadata.currentPhase' 2>/dev/null || echo "unknown")

    if [ "$CURRENT_PHASE" != "$LAST_PHASE" ]; then
        echo "  Phase: $CURRENT_PHASE (${ELAPSED}s)"
        LAST_PHASE="$CURRENT_PHASE"
    fi

    if [ "$IS_DONE" = "true" ]; then
        HAS_ERROR=$(echo "$OP_STATUS" | jq -r '.error' 2>/dev/null)
        if [ "$HAS_ERROR" != "null" ] && [ "$HAS_ERROR" != "" ]; then
            ERROR_MSG=$(echo "$OP_STATUS" | jq -r '.error.message' 2>/dev/null)
            fail "Build completed with error: $ERROR_MSG"

            echo ""
            echo "  Build metadata:"
            echo "$OP_STATUS" | jq '.metadata' 2>/dev/null | head -50

            save_logs
        else
            BUILD_SUCCEEDED=true
            pass "Build completed successfully"
        fi
        break
    fi

    sleep $POLL_INTERVAL
    ELAPSED=$((ELAPSED + POLL_INTERVAL))
done

if [ $ELAPSED -ge $MAX_BUILD_WAIT ]; then
    fail "Build timed out after ${MAX_BUILD_WAIT}s"
    echo "  Last status:"
    echo "$OP_STATUS" | jq '.' 2>/dev/null | head -30
    save_logs
fi

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
    check_file_contains "$SA_BUILD" "js_proto_bundle\|es_proto_compile" "service_a has JS rules"
    check_file_contains "$SA_BUILD" "publish_to_maven" "service_a has publish_to_maven"
    check_file_contains "$SA_BUILD" "publish_to_pypi" "service_a has publish_to_pypi"
    check_file_contains "$SA_BUILD" "publish_to_npm" "service_a has publish_to_npm"
    check_file_contains "$SA_BUILD" "group_id.*com.company.proto\|com\.company\.proto" "service_a has correct group_id"
    check_file_contains "$SA_BUILD" "service-a-proto" "service_a has correct artifact_id"

    echo ""
    echo "  service_a BUILD.bazel (first 40 lines):"
    head -40 "$SA_BUILD" | sed 's/^/    /'
fi

if [ -f "$SB_BUILD" ]; then
    check_file_contains "$SB_BUILD" "publish_to_maven" "service_b has publish_to_maven"
    check_file_contains "$SB_BUILD" "service-b-proto" "service_b has correct artifact_id"
    check_file_contains "$SB_BUILD" "fat_jar = True" "service_b has fat_jar = True"
fi

# ============================================================================
# Phase 7: Verify Bazel Targets (via docker exec)
# ============================================================================

echo ""
echo "Phase 7: Verify Bazel targets..."

LAKE_CONTAINER_PATH="/var/proto-lake/$LAKE_ID"

# Query proto targets
PROTO_TARGETS=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel query 'kind(proto_library, //...)' 2>/dev/null" 2>/dev/null || echo "")
if [ -n "$PROTO_TARGETS" ]; then
    PROTO_COUNT=$(echo "$PROTO_TARGETS" | wc -l | tr -d ' ')
    pass "Found $PROTO_COUNT proto_library targets"
    echo "    $PROTO_TARGETS" | head -10 | sed 's/^/    /'
else
    fail "No proto_library targets found"
fi

# Query bundle targets
BUNDLE_TARGETS=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel query 'kind(\".*_bundle\", //...)' 2>/dev/null" 2>/dev/null || echo "")
if [ -n "$BUNDLE_TARGETS" ]; then
    BUNDLE_COUNT=$(echo "$BUNDLE_TARGETS" | wc -l | tr -d ' ')
    pass "Found $BUNDLE_COUNT bundle targets"
    echo "$BUNDLE_TARGETS" | sed 's/^/    /'
else
    fail "No bundle targets found"
fi

# Query gRPC targets (rules_proto_grpc macros expand to internal rules, so query by label name)
GRPC_TARGETS=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel query 'filter(\"grpc\", //...)' 2>/dev/null" 2>/dev/null || echo "")
if [ -n "$GRPC_TARGETS" ]; then
    GRPC_COUNT=$(echo "$GRPC_TARGETS" | wc -l | tr -d ' ')
    pass "Found $GRPC_COUNT grpc-related targets"
    echo "$GRPC_TARGETS" | head -10 | sed 's/^/    /'
else
    fail "No grpc-related targets found"
fi

# Query publish targets
PUBLISH_TARGETS=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel query 'kind(\"genrule\", //...)' --output=label 2>/dev/null | grep publish_" 2>/dev/null || echo "")
if [ -n "$PUBLISH_TARGETS" ]; then
    PUBLISH_COUNT=$(echo "$PUBLISH_TARGETS" | wc -l | tr -d ' ')
    pass "Found $PUBLISH_COUNT publish targets"
    echo "$PUBLISH_TARGETS" | sed 's/^/    /'
else
    fail "No publish targets found"
fi

# ============================================================================
# Phase 8: Verify Build Artifacts
# ============================================================================

echo ""
echo "Phase 8: Verify build artifacts..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    # Resolve bazel-bin to its real path.
    # Three-tier resolution: bazel info → readlink → constructed fallback.
    BAZEL_BIN=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel info bazel-bin 2>/dev/null | tr -d '[:space:]'" 2>/dev/null)
    if [ -z "$BAZEL_BIN" ]; then
        BAZEL_BIN=$(run_in_docker "readlink -f $LAKE_CONTAINER_PATH/.bazel/bin 2>/dev/null | tr -d '[:space:]'" 2>/dev/null)
    fi
    if [ -z "$BAZEL_BIN" ]; then
        BAZEL_BIN="/home/protolake/.cache/bazel/execroot/_main/bazel-out/aarch64-fastbuild/bin"
    fi
    echo "  Resolved bazel-bin: $BAZEL_BIN"

    # After publishLocal, bundle artifacts may not be materialized in bazel-bin
    # (Bazel 9 with disk_cache can evict intermediate outputs consumed by publish
    # genrules). Rebuild bundle targets explicitly — this is instant (fully cached)
    # and ensures the .jar/.whl/.tgz files exist on disk for verification.
    echo "  Materializing bundle artifacts..."
    BUNDLE_LABELS=$(run_in_docker "cd $LAKE_CONTAINER_PATH && bazel query 'kind(\".*_proto_bundle\", //...)' 2>/dev/null" 2>/dev/null | tr '\n' ' ')
    if [ -n "$BUNDLE_LABELS" ]; then
        run_in_docker "cd $LAKE_CONTAINER_PATH && bazel build $BUNDLE_LABELS 2>/dev/null" 2>/dev/null
    fi

    # Check for Java artifacts (JARs)
    JAVA_JARS=$(run_in_docker "find $BAZEL_BIN -name '*_bundle.jar' -not -path '*/external/*'" 2>/dev/null || echo "")
    if [ -n "$JAVA_JARS" ]; then
        pass "Java JAR artifacts found"
        echo "$JAVA_JARS" | sed 's/^/    /'
    else
        fail "No Java JAR artifacts found in bazel-bin"
    fi

    # Check for Python artifacts (wheels)
    PY_WHLS=$(run_in_docker "find $BAZEL_BIN -name '*_bundle.whl' -not -path '*/external/*'" 2>/dev/null || echo "")
    if [ -n "$PY_WHLS" ]; then
        pass "Python wheel artifacts found"
        echo "$PY_WHLS" | sed 's/^/    /'
    else
        fail "No Python wheel artifacts found in bazel-bin"
    fi

    # Check for JavaScript artifacts (tarballs)
    JS_TGZS=$(run_in_docker "find $BAZEL_BIN -name '*_bundle.tgz' -not -path '*/external/*'" 2>/dev/null || echo "")
    if [ -n "$JS_TGZS" ]; then
        pass "JavaScript tgz artifacts found"
        echo "$JS_TGZS" | sed 's/^/    /'
    else
        fail "No JavaScript tgz artifacts found in bazel-bin"
    fi

    # ========================================================================
    # Phase 8b: Verify Artifact Contents
    # ========================================================================

    echo ""
    echo "Phase 8b: Verify artifact contents..."

    # --- Java JAR contents ---
    # NOTE: grep is run inside docker to avoid capturing jar tf output over grpc
    echo "  Checking Java JAR contents..."
    for JAR_PATH in $JAVA_JARS; do
        JAR_NAME=$(basename "$JAR_PATH")

        if echo "$JAR_PATH" | grep -q "service_a"; then
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'UserServiceGrpc'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains UserServiceGrpc"
            else
                fail "JAR $JAR_NAME missing UserServiceGrpc"
            fi
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -qi 'address\|common'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains common types"
            else
                fail "JAR $JAR_NAME missing common types (Address/Common)"
            fi

            # Thin JAR: should NOT contain protobuf runtime classes
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'com/google/protobuf/'" 2>/dev/null; then
                fail "JAR $JAR_NAME contains protobuf classes (should be thin)"
            else
                pass "JAR $JAR_NAME is thin (no protobuf classes)"
            fi

            # Jandex index for Quarkus gRPC service discovery
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'META-INF/jandex.idx'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains Jandex index"
            else
                fail "JAR $JAR_NAME missing META-INF/jandex.idx"
            fi

            # Proto descriptor (service_a sets generate_descriptor_set: true).
            # Expect META-INF/proto-descriptors/service_a.pb with non-zero bytes
            # and the bundle's service name encoded inside (FileDescriptorSet
            # always contains the service name as a UTF-8 string).
            DESC_PATH="META-INF/proto-descriptors/service_a.pb"
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q '$DESC_PATH'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains $DESC_PATH"
                if run_in_docker "rm -rf /tmp/desc-check && mkdir -p /tmp/desc-check && cd /tmp/desc-check && jar xf $JAR_PATH $DESC_PATH && [ -s /tmp/desc-check/$DESC_PATH ]" 2>/dev/null; then
                    pass "JAR $JAR_NAME descriptor file is non-empty"
                    if run_in_docker "strings /tmp/desc-check/$DESC_PATH | grep -q 'UserService'" 2>/dev/null; then
                        pass "JAR $JAR_NAME descriptor encodes UserService"
                    else
                        fail "JAR $JAR_NAME descriptor missing UserService"
                    fi
                else
                    fail "JAR $JAR_NAME descriptor extraction failed or empty"
                fi
                run_in_docker "rm -rf /tmp/desc-check" 2>/dev/null
            else
                fail "JAR $JAR_NAME missing $DESC_PATH (generate_descriptor_set: true)"
            fi
        fi

        if echo "$JAR_PATH" | grep -q "service_b"; then
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'OrderServiceGrpc'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains OrderServiceGrpc"
            else
                fail "JAR $JAR_NAME missing OrderServiceGrpc"
            fi
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -qi 'order'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains Order classes"
            else
                fail "JAR $JAR_NAME missing Order classes"
            fi

            # Fat JAR: should contain protobuf runtime classes (unlike service_a's thin JAR)
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'com/google/protobuf/'" 2>/dev/null; then
                pass "JAR $JAR_NAME is fat (contains protobuf classes)"
            else
                fail "JAR $JAR_NAME missing protobuf classes (should be fat)"
            fi

            # Jandex index should be present regardless of fat/thin
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'META-INF/jandex.idx'" 2>/dev/null; then
                pass "JAR $JAR_NAME contains Jandex index"
            else
                fail "JAR $JAR_NAME missing META-INF/jandex.idx"
            fi

            # Negative descriptor test: service_b does NOT set generate_descriptor_set,
            # so its JAR must not contain a META-INF/proto-descriptors/ entry.
            if run_in_docker "jar tf $JAR_PATH 2>/dev/null | grep -q 'META-INF/proto-descriptors/'" 2>/dev/null; then
                fail "JAR $JAR_NAME has proto descriptor (should not — generate_descriptor_set is unset)"
            else
                pass "JAR $JAR_NAME has no proto descriptor (correct — flag unset)"
            fi
        fi
    done

    # --- Python wheel contents ---
    echo "  Checking Python wheel contents..."
    for WHL_PATH in $PY_WHLS; do
        WHL_NAME=$(basename "$WHL_PATH")
        WHL_LISTING=$(run_in_docker "python3 -c \"import zipfile; [print(n) for n in zipfile.ZipFile('$WHL_PATH').namelist()]\" 2>/dev/null" 2>/dev/null || echo "")

        if echo "$WHL_PATH" | grep -q "service_a"; then
            if echo "$WHL_LISTING" | grep -q "user_pb2_grpc"; then
                pass "Wheel $WHL_NAME contains user_pb2_grpc"
            else
                fail "Wheel $WHL_NAME missing user_pb2_grpc"
            fi
            if echo "$WHL_LISTING" | grep -q "common_pb2"; then
                pass "Wheel $WHL_NAME contains common_pb2"
            else
                fail "Wheel $WHL_NAME missing common_pb2"
            fi
        fi

        if echo "$WHL_PATH" | grep -q "service_b"; then
            if echo "$WHL_LISTING" | grep -q "order_pb2"; then
                pass "Wheel $WHL_NAME contains order_pb2"
            else
                fail "Wheel $WHL_NAME missing order_pb2"
            fi
        fi
    done

    # --- JavaScript tgz contents (Connect-ES) ---
    echo "  Checking JavaScript tgz contents..."
    for TGZ_PATH in $JS_TGZS; do
        TGZ_NAME=$(basename "$TGZ_PATH")
        TGZ_LISTING=$(run_in_docker "tar tzf $TGZ_PATH 2>/dev/null" 2>/dev/null || echo "")

        if echo "$TGZ_PATH" | grep -q "service_a"; then
            if echo "$TGZ_LISTING" | grep -qi "user"; then
                pass "Tgz $TGZ_NAME contains user proto files"
            else
                fail "Tgz $TGZ_NAME missing user proto files"
            fi
            if echo "$TGZ_LISTING" | grep -q "package.json"; then
                pass "Tgz $TGZ_NAME contains package.json"
            else
                fail "Tgz $TGZ_NAME missing package.json"
            fi
            # Connect-ES: verify _pb.js files exist (protoc-gen-es output)
            if echo "$TGZ_LISTING" | grep -q "_pb\.js"; then
                pass "Tgz $TGZ_NAME contains _pb.js (Connect-ES)"
            else
                fail "Tgz $TGZ_NAME missing _pb.js files (Connect-ES)"
            fi
            # Connect-ES: verify _pb.d.ts files exist (TypeScript declarations)
            if echo "$TGZ_LISTING" | grep -q "_pb\.d\.ts"; then
                pass "Tgz $TGZ_NAME contains _pb.d.ts (Connect-ES)"
            else
                fail "Tgz $TGZ_NAME missing _pb.d.ts files (Connect-ES)"
            fi
            # Connect-ES: verify NO legacy node/ or web/ subdirectories
            if echo "$TGZ_LISTING" | grep -q "/node/\|/web/"; then
                fail "Tgz $TGZ_NAME has legacy node/web dirs (should be flat ESM)"
            else
                pass "Tgz $TGZ_NAME has flat layout (no node/web dirs)"
            fi
        fi

        if echo "$TGZ_PATH" | grep -q "service_b"; then
            if echo "$TGZ_LISTING" | grep -qi "order"; then
                pass "Tgz $TGZ_NAME contains order proto files"
            else
                fail "Tgz $TGZ_NAME missing order proto files"
            fi
            if echo "$TGZ_LISTING" | grep -q "package.json"; then
                pass "Tgz $TGZ_NAME contains package.json"
            else
                fail "Tgz $TGZ_NAME missing package.json"
            fi
            # Connect-ES: verify _pb.js files
            if echo "$TGZ_LISTING" | grep -q "_pb\.js"; then
                pass "Tgz $TGZ_NAME contains _pb.js (Connect-ES)"
            else
                fail "Tgz $TGZ_NAME missing _pb.js files (Connect-ES)"
            fi
        fi

        # Verify package.json has Connect-ES peerDependencies (not legacy google-protobuf)
        PKG_JSON=$(run_in_docker "tar xzf $TGZ_PATH -O --wildcards '*/package.json' 2>/dev/null" 2>/dev/null || echo "")
        if [ -n "$PKG_JSON" ]; then
            if echo "$PKG_JSON" | grep -q '@bufbuild/protobuf'; then
                pass "Tgz $TGZ_NAME package.json has @bufbuild/protobuf peer dep"
            else
                fail "Tgz $TGZ_NAME package.json missing @bufbuild/protobuf peer dep"
            fi
            if echo "$PKG_JSON" | grep -q '"type".*"module"'; then
                pass "Tgz $TGZ_NAME package.json has type=module (ESM)"
            else
                fail "Tgz $TGZ_NAME package.json missing type=module (ESM)"
            fi
            if echo "$PKG_JSON" | grep -q 'google-protobuf'; then
                fail "Tgz $TGZ_NAME package.json still has legacy google-protobuf"
            else
                pass "Tgz $TGZ_NAME package.json has no legacy google-protobuf"
            fi
        fi
    done
else
    echo "  Skipping artifact verification (build did not succeed)"
fi

# ============================================================================
# Phase 9: Verify Local Publishing
# ============================================================================

echo ""
echo "Phase 9: Verify local publishing..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    # --- Java (Maven Local) ---
    echo "  Checking Maven local repository..."
    M2_BASE="$HOME/.m2/repository/com/company/proto"

    if [ -d "$M2_BASE/service-a-proto" ]; then
        pass "Maven: service-a-proto directory exists"

        # Check for JAR
        SA_JAR=$(find "$M2_BASE/service-a-proto" -name "*.jar" 2>/dev/null | head -1)
        if [ -n "$SA_JAR" ]; then
            pass "Maven: service-a-proto JAR exists"
        else
            fail "Maven: service-a-proto JAR not found"
        fi

        # Check for POM
        SA_POM=$(find "$M2_BASE/service-a-proto" -name "*.pom" 2>/dev/null | head -1)
        if [ -n "$SA_POM" ]; then
            pass "Maven: service-a-proto POM exists"

            # Verify POM has correct protobuf-java version (not old hardcoded 3.25.3)
            if grep -q '>3.25.3<' "$SA_POM"; then
                fail "Maven: POM has stale protobuf-java version 3.25.3"
            elif grep -q '>4.33.5<' "$SA_POM"; then
                pass "Maven: POM has correct protobuf-java version"
            else
                fail "Maven: POM protobuf-java version unexpected (neither 4.33.5 nor 3.25.3)"
            fi
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
        # Also check inside the container
        PY_IN_CONTAINER=$(run_in_docker "find /home/protolake/.cache/pip/simple -name '*.whl' 2>/dev/null" 2>/dev/null || echo "")
        if [ -n "$PY_IN_CONTAINER" ]; then
            pass "PyPI: wheel files found in container"
            echo "$PY_IN_CONTAINER" | sed 's/^/    /'
        else
            fail "PyPI: no wheel files found"
        fi
    fi

    # --- JavaScript (npm) ---
    echo "  Checking npm local packages..."
    NPM_BASE="$HOME/.npm"

    # npm file-based publishing copies directories (not tgz) to npm-packages/
    # npm pack mode copies tgz files to npm-packs/
    NPM_PACKAGES=$(run_in_docker "find /home/protolake/.proto-lake/npm-packages -name 'package.json' 2>/dev/null; find /home/protolake/.proto-lake/npm-packs -name '*.tgz' 2>/dev/null" 2>/dev/null || echo "")
    if [ -n "$NPM_PACKAGES" ]; then
        pass "npm: packages found"
        echo "$NPM_PACKAGES" | sed 's/^/    /'
    else
        # Check if npm link was used instead
        NPM_LINKED=$(run_in_docker "npm ls -g --depth=0 2>/dev/null | grep company" 2>/dev/null || echo "")
        if [ -n "$NPM_LINKED" ]; then
            pass "npm: packages found (linked)"
            echo "$NPM_LINKED" | sed 's/^/    /'
        else
            fail "npm: no packages found"
        fi
    fi
else
    echo "  Skipping publish verification (build did not succeed)"
fi

# ============================================================================
# Phase 9.5: Test GetDependencySnippet RPC
# ============================================================================

echo ""
echo "Phase 9.5: Test GetDependencySnippet RPC..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    # Test 1: Get all snippets for service_a (no language filter)
    SNIPPET_RESP=$(grpc_call "protolake.v1.BundleService/GetDependencySnippet" "{
      \"name\": \"lakes/$LAKE_ID/bundles/service_a\"
    }" 2>&1)

    if [ $? -eq 0 ]; then
        pass "GetDependencySnippet RPC success"
    else
        fail "GetDependencySnippet RPC: $SNIPPET_RESP"
    fi

    # Verify Java snippet present with correct coordinates
    if echo "$SNIPPET_RESP" | jq -r '.snippets[].snippet' 2>/dev/null | grep -q "com.company.proto"; then
        pass "Snippet contains Java group_id"
    else
        fail "Snippet missing Java group_id"
    fi
    if echo "$SNIPPET_RESP" | jq -r '.snippets[].snippet' 2>/dev/null | grep -q "service-a-proto"; then
        pass "Snippet contains Java artifact_id"
    else
        fail "Snippet missing Java artifact_id"
    fi

    # Verify JS snippet present
    if echo "$SNIPPET_RESP" | jq -r '.snippets[].snippet' 2>/dev/null | grep -q "@company/service-a-proto"; then
        pass "Snippet contains JS package_name"
    else
        fail "Snippet missing JS package_name"
    fi

    # Test 2: Filter to Java only with Gradle Kotlin format
    JAVA_SNIPPET=$(grpc_call "protolake.v1.BundleService/GetDependencySnippet" "{
      \"name\": \"lakes/$LAKE_ID/bundles/service_a\",
      \"languages\": [\"JAVA\"],
      \"format\": \"GRADLE_KOTLIN\"
    }" 2>&1)

    if echo "$JAVA_SNIPPET" | jq -r '.snippets[0].snippet' 2>/dev/null | grep -q "implementation("; then
        pass "Gradle Kotlin snippet format correct"
    else
        fail "Gradle Kotlin snippet format incorrect"
    fi

    # Test 3: Non-existent bundle returns error
    MISSING_RESP=$(grpc_call "protolake.v1.BundleService/GetDependencySnippet" "{
      \"name\": \"lakes/$LAKE_ID/bundles/nonexistent\"
    }" 2>&1)

    if echo "$MISSING_RESP" | grep -qi "NOT_FOUND\|not found"; then
        pass "Non-existent bundle returns NOT_FOUND"
    else
        fail "Non-existent bundle did not return NOT_FOUND"
    fi
else
    echo "  Skipping snippet tests (build did not succeed)"
fi

# ============================================================================
# Phase 10: Summary & Cleanup
# ============================================================================

echo ""
echo "============================================"
echo " Test Summary"
echo "============================================"
echo "  Passed: $PASS_COUNT"
echo "  Failed: $FAIL_COUNT"
echo "  Total:  $((PASS_COUNT + FAIL_COUNT))"
echo "============================================"

if [ $FAIL_COUNT -gt 0 ]; then
    echo ""
    echo "Some tests failed. Saving docker logs..."
    save_logs
fi

cleanup

if [ $FAIL_COUNT -gt 0 ]; then
    exit 1
else
    echo ""
    echo "All tests passed!"
    exit 0
fi
