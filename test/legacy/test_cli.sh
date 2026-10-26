#!/bin/bash

# Proto Lake CLI End-to-End Test Suite
# Tests the full CLI pipeline: init -> create-bundle -> build -> publish
# Uses protolakew wrapper script (the real user workflow)

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

LAKE_NAME="test_lake"
OUTPUT_DIR="./test-cli-output"
# PROTOLAKE_E2E_IMAGE builds and runs a branch-scoped tag instead, leaving the
# shared tag other checkouts run against alone.
DOCKER_IMAGE="${PROTOLAKE_E2E_IMAGE:-protolake-proto-lake:latest}"
GAZELLE_SOURCE_PATH="${PROTOLAKE_GAZELLE_SOURCE_PATH:-$(cd ../../protolake-gazelle 2>/dev/null && pwd)}"
BUILD_TIMEOUT=1200  # 20 minutes for cold build
BASE_VOLUME=""  # the scratch local root's bazel output base, named by protolakew
BUILD_PID=""
SUITE_DONE=false  # set once the summary prints; cleanup keeps the output otherwise

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
        # A --rm container's own auto-removal can still hold the volume for a moment.
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            docker volume inspect "$BASE_VOLUME" >/dev/null 2>&1 || break
            docker volume rm "$BASE_VOLUME" >/dev/null 2>&1 && break
            sleep 1
        done
    fi
    # Only a run that finished clean removes its output; any other keeps it for the logs.
    if [ "$SUITE_DONE" = true ] && [ "$FAIL_COUNT" -eq 0 ]; then rm -rf "$OUTPUT_DIR"; fi
}

# Every exit cleans up, a failed or interrupted run included.
trap cleanup EXIT
trap 'exit 130' INT TERM

# ============================================================================
# Phase 0: Prerequisites
# ============================================================================

echo "============================================"
echo " Proto Lake CLI End-to-End Test Suite"
echo "============================================"
echo ""
echo "Phase 0: Checking prerequisites..."

PREREQ_FAIL=false
for cmd in docker jq unzip; do
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

# Clean previous state
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

echo "  Building Docker image $DOCKER_IMAGE..."
if ! docker build -t "$DOCKER_IMAGE" .. 2>&1 | tail -5; then
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
# Every protolakew call below shares this scratch local root, so the publish
# checks see only this run's artifacts and the developer's ~/.m2, pip cache
# and protolake db stay untouched.
LOCAL_ROOT="$ABS_OUTPUT_DIR/local-root"

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

# Create service_b
echo "  Creating service_b..."
SB_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" create-bundle --name service_b \
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

# Create api_demo: REST bindings, a WebSocket stream and a custom RPC, for the API specs
echo "  Creating api_demo..."
AD_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" create-bundle --name api_demo \
    --java-artifact-id api-demo-proto \
    --python-package-name company_api_demo_proto \
    --js-package-name @company/api-demo-proto 2>&1)
AD_EXIT=$?

if [ $AD_EXIT -eq 0 ]; then
    pass "create-bundle api_demo exit code 0"
else
    fail "create-bundle api_demo exit code $AD_EXIT"
    echo "  Output:"
    echo "$AD_OUTPUT" | tail -10 | sed 's/^/    /'
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

cp -r fixtures/test-protos/company_a/platform/service_a/api "$LAKE_DIR/company_a/platform/service_a/"
cp -r fixtures/test-protos/company_a/platform/service_a/types "$LAKE_DIR/company_a/platform/service_a/"
cp -r fixtures/test-protos/company_b/apps/service_b/api "$LAKE_DIR/company_b/apps/service_b/"
cp -r fixtures/test-protos/api_demo/v1 "$LAKE_DIR/api_demo/"
# api_demo's options: protolake's own cohub/api/v1/exposure.proto, held by the
# lake as a lake that vendors the file would hold it.
mkdir -p "$LAKE_DIR/cohub/api/v1"
cp ../src/main/proto/cohub/api/v1/exposure.proto "$LAKE_DIR/cohub/api/v1/"

# Remove auto-generated example.proto files
rm -f "$LAKE_DIR/company_a/platform/service_a/example.proto"
rm -f "$LAKE_DIR/company_b/apps/service_b/example.proto"
rm -f "$LAKE_DIR/api_demo/example.proto"

check_file "$LAKE_DIR/company_a/platform/service_a/api/v1/user.proto" "service_a user.proto"
check_file "$LAKE_DIR/company_a/platform/service_a/types/v1/common.proto" "service_a common.proto"
check_file "$LAKE_DIR/company_b/apps/service_b/api/v1/order.proto" "service_b order.proto"
check_file "$LAKE_DIR/api_demo/v1/things.proto" "api_demo things.proto"

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
# protolakew skips npm publishing unless asked; file mode publishes into the
# local root's npm-packages, which Phase 7 checks.
export NPM_PUBLISH_MODE=file

# Run build with timeout tracking
BUILD_LOG="${ABS_OUTPUT_DIR}/build.log"
(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" build --install-local --skip-validation) > "$BUILD_LOG" 2>&1 &
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

# The build seeds the release-please scaffolding. A release PR must not merge
# before its branch carries the regenerated BUILD files, so release-please
# opens release PRs as drafts, the release-please job holds a ready one before
# release-please can move it, and every release PR whose branch tip is not the
# regenerate job's commit (it carries a Regenerated-by trailer, made even when
# nothing changed), and every one release-please reports, goes back to draft
# and is regenerated. The job readies a release PR only from its drafts step,
# after release-please succeeded with a report the job trusts, and never one
# release-please reported. Both lookups page through every open PR with the
# REST API and filter release PRs in jq: a --label list reads the search
# index, which lags. Neither passes a fork PR on to checkout or push.
echo ""
echo "  Release-please scaffolding:"
check_file_contains "$LAKE_DIR/release-please-config.json" '"draft-pull-request" *: *true' "release-please opens release PRs as drafts"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'gh pr ready --undo' "release.yml holds a ready release PR as a draft"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'gh api --paginate "repos/.*/pulls?state=open&per_page=100"' "release.yml pages through every open PR without the search index"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'select(.head.repo.full_name == env.GITHUB_REPOSITORY)' "release.yml keeps fork PRs out of checkout and push"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'select(any(.labels\[\]; .name == "autorelease: pending") and (.draft | not))' "release.yml holds every ready release PR"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'REPORTED_NUMBERS: .*fromJSON(steps.release.outputs.prs' "release.yml regenerates each release PR release-please reports, from its number and branch only"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'any(. == "Regenerated-by: release-workflow")' "release.yml judges each release PR by its branch tip's commit"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'commits/heads/$branch' "release.yml reads the branch tip, not the PR's recorded head"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" '\[ "$draft" = true \] && ! gh pr ready "$pr"' "release.yml readies a release PR whose branch tip is regenerated"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'jq -nc "$REPORTED_PRS"' "release.yml judges release-please's report by one definition"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'REPORTED_ENTRIES: .*join(fromJSON(steps.release.outputs.prs' "release.yml counts every entry of release-please's report"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'REPORTED_NULLS: .*contains(fromJSON(steps.release.outputs.prs' "release.yml catches a null entry in release-please's report"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" '\[ "$trusted" = false \] ||' "release.yml regenerates every release PR after a report it cannot trust"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" '"$reported" | jq -e --argjson n "$pr"' "release.yml regenerates each release PR release-please reports outright"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" '\[ "$draft" = false \] && ! gh pr ready --undo "$pr"' "release.yml puts a release PR that is not regenerated back to draft"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'select(any($open\[\]; .number == $n) | not)' "release.yml drops a reported PR that is not open on this repository"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'gh api "repos/$GITHUB_REPOSITORY/pulls/$pr"' "release.yml looks up a reported PR the list missed"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'del(.draft, .release)]' "release.yml regenerates every release PR that is not regenerated"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" 'git commit --allow-empty' "release.yml commits the regenerated files even when nothing changed"
check_file_contains "$LAKE_DIR/.github/workflows/release.yml" '"Regenerated-by: release-workflow"$' "release.yml marks the regenerate commit with the Regenerated-by trailer"

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
    M2_BASE="$LOCAL_ROOT/maven/repository/com/example/proto"

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
    PYPI_BASE="$LOCAL_ROOT/pip/simple"

    PY_PACKAGES=$(find "$PYPI_BASE" -name "*.whl" 2>/dev/null || echo "")
    if [ -n "$PY_PACKAGES" ]; then
        pass "PyPI: wheel files found in local index"
        echo "$PY_PACKAGES" | sed 's/^/    /'
    else
        fail "PyPI: no wheel files found"
    fi

    # --- JavaScript (npm file mode) ---
    echo "  Checking npm local packages..."
    NPM_PKG_BASE="$LOCAL_ROOT/npm-packages"

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
    DEP_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" dep show service_a 2>&1)
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

    # Test 2: --plain mode: succeeds and prints the snippet without the [protolake] prefix
    PLAIN_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" dep show service_a --plain 2>&1)
    PLAIN_EXIT=$?
    if [ $PLAIN_EXIT -eq 0 ] && echo "$PLAIN_OUTPUT" | grep -q "service-a-proto" \
        && ! echo "$PLAIN_OUTPUT" | grep -q "\[protolake\]"; then
        pass "dep show --plain prints the bare snippet"
    else
        fail "dep show --plain (exit $PLAIN_EXIT): no bare snippet in the output"
    fi

    # Test 3: --json mode: succeeds and carries a non-empty snippets list
    # Filter out Quarkus startup logs (stderr mixed in by Docker) — extract only the JSON object
    JSON_OUTPUT=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" dep show service_a --json 2>/dev/null)
    JSON_EXIT=$?
    if [ $JSON_EXIT -eq 0 ] && echo "$JSON_OUTPUT" | sed -n '/^{/,/^}/p' | jq -e '.snippets | length > 0' > /dev/null 2>&1; then
        pass "dep show --json produces a snippets list"
    else
        fail "dep show --json (exit $JSON_EXIT): no snippets list in the output"
    fi

    # Test 4: --lang filter
    JAVA_ONLY=$(cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" dep show service_a --lang java --plain 2>&1)
    if echo "$JAVA_ONLY" | grep -q "groupId\|implementation("; then
        pass "dep show --lang java shows Java snippet"
    else
        fail "dep show --lang java missing Java snippet"
    fi
else
    echo "  Skipping dep show tests (build did not succeed)"
fi

# ============================================================================
# Phase 7.6: Verify API specs
# ============================================================================

echo ""
echo "Phase 7.6: Verify API specs..."

# Prints a file of the build's bazel-bin. The specs are Bazel outputs, and the
# output base lives in a docker volume, not on this host.
bazel_bin_file() {
    docker run --rm --entrypoint sh -v "$BASE_VOLUME:/home/protolake/.cache/bazel" "$DOCKER_IMAGE" \
        -c "cat /home/protolake/.cache/bazel/execroot/_main/bazel-out/*-fastbuild/bin/$1"
}

# check_json <file> <jq filter printing true> <description>
check_json() {
    if [ -s "$1" ] && [ "$(jq -r "$2" "$1" 2>/dev/null)" = "true" ]; then
        pass "$3"
    else
        fail "$3 ($2 on $1)"
    fi
}

SPECS_DIR="$ABS_OUTPUT_DIR/api-specs"
if [ "$BUILD_SUCCEEDED" = true ] && [ -n "$BASE_VOLUME" ]; then
    mkdir -p "$SPECS_DIR"
    OPENAPI="$SPECS_DIR/api_demo.openapi.json"
    ASYNCAPI="$SPECS_DIR/api_demo.asyncapi.json"
    FINDINGS="$SPECS_DIR/api_demo_api_findings.json"
    SA_FINDINGS="$SPECS_DIR/service_a_api_findings.json"
    bazel_bin_file api_demo/api_demo_api_specs/api_demo.openapi.json > "$OPENAPI"
    bazel_bin_file api_demo/api_demo_api_specs/api_demo.asyncapi.json > "$ASYNCAPI"
    bazel_bin_file api_demo/api_demo_api_findings.json > "$FINDINGS"
    bazel_bin_file company_a/platform/service_a/service_a_api_findings.json > "$SA_FINDINGS"
    AD_VERSION=$(sed -n 's/^version: *"\{0,1\}\([^" ]*\)"\{0,1\}.*/\1/p' "$LAKE_DIR/api_demo/bundle.yaml" | head -1)

    check_file_contains "$BUILD_LOG" "Writing API specs: OK - api_demo (api_demo.openapi.json, api_demo.asyncapi.json)" \
        "build log names api_demo's specs"

    # OpenAPI 2.0, from the google.api.http bindings
    check_json "$OPENAPI" '.swagger == "2.0"' "OpenAPI: a Swagger 2.0 document"
    check_json "$OPENAPI" '.info.title == "api_demo"' "OpenAPI: titled after the bundle"
    check_json "$OPENAPI" '.paths["/v1/{name}"].get.operationId == "ThingService_GetThing"' \
        "OpenAPI: the REST-bound unary RPC"
    check_json "$OPENAPI" '.paths["/v1/things:watch"].get.responses["200"]
        | .schema["$ref"] == "#/definitions/v1ThingEvent" and (.schema | has("properties") | not)
          and ."x-stream-framing" == "newline-delimited"' \
        "OpenAPI: the server stream is newline-delimited bare ThingEvent messages"
    check_json "$OPENAPI" '.paths["/v1/{name}:export"].post.operationId == "ThingService_ExportThing"' \
        "OpenAPI: the custom RPC is in the spec"
    check_json "$OPENAPI" '.paths["/v1/{name}:tail"].get.responses["200"]
        | ."x-stream-framing" == "author-defined" and .schema.properties.result["$ref"] == "#/definitions/v1ThingLogLine"' \
        "OpenAPI: the custom server stream keeps the generator's framing, marked author-defined"
    check_json "$OPENAPI" '.paths | has("/v1/{name}:upload") | not' \
        "OpenAPI: the client stream's binding is left out"
    check_json "$OPENAPI" '[.. | .operationId? // empty]
        | index("ThingService_PingThings") == null and index("ThingService_CollaborateOnThing") == null' \
        "OpenAPI: neither the unannotated nor the WebSocket RPC"
    check_json "$OPENAPI" '.definitions | has("v1PingThingsResponse") | not' \
        "OpenAPI: none of the unannotated RPC's messages"

    # AsyncAPI 3.1, for the WebSocket-marked RPCs
    CHANNEL=api_demo-v1-ThingService-CollaborateOnThing
    check_json "$ASYNCAPI" '.asyncapi == "3.1.0"' "AsyncAPI: a 3.1 document"
    check_json "$ASYNCAPI" ".channels | keys == [\"$CHANNEL\"]" "AsyncAPI: only the WebSocket RPC has a channel"
    check_json "$ASYNCAPI" ".channels[\"$CHANNEL\"].address == \"/ws/api_demo.v1.ThingService/CollaborateOnThing\"" \
        "AsyncAPI: the channel sits at the bridge's address"
    check_json "$ASYNCAPI" ".channels[\"$CHANNEL\"].messages
        | .request.payload.properties.data[\"\$ref\"] == \"#/components/schemas/api_demo.v1.CollaborateOnThingRequest\"
          and .response.payload.properties.data[\"\$ref\"] == \"#/components/schemas/api_demo.v1.CollaborateOnThingResponse\"
          and .end[\"\$ref\"] == \"#/components/messages/End\" and .status[\"\$ref\"] == \"#/components/messages/Status\"" \
        "AsyncAPI: the channel carries the bridge's Data, End and Status frames"
    check_json "$ASYNCAPI" ".channels[\"$CHANNEL\"].bindings.ws.headers.required == [\"Sec-WebSocket-Protocol\"]" \
        "AsyncAPI: the WebSocket binding declares the subprotocol handshake"
    check_json "$ASYNCAPI" '.components.schemas | has("api_demo.v1.PingThingsResponse") | not' \
        "AsyncAPI: none of the unannotated RPC's messages"
    check_json "$FINDINGS" '.asyncapi_generator | startswith("protoc-gen-connect-openapi")' \
        "AsyncAPI: protoc-gen-connect-openapi's output passed protolake's check"
    check_json "$FINDINGS" '[.findings[] | [.severity, .rpc]] == [["WARNING", "api_demo.v1.ThingService.UploadThingChunks"]]' \
        "the client stream's binding is the one finding, a WARNING"
    check_file_contains "$BUILD_LOG" "WARNING: api_demo/v1/things.proto:" "the build prints the WARNING with its location"

    # service_a has no bindings and no WebSocket RPCs: no specs, which is fine
    check_json "$SA_FINDINGS" '.findings == [] and .specs == []' "service_a gets no API specs"

    # The specs ship inside api_demo's packages, stamped with the version each is published under
    AD_JAR=$(find "$LOCAL_ROOT/maven/repository/com/example/proto/api-demo-proto" -name "*.jar" 2>/dev/null | head -1)
    AD_WHEEL=$(find "$LOCAL_ROOT/pip/simple" -name "company_api_demo_proto-*.whl" 2>/dev/null | head -1)
    AD_NPM_SPEC=$(find "$LOCAL_ROOT/npm-packages" -path "*api-demo-proto*" -name "api_demo.openapi.json" 2>/dev/null | head -1)
    PACKAGED="$SPECS_DIR/packaged"
    mkdir -p "$PACKAGED"
    if [ -n "$AD_JAR" ]; then
        unzip -p "$AD_JAR" META-INF/api-specs/api_demo.openapi.json > "$PACKAGED/jar.openapi.json" 2>/dev/null
        unzip -p "$AD_JAR" META-INF/api-specs/api_demo.asyncapi.json > "$PACKAGED/jar.asyncapi.json" 2>/dev/null
    fi
    if [ -n "$AD_WHEEL" ]; then
        unzip -p "$AD_WHEEL" "*.dist-info/api-specs/api_demo.openapi.json" > "$PACKAGED/wheel.openapi.json" 2>/dev/null
        unzip -p "$AD_WHEEL" "*.dist-info/RECORD" > "$PACKAGED/wheel.RECORD" 2>/dev/null
    fi
    [ -n "$AD_NPM_SPEC" ] && cp "$AD_NPM_SPEC" "$PACKAGED/npm.openapi.json"
    check_json "$PACKAGED/jar.openapi.json" ".info.version == \"$AD_VERSION\" and (.paths | has(\"/v1/{name}\"))" \
        "jar: META-INF/api-specs/api_demo.openapi.json, version $AD_VERSION"
    check_json "$PACKAGED/jar.asyncapi.json" ".info.version == \"$AD_VERSION\" and .asyncapi == \"3.1.0\"" \
        "jar: META-INF/api-specs/api_demo.asyncapi.json, version $AD_VERSION"
    check_json "$PACKAGED/wheel.openapi.json" ".info.version == \"$AD_VERSION\"" \
        "wheel: .dist-info/api-specs/api_demo.openapi.json, version $AD_VERSION"
    check_file_contains "$PACKAGED/wheel.RECORD" "dist-info/api-specs/api_demo.openapi.json,sha256=" \
        "wheel: RECORD lists the spec with its hash"
    check_json "$PACKAGED/npm.openapi.json" ".info.version == \"$AD_VERSION\"" \
        "npm: api-specs/api_demo.openapi.json, version $AD_VERSION"
    # service_a has no specs, so its jar carries none
    SA_JAR_FILE=$(find "$LOCAL_ROOT/maven/repository/com/example/proto/service-a-proto" -name "*.jar" 2>/dev/null | head -1)
    if [ -n "$SA_JAR_FILE" ] && ! unzip -l "$SA_JAR_FILE" | grep -q "api-specs/"; then
        pass "service_a's jar carries no API specs"
    else
        fail "service_a's jar carries API specs, or is missing ($SA_JAR_FILE)"
    fi
else
    echo "  Skipping API spec checks (build did not succeed)"
fi

# ============================================================================
# Phase 7.7: The WebSocket option on a unary RPC fails the build
# ============================================================================

echo ""
echo "Phase 7.7: The WebSocket option on a unary RPC fails the build..."

if [ "$BUILD_SUCCEEDED" = true ]; then
    THINGS="$LAKE_DIR/api_demo/v1/things.proto"
    # GetThing is unary; gRPC-web serves it to browsers, so the option is a mistake.
    awk 'index($0, "{get: \"/v1/{name=things/*}\"}") { print; print "    option (cohub.api.v1.exposure) = {websocket: true};"; next } { print }' \
        "$THINGS" > "$THINGS.tmp" && mv "$THINGS.tmp" "$THINGS"
    if [ "$(grep -c 'websocket: true' "$THINGS")" -eq 2 ]; then
        pass "GetThing carries the WebSocket option beside CollaborateOnThing"
    else
        fail "GetThing does not carry the WebSocket option"
    fi
    NEG_LOG="${ABS_OUTPUT_DIR}/build-websocket-on-unary.log"
    (cd "$LAKE_DIR" && ./protolakew --local-root "$LOCAL_ROOT" build --skip-validation) > "$NEG_LOG" 2>&1
    NEG_EXIT=$?
    if [ $NEG_EXIT -ne 0 ]; then
        pass "the build fails (exit $NEG_EXIT)"
    else
        fail "the build succeeded with the WebSocket option on a unary RPC"
    fi
    check_file_contains "$NEG_LOG" "API exposure checks failed" "the failure names the exposure checks"
    check_file_contains "$NEG_LOG" "api_demo.v1.ThingService.GetThing is a unary RPC" "the error names the RPC"
    check_file_contains "$NEG_LOG" "api_demo/v1/things.proto:" "the error points at the proto file"
else
    echo "  Skipping the negative build (the first build did not succeed)"
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

SUITE_DONE=true
if [ $FAIL_COUNT -gt 0 ]; then
    exit 1
else
    echo ""
    echo "All CLI tests passed!"
    exit 0
fi
