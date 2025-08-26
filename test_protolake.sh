#!/bin/bash

# Proto Lake Comprehensive Test Suite - Updated for protolake-gazelle
set -euo pipefail

echo "=== Proto Lake Comprehensive Test Suite ==="
echo "Testing Lake/Bundle services with protolake-gazelle extension"

# Check for required tools
if ! command -v jq &> /dev/null; then
    echo "❌ jq is required but not installed. Please install jq first."
    echo "   On macOS: brew install jq"
    echo "   On Ubuntu: apt-get install jq"
    exit 1
fi

# Configuration
# NOTE: After pushing protolake-gazelle to GitHub, update the PROTOLAKE_GAZELLE_GIT_COMMIT
# in docker-compose.yml with the actual commit SHA for reproducible builds
LAKE_ID="test_lake"
LAKE_OUTPUT_DIR="./test-lake-output"
DOCKER_IMAGE="protolake-proto-lake:latest"
MAVEN_REPO="${HOME}/.m2/repository"
BUILD_FAILED=false

# Helper functions
wait_for_service() {
    echo "Waiting for service to be ready..."
    for i in {1..30}; do
        if curl -sf http://localhost:8085/q/health > /dev/null 2>&1; then
            echo "✅ Service is healthy!"
            return 0
        fi
        echo "   Waiting... ($i/30)"
        sleep 2
    done
    echo "❌ Service failed to start"
    exit 1
}

verify_file() {
    local file=$1
    local desc=$2
    if [ -f "$file" ]; then
        echo "   ✅ $desc exists"
    else
        echo "   ❌ $desc missing"
        exit 1
    fi
}

verify_dir() {
    local dir=$1
    local desc=$2
    if [ -d "$dir" ]; then
        echo "   ✅ $desc exists"
    else
        echo "   ❌ $desc missing"
        exit 1
    fi
}

# Helper to run commands in Docker with proper volume mounts
run_in_docker() {
    # Mount the entire definition_tools directory so relative paths work
    DEFINITION_TOOLS_ABS="$(cd .. && pwd)"
    
    # The workspace will be at the same relative location inside the container
    WORKSPACE_REL="protolake/$LAKE_OUTPUT_DIR/$LAKE_ID"
    
    docker run --rm \
        -v "${DEFINITION_TOOLS_ABS}:/definition_tools" \
        -v "${HOME}/.m2:/home/protolake/.m2" \
        -w "/definition_tools/${WORKSPACE_REL}" \
        --entrypoint /bin/bash \
        ${DOCKER_IMAGE} \
        -c "$1"
}

# Helper to run Bazel commands specifically
run_bazel() {
    run_in_docker "bazel $*"
}

# 0. Clean up and start service
echo "0. Cleaning up and starting service..."
rm -rf "$LAKE_OUTPUT_DIR"
mkdir -p "$LAKE_OUTPUT_DIR"
docker-compose down --remove-orphans
docker-compose build
docker-compose up -d
wait_for_service

# === PHASE 1: Lake Creation ===
echo ""
echo "=== PHASE 1: Lake Creation ==="

# 1. Create Lake using LakeService
echo "1. Creating lake '$LAKE_ID' using LakeService..."
grpcurl -plaintext -d "{
  \"lake\": {
    \"name\": \"$LAKE_ID\",
    \"display_name\": \"Test Proto Lake\",
    \"description\": \"Lake for testing protolake-gazelle functionality\"
  }
}" localhost:9050 protolake.v1.LakeService/CreateLake || { echo "❌ Failed to create lake"; exit 1; }

# Verify lake was created
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/MODULE.bazel" "Lake MODULE.bazel"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/BUILD.bazel" "Lake root BUILD.bazel"
verify_dir "$LAKE_OUTPUT_DIR/$LAKE_ID/tools" "Lake tools directory"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/lake.yaml" "Lake configuration"

echo "   Lake created at: $LAKE_OUTPUT_DIR/$LAKE_ID"

# === PHASE 2: Bundle Creation ===
echo ""
echo "=== PHASE 2: Bundle Creation ==="

# 2. Create bundles using BundleService
echo "2. Creating bundles using BundleService..."

# Bundle 1: service_a
echo "   Creating bundle: service_a"
grpcurl -plaintext -d "{
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
}" localhost:9050 protolake.v1.BundleService/CreateBundle || { echo "❌ Failed to create bundle service_a"; exit 1; }

# Bundle 2: service_b (depends on service_a)
echo "   Creating bundle: service_b"
grpcurl -plaintext -d "{
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
}" localhost:9050 protolake.v1.BundleService/CreateBundle || { echo "❌ Failed to create bundle service_b"; exit 1; }

# Verify bundle structures were created
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/bundle.yaml" "Service A bundle.yaml"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/bundle.yaml" "Service B bundle.yaml"

echo "   ✅ Bundles created successfully"

# === PHASE 3: Copy Proto Files ===
echo ""
echo "=== PHASE 3: Copy Proto Files ==="

echo "3. Copying proto files from test-protos..."

# Copy service_a protos
echo "   Copying service_a protos..."
cp -r test-protos/company_a/platform/service_a/api "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/"
cp -r test-protos/company_a/platform/service_a/types "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/"

# Copy service_b protos
echo "   Copying service_b protos..."
cp -r test-protos/company_b/apps/service_b/api "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/"

# Remove example.proto files that were created by default
echo "   Removing example.proto files..."
rm -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/example.proto"
rm -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/example.proto"

# Verify protos were copied
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/api/v1/user.proto" "Service A user.proto"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/types/v1/common.proto" "Service A common.proto"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/api/v1/order.proto" "Service B order.proto"

echo "   ✅ Proto files copied successfully"

# Show the directory structure
echo ""
echo "Lake directory structure:"
tree "$LAKE_OUTPUT_DIR/$LAKE_ID" -I "bazel-*" || find "$LAKE_OUTPUT_DIR/$LAKE_ID" -type f -name "*.proto" -o -name "*.yaml" | sort

# === PHASE 3.5: Pre-fetch Dependencies ===
echo ""
echo "=== PHASE 3.5: Pre-fetch Dependencies ==="
echo "Pre-fetching bazel dependencies to avoid timeout..."

# Change to lake directory and fetch dependencies
cd "$LAKE_OUTPUT_DIR/$LAKE_ID"
echo "   Running bazel fetch to download dependencies..."
if run_bazel "fetch //..."; then
    echo "   ✅ Dependencies fetched successfully"
else
    echo "   ⚠️  Failed to fetch some dependencies"
fi
cd - > /dev/null

# === PHASE 4: Build Lake ===
echo ""
echo "=== PHASE 4: Build Lake ==="

echo "4. Building lake using BuildLake RPC..."
echo "   This will run gazelle_wrapper and build all bundles..."

# Capture the operation response
BUILD_RESPONSE=$(grpcurl -plaintext -d "{
  \"name\": \"lakes/$LAKE_ID\"
}" localhost:9050 protolake.v1.LakeService/BuildLake 2>&1)

if [ $? -ne 0 ]; then
    echo "❌ Failed to start lake build"
    echo "$BUILD_RESPONSE"
    exit 1
fi

echo "   ✅ Lake build initiated"

# Extract operation name from response
OPERATION_NAME=$(echo "$BUILD_RESPONSE" | jq -r '.name' 2>/dev/null)
if [ -z "$OPERATION_NAME" ] || [ "$OPERATION_NAME" == "null" ]; then
    echo "❌ Failed to get operation name from response"
    echo "Response: $BUILD_RESPONSE"
    exit 1
fi

echo "   Operation: $OPERATION_NAME"

# Wait for operation to complete
echo "   Waiting for build to complete..."
MAX_WAIT=300  # 5 minutes timeout
WAIT_INTERVAL=5
ELAPSED=0

while [ $ELAPSED -lt $MAX_WAIT ]; do
    # Get operation status
    OP_STATUS=$(grpcurl -plaintext -d "{
      \"name\": \"$OPERATION_NAME\"
    }" localhost:9050 google.longrunning.Operations/GetOperation 2>&1)
    
    if [ $? -ne 0 ]; then
        echo "❌ Failed to get operation status"
        echo "$OP_STATUS"
        exit 1
    fi
    
    # Check if operation is done
    IS_DONE=$(echo "$OP_STATUS" | jq -r '.done' 2>/dev/null)
    
    if [ "$IS_DONE" == "true" ]; then
        # Check for errors
        HAS_ERROR=$(echo "$OP_STATUS" | jq -r '.error' 2>/dev/null)
        if [ "$HAS_ERROR" != "null" ]; then
            ERROR_MESSAGE=$(echo "$OP_STATUS" | jq -r '.error.message' 2>/dev/null)
            echo "❌ Build failed: $ERROR_MESSAGE"
            
            # Try to get more details from metadata
            METADATA=$(echo "$OP_STATUS" | jq -r '.metadata' 2>/dev/null)
            if [ "$METADATA" != "null" ]; then
                echo "Build metadata:"
                echo "$METADATA" | jq '.' 2>/dev/null || echo "$METADATA"
            fi
            
            # Show docker logs for debugging
            echo ""
            echo "Docker logs (last 50 lines):"
            docker-compose logs --tail=50 proto-lake
            
            # Set flag to indicate build failed but continue to debugging
            BUILD_FAILED=true
            echo ""
            echo "⚠️  Continuing to debugging phase to diagnose the issue..."
            break
        fi
        
        echo "   ✅ Build completed successfully"
        break
    fi
    
    # Show progress
    CURRENT_PHASE=$(echo "$OP_STATUS" | jq -r '.metadata.currentPhase' 2>/dev/null || echo "unknown")
    echo "   Build in progress... Phase: $CURRENT_PHASE (${ELAPSED}s elapsed)"
    
    sleep $WAIT_INTERVAL
    ELAPSED=$((ELAPSED + WAIT_INTERVAL))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
    echo "❌ Build timed out after ${MAX_WAIT} seconds"
    echo "Last operation status:"
    echo "$OP_STATUS" | jq '.' 2>/dev/null || echo "$OP_STATUS"
    exit 1
fi

# Verify BUILD files were generated
echo ""
echo "5. Verifying generated BUILD files..."

# Check that protolake-gazelle generated bundle BUILD files
if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel" ]; then
    echo "   ✅ Service A bundle BUILD exists"
else
    echo "   ❌ Service A bundle BUILD missing"
    echo "   This may indicate gazelle failed to run or found errors"
    # Don't exit here, let's see what other files were created
fi

if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/BUILD.bazel" ]; then
    echo "   ✅ Service B bundle BUILD exists"
else
    echo "   ❌ Service B bundle BUILD missing"
fi

# Check that standard gazelle generated proto BUILD files
if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/api/v1/BUILD.bazel" ]; then
    echo "   ✅ Service A api/v1 BUILD exists"
else
    echo "   ❌ Service A api/v1 BUILD missing"
fi

if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/types/v1/BUILD.bazel" ]; then
    echo "   ✅ Service A types/v1 BUILD exists"
else
    echo "   ❌ Service A types/v1 BUILD missing"
fi

if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/api/v1/BUILD.bazel" ]; then
    echo "   ✅ Service B api/v1 BUILD exists"
else
    echo "   ❌ Service B api/v1 BUILD missing"
fi

# Show what was generated in the bundle BUILD files
if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel" ]; then
    echo ""
    echo "Generated bundle BUILD file for service_a:"
    echo "----------------------------------------"
    head -30 "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel"
    echo "----------------------------------------"
fi

# === PHASE 4.5: Debug Buf Validation ===
echo ""
echo "=== PHASE 4.5: Debug Buf Validation ==="
if [ "$BUILD_FAILED" = true ]; then
    echo "Build failed during validation phase. Running buf commands manually to diagnose..."
else
    echo "Running buf commands manually for additional verification..."
fi

# Helper to run buf commands
run_buf() {
    run_in_docker "buf $*"
}

echo ""
echo "1. Checking buf version:"
run_buf "--version"

echo ""
echo "2. Running buf build (compilation check):"
echo "   This checks if all protos compile successfully..."
if ! run_buf "build"; then
    echo "   ❌ Buf build failed - see errors above"
else
    echo "   ✅ Buf build succeeded"
fi

echo ""
echo "3. Running buf lint:"
echo "   This checks for style and best practice violations..."
if ! run_buf "lint"; then
    echo "   ❌ Buf lint found issues - see above"
else
    echo "   ✅ Buf lint passed"
fi

echo ""
echo "4. Checking buf.yaml configuration:"
echo "   Current buf.yaml:"
cat "$LAKE_OUTPUT_DIR/$LAKE_ID/buf.yaml"

echo ""
echo "5. Listing proto files buf will validate:"
run_buf "ls-files"

echo ""
echo "6. Running buf breaking (if git history exists):"
echo "   This checks for breaking changes against previous commits..."
if run_buf "breaking --against .git#branch=HEAD~1" 2>/dev/null; then
    echo "   ✅ No breaking changes detected"
else
    echo "   ⚠️  Breaking change detection failed (might be due to no git history)"
fi

echo ""
echo "7. Checking for Bazel-generated files that might cause issues:"
echo "   Looking for .bazel directories:"
find "$LAKE_OUTPUT_DIR/$LAKE_ID" -type d -name ".bazel" | head -10
echo "   Looking for bazel-* directories:"
find "$LAKE_OUTPUT_DIR/$LAKE_ID" -type d -name "bazel-*" | head -10
echo "   Looking for proto files in these directories:"
find "$LAKE_OUTPUT_DIR/$LAKE_ID" -path "*/.bazel/*" -name "*.proto" -o -path "*/bazel-*" -name "*.proto" | head -10

echo ""
echo "8. Running gazelle manually to check for errors:"
echo "   This regenerates BUILD files from proto imports..."
if run_bazel "run //tools:gazelle_wrapper"; then
    echo "   ✅ Gazelle completed successfully"
else
    echo "   ❌ Gazelle failed - see errors above"
fi

# === PHASE 5: Build Verification ===
echo ""
echo "=== PHASE 5: Build Verification ==="

echo "6. Building specific targets to verify..."

# Check if BUILD files exist before trying to build
if [ "$BUILD_FAILED" = true ]; then
    echo "   ⚠️  Skipping build verification - previous build failed"
elif [ ! -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel" ] || 
   [ ! -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/BUILD.bazel" ]; then
    echo "   ⚠️  Skipping build verification - BUILD files not generated"
    echo "   This indicates an issue with gazelle or validation phase"
else
    # Build service_a bundle
    echo "   Building service_a Java bundle..."
    if run_bazel "build //company_a/platform/service_a:service_a_java_bundle"; then
        echo "   ✅ Service A bundle built successfully"
    else
        echo "   ❌ Failed to build Service A bundle"
    fi

    # Build service_b bundle (tests cross-bundle dependencies)
    echo "   Building service_b Java bundle (with service_a dependencies)..."
    if run_bazel "build //company_b/apps/service_b:service_b_java_bundle"; then
        echo "   ✅ Service B bundle built successfully"
    else
        echo "   ❌ Failed to build Service B bundle"
    fi
fi

# List all available targets
echo ""
echo "7. Available targets in the lake:"
if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/BUILD.bazel" ]; then
    echo "   Proto targets:"
    run_bazel "query 'kind(proto_library, //...)'" 2>/dev/null | head -20 || echo "   Failed to query proto targets"

    echo ""
    echo "   Bundle targets:"
    run_bazel "query 'kind(\".*_bundle\", //...)'" 2>/dev/null || echo "   Failed to query bundle targets"
else
    echo "   Skipping target listing - no BUILD files found"
fi

# === PHASE 6: Artifact Inspection ===
echo ""
echo "=== PHASE 6: Artifact Inspection ==="

echo "8. Inspecting generated artifacts..."

# Only try to inspect artifacts if BUILD files exist
if [ -f "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel" ]; then
    # Find and inspect the Java bundle JAR
    BUNDLE_JAR=$(run_bazel "cquery --output=files //company_a/platform/service_a:service_a_java_bundle" 2>/dev/null | grep -E "\.jar$" | head -1)
    if [ -n "$BUNDLE_JAR" ]; then
        echo "   Service A Java bundle JAR contents:"
        run_in_docker "jar tf $BUNDLE_JAR 2>/dev/null | head -20" || echo "   Failed to inspect JAR contents"
    else
        echo "   Could not find bundle JAR (build may have failed)"
    fi
else
    echo "   Skipping artifact inspection - no BUILD files found"
fi

# === PHASE 6.5: Manual Build Testing ===
echo ""
echo "=== PHASE 6.5: Manual Build Testing ==="
echo ""
echo "Testing manual bazel build commands..."

# Change to lake directory
cd "$LAKE_OUTPUT_DIR/$LAKE_ID"

echo ""
echo "1. Testing bazel version:"
run_bazel "--version"

echo ""
echo "2. Testing bazel query for proto targets:"
run_bazel 'query "kind(proto_library, //...)"' 2>/dev/null | head -10 || echo "   Query failed"

echo ""
echo "3. Testing build of a specific bundle target:"
if run_bazel "build //company_a/platform/service_a:service_a_java_bundle --verbose_failures"; then
    echo "   ✅ Manual bazel build succeeded!"
else
    echo "   ❌ Manual bazel build failed"
    echo "   Checking bazel output base:"
    run_bazel "info output_base"
    echo "   Checking for lock files:"
    find . -name "*.lock" -type f | head -10
fi

# Return to original directory
cd - > /dev/null

# === PHASE 7: Manual Testing Instructions ===
echo ""
echo "=== PHASE 7: Manual Testing Instructions ==="
echo ""
echo "The Proto Lake is now running and ready for manual testing!"
echo ""
echo "Lake location: $LAKE_OUTPUT_DIR/$LAKE_ID"
echo ""
echo "To run manual commands:"
echo ""
echo "1. Navigate to the lake directory:"
echo "   cd $LAKE_OUTPUT_DIR/$LAKE_ID"
echo ""
echo "2. Run gazelle_wrapper to regenerate BUILD files:"
echo "   bazel run //tools:gazelle_wrapper"
echo ""
echo "3. Build all targets:"
echo "   bazel build //..."
echo ""
echo "4. Build specific bundles:"
echo "   bazel build //company_a/platform/service_a:service_a_java_bundle"
echo "   bazel build //company_b/apps/service_b:service_b_java_bundle"
echo ""
echo "5. Publish to Maven (using hybrid approach):"
echo "   bazel run //company_a/platform/service_a:publish_to_maven"
echo ""
echo "6. Query available targets:"
echo "   bazel query 'kind(proto_library, //...)'"
echo "   bazel query 'kind(\".*_bundle\", //...)'"
echo ""
echo "7. Test protolake-gazelle directly:"
echo "   bazel run //:gazelle-protolake"
echo ""
echo "8. Inspect bundle contents:"
echo "   jar tf bazel-bin/company_a/platform/service_a/service_a_java_bundle.jar"
echo ""
echo "9. View service logs:"
echo "   docker-compose logs -f proto-lake"
echo ""
echo "Services are still running. Press Ctrl+C to stop them."
echo ""

# Keep the script running so services stay up
trap "echo ''; echo 'Stopping services...'; docker-compose down" EXIT
while true; do sleep 1; done
