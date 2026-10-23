#!/bin/bash

# Proto Lake foreign-owner suite: protolake's git works in a lake another user owns.
#
# protolake runs as uid 1001 in its image, while a bind-mounted lake belongs to the host
# user (Docker Desktop's VirtioFS reports a mount's root as root), and git refuses a
# repository another user owns. The suite builds that mismatch on the container's own
# filesystem, so it holds on every host: before each step root hands the whole lake to
# uid 4242, and each protolake command then runs as uid 1001.
#
# PROTOLAKE_TEST_IMAGE=<image> tests an existing image instead of building this tree's.

set -uo pipefail

# Run from test/, where the stack's docker-compose.yml lives (it builds the image).
cd "$(dirname "$0")/.."

IMAGE="${PROTOLAKE_TEST_IMAGE:-protolake-proto-lake:latest}"
PASS_COUNT=0
FAIL_COUNT=0

echo "============================================"
echo " Proto Lake Foreign-Owner Test Suite"
echo "============================================"
echo ""
echo "Phase 0: Checking prerequisites..."
if ! command -v docker &> /dev/null; then
    echo "  MISSING: docker"
    exit 1
fi
echo "  Found: docker"

echo ""
if [ -n "${PROTOLAKE_TEST_IMAGE:-}" ]; then
    echo "Phase 1: Using image $IMAGE (PROTOLAKE_TEST_IMAGE)"
    if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
        echo "  Image not found: $IMAGE"
        exit 1
    fi
else
    echo "Phase 1: Docker build..."
    if ! docker compose build 2>&1 | tail -5; then
        echo "  FAIL: Docker build"
        exit 1
    fi
fi

echo ""
echo "Phase 2: init, create-bundle and validate in a lake uid 4242 owns..."

# The steps print their own PASS/FAIL lines; a run that never reaches the end marker
# (the container did not start, the script died) fails as a whole.
OUTPUT=$(docker run --rm -i -u 0 --entrypoint bash "$IMAGE" -s 2>&1 <<'STEPS'
set -u
OWNER=4242
LAKE=/proto-lake/owned_lake
BUNDLE_PROTO="$LAKE/acme/svc/example.proto"

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; }
show() { printf '%s\n' "$1" | grep -E 'fatal:|dubious|Exception|Failure|ERROR|\[protolake\]|could not|changed type|\.proto:' | tail -12 | sed 's/^/      /'; }

# The image's user, the way protolakew runs protolake. The script itself arrives on
# stdin, so no command may read it.
as_protolake() { setpriv --reuid 1001 --regid 1001 --init-groups env HOME=/home/protolake PROTO_LAKE_BASE_PATH=/proto-lake LC_ALL=C "$@" </dev/null; }
protolake() { as_protolake java -jar /deployments/app.jar "$@"; }
# Another user owns everything, as the host user owns a checkout; uid 1001 keeps
# write access, which protolake needs, but never ownership.
hand_over() { chown -R "$OWNER:$OWNER" "$1"; chmod -R a+rwX "$1"; }
# The suite's own reads and edits, as root, trusting only the lake.
lake_git() { git -c safe.directory="$LAKE" -C "$LAKE" "$@"; }

steps() {
    # The premise: plain git as uid 1001 refuses a repository uid 4242 owns, so the
    # steps below test protolake's trust, not a runtime that ignores ownership.
    mkdir -p /proto-lake/premise && git -C /proto-lake/premise init -q
    hand_over /proto-lake/premise
    OUT=$(as_protolake git -C /proto-lake/premise status 2>&1); RC=$?
    if [ $RC -ne 0 ] && printf '%s' "$OUT" | grep -q "dubious ownership"; then
        pass "plain git as uid 1001 refuses a repository uid 4242 owns"
    else
        fail "plain git as uid 1001 did not refuse a repository uid 4242 owns (exit $RC)"
    fi

    mkdir -p "$LAKE"
    hand_over "$LAKE"
    OUT=$(protolake init --name owned_lake 2>&1); RC=$?
    if [ $RC -eq 0 ]; then pass "init succeeds in a lake uid 4242 owns"; else fail "init exit code $RC"; show "$OUT"; return; fi
    hand_over "$LAKE"
    if lake_git log --format=%s 2>/dev/null | grep -q "^Initialize ProtoLake: owned_lake$"; then
        pass "init committed the lake (git init, config, add, commit)"
    else
        fail "init commit missing"
    fi

    OUT=$(protolake create-bundle --name svc --bundle-prefix acme --lake-path "$LAKE" 2>&1); RC=$?
    if [ $RC -eq 0 ]; then pass "create-bundle succeeds"; else fail "create-bundle exit code $RC"; show "$OUT"; return; fi
    hand_over "$LAKE"
    if lake_git log --format=%s 2>/dev/null | grep -q "^Add bundle: svc$"; then
        pass "create-bundle committed the bundle"
    else
        fail "bundle commit missing"
    fi
    if [ -z "$(lake_git status --porcelain 2>&1)" ]; then
        pass "the lake's tree is clean after create-bundle"
    else
        fail "uncommitted changes after create-bundle: $(lake_git status --porcelain 2>&1 | head -5 | tr '\n' ' ')"
    fi

    # A breaking change against the previous commit: validate finds it only when it
    # can read the lake's history (git rev-parse, merge-base) and buf can clone the
    # lake's own .git, which another user owns too.
    lake_git -c user.name=suite -c user.email=suite@localhost commit -q --allow-empty -m "suite: baseline"
    sed -i 's/string id = 1;/int64 id = 1;/' "$BUNDLE_PROTO"
    hand_over "$LAKE"
    OUT=$(protolake validate --lake-path "$LAKE" 2>&1); RC=$?
    if [ $RC -ne 0 ] && printf '%s' "$OUT" | grep -q 'changed type from "string" to "int64"'; then
        pass "validate reports a breaking change against the lake's history"
    else
        fail "validate did not report the breaking change (exit $RC)"; show "$OUT"
    fi

    lake_git checkout -q -- "$BUNDLE_PROTO"
    hand_over "$LAKE"
    OUT=$(protolake validate --lake-path "$LAKE" 2>&1); RC=$?
    if [ $RC -eq 0 ]; then pass "validate passes on the unchanged lake"; else fail "validate exit code $RC"; show "$OUT"; fi

    # A relative HOME stops the breaking check before buf clones the lake: buf's git would
    # resolve it against a temporary directory, and the git serving the clone inside the
    # lake's .git. buf's own cache stays absolute, so the run gets as far as the check.
    hand_over "$LAKE"
    OUT=$(as_protolake HOME=protolake-home BUF_CACHE_DIR=/home/protolake/.cache/buf \
        java -jar /deployments/app.jar validate --lake-path "$LAKE" 2>&1); RC=$?
    if [ $RC -ne 0 ] && printf '%s' "$OUT" | grep -q "HOME is 'protolake-home'" \
        && printf '%s' "$OUT" | grep -q "absolute path"; then
        pass "validate refuses a relative HOME before buf clones the lake"
    else
        fail "validate did not refuse a relative HOME (exit $RC)"; show "$OUT"
    fi
}

steps
echo "@@steps-done"
STEPS
)
echo "$OUTPUT" | grep -v "^@@steps-done$"
PASS_COUNT=$(printf '%s\n' "$OUTPUT" | grep -c "^  PASS: ")
FAIL_COUNT=$(printf '%s\n' "$OUTPUT" | grep -c "^  FAIL: ")
if ! printf '%s\n' "$OUTPUT" | grep -q "^@@steps-done$"; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo "  FAIL: the steps did not run to the end"
fi

echo ""
echo "============================================"
echo " Foreign-Owner Test Summary"
echo "============================================"
echo "  Passed: $PASS_COUNT"
echo "  Failed: $FAIL_COUNT"
echo "  Total:  $((PASS_COUNT + FAIL_COUNT))"
echo "============================================"

if [ "$FAIL_COUNT" -gt 0 ]; then
    exit 1
fi
echo ""
echo "All foreign-owner tests passed!"
exit 0
