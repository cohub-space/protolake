#!/bin/bash

# Proto Lake Comprehensive Test Suite - Updated for protolake-gazelle
set -euo pipefail

echo "=== Proto Lake Comprehensive Test Suite ==="
echo "Testing Lake/Bundle services with protolake-gazelle extension"

# Configuration
LAKE_ID="test_lake"
LAKE_OUTPUT_DIR="./test-lake-output"
DOCKER_IMAGE="protolake-proto-lake:latest"
MAVEN_REPO="${HOME}/.m2/repository"

# Helper functions
wait_for_service() {
    echo "Waiting for service to be ready..."
    for i in {1..30}; do
        if curl -sf http://localhost:8080/q/health > /dev/null 2>&1; then
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
        -v "bazel-disk-cache:/home/protolake/.cache/bazel-disk-cache" \
        -v "bazel-output-base:/home/protolake/.cache/bazel" \
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
}" localhost:9090 protolake.v1.LakeService/CreateLake || { echo "❌ Failed to create lake"; exit 1; }

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
}" localhost:9090 protolake.v1.BundleService/CreateBundle || { echo "❌ Failed to create bundle service_a"; exit 1; }

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
}" localhost:9090 protolake.v1.BundleService/CreateBundle || { echo "❌ Failed to create bundle service_b"; exit 1; }

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

# Verify protos were copied
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/api/v1/user.proto" "Service A user.proto"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/types/v1/common.proto" "Service A common.proto"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/api/v1/order.proto" "Service B order.proto"

echo "   ✅ Proto files copied successfully"

# Show the directory structure
echo ""
echo "Lake directory structure:"
tree "$LAKE_OUTPUT_DIR/$LAKE_ID" -I "bazel-*" || find "$LAKE_OUTPUT_DIR/$LAKE_ID" -type f -name "*.proto" -o -name "*.yaml" | sort

# === PHASE 4: Build Lake ===
echo ""
echo "=== PHASE 4: Build Lake ==="

echo "4. Building lake using BuildLake RPC..."
echo "   This will run gazelle_wrapper and build all bundles..."

grpcurl -plaintext -d "{
  \"name\": \"lakes/$LAKE_ID\"
}" localhost:9090 protolake.v1.LakeService/BuildLake || { echo "❌ Failed to build lake"; exit 1; }

echo "   ✅ Lake build initiated"

# Wait a bit for the build to complete (since it's a long-running operation)
echo "   Waiting for build to complete..."
sleep 10

# Verify BUILD files were generated
echo ""
echo "5. Verifying generated BUILD files..."

# Check that protolake-gazelle generated bundle BUILD files
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel" "Service A bundle BUILD"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/BUILD.bazel" "Service B bundle BUILD"

# Check that standard gazelle generated proto BUILD files
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/api/v1/BUILD.bazel" "Service A api/v1 BUILD"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/types/v1/BUILD.bazel" "Service A types/v1 BUILD"
verify_file "$LAKE_OUTPUT_DIR/$LAKE_ID/company_b/apps/service_b/api/v1/BUILD.bazel" "Service B api/v1 BUILD"

# Show what was generated in the bundle BUILD files
echo ""
echo "Generated bundle BUILD file for service_a:"
echo "----------------------------------------"
head -30 "$LAKE_OUTPUT_DIR/$LAKE_ID/company_a/platform/service_a/BUILD.bazel"
echo "----------------------------------------"

# === PHASE 5: Build Verification ===
echo ""
echo "=== PHASE 5: Build Verification ==="

echo "6. Building specific targets to verify..."

# Build service_a bundle
echo "   Building service_a Java bundle..."
run_bazel "build //company_a/platform/service_a:service_a_java_bundle"

# Build service_b bundle (tests cross-bundle dependencies)
echo "   Building service_b Java bundle (with service_a dependencies)..."
run_bazel "build //company_b/apps/service_b:service_b_java_bundle"

echo "   ✅ All bundles built successfully"

# List all available targets
echo ""
echo "7. Available targets in the lake:"
echo "   Proto targets:"
run_bazel "query 'kind(proto_library, //...)'" | head -20

echo ""
echo "   Bundle targets:"
run_bazel "query 'kind(\".*_bundle\", //...)'"

# === PHASE 6: Artifact Inspection ===
echo ""
echo "=== PHASE 6: Artifact Inspection ==="

echo "8. Inspecting generated artifacts..."

# Find and inspect the Java bundle JAR
BUNDLE_JAR=$(run_bazel "cquery --output=files //company_a/platform/service_a:service_a_java_bundle" 2>/dev/null | grep -E "\.jar$" | head -1)
if [ -n "$BUNDLE_JAR" ]; then
    echo "   Service A Java bundle JAR contents:"
    run_in_docker "jar tf $BUNDLE_JAR | head -20"
else
    echo "   Could not find bundle JAR"
fi

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
