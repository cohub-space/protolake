#!/usr/bin/env bash
# CoHub board e2e — entry point.
# Usage: ./test/run.sh [smoke|e2e|all]   (default: smoke)
#
# Brings up the protolake stack via test/docker-compose.yml, runs Karate
# scenarios tagged @smoke or @e2e against it, tears down on exit.
#
# protolake is an EXTERNAL repo (not VDP-emitted); the test/ tree is
# hand-placed to match the layered board e2e standard. The @e2e suite
# delegates to the legacy bash scripts under test/legacy/ as a transition
# while the per-feature Karate equivalents are filled out — see
# TODO(t/PL-e2bc-protolake-e2e-restructure) (closed by VDP-e8e9; reopen
# if richer per-feature coverage is needed before that supersession).
#
# First invocation builds the local karate-runner image; subsequent
# runs reuse the cached image.
#
# Generated scaffold-once by CoHub. See test/README.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIER="${1:-smoke}"
KARATE_IMAGE="cohub-karate-runner:1.5.2"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

# Prereq checks.
command -v docker >/dev/null 2>&1 || { echo "docker required" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose v2 required" >&2; exit 1; }

# Build the runner image if missing.
if ! docker image inspect "$KARATE_IMAGE" >/dev/null 2>&1; then
  echo ">>> Building $KARATE_IMAGE (one-time; cached thereafter)..."
  docker build -t "$KARATE_IMAGE" "$SCRIPT_DIR/runner"
fi

# Bring up the protolake stack.
echo ">>> docker compose up -d --wait"
docker compose -f "$COMPOSE_FILE" up -d --wait

# Teardown on exit so failures don't leak containers.
trap 'echo ">>> docker compose down -v"; docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true' EXIT

# Run the Karate suite for the requested tier.
case "$TIER" in
  smoke|e2e)
    echo ">>> karate --tags @$TIER"
    docker run --rm --network host \
      -v "$SCRIPT_DIR:/test" -w /test \
      "$KARATE_IMAGE" \
      --tags "@$TIER" .
    ;;
  all)
    echo ">>> karate (no tag filter)"
    docker run --rm --network host \
      -v "$SCRIPT_DIR:/test" -w /test \
      "$KARATE_IMAGE" \
      .
    ;;
  *)
    echo "unknown tier: $TIER (expected smoke|e2e|all)" >&2
    exit 1
    ;;
esac
