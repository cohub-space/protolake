#!/usr/bin/env python3
"""CoHub board e2e — entry point.

Usage:
    ./test/run.py [smoke|e2e|all]   (default: smoke)
    python test/run.py [smoke|e2e|all]   (Windows / explicit invocation)

smoke: brings up the protolake stack via test/docker-compose.yml, runs the
Karate @smoke scenarios against it, tears down on exit.
e2e:   runs the full-pipeline bash suites under test/legacy/ on this host,
       each managing its own stack; a suite passes only on exit status 0.
all:   both, in that order.

protolake is an EXTERNAL repo (not VDP-emitted); the test/ tree is
hand-placed to match the layered board e2e standard. The legacy suites run
on the host because they drive docker, jq, grpcurl and curl themselves,
which the Karate runner image does not carry.

First invocation builds the local karate-runner image (Karate OSS +
grpcurl on top of eclipse-temurin); subsequent runs reuse it.

Generated scaffold-once by CoHub. See test/README.md.
"""
import argparse
import atexit
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
KARATE_IMAGE = "cohub-karate-runner:2.1.3"
COMPOSE_FILE = SCRIPT_DIR / "docker-compose.yml"


def run(cmd, **kw) -> subprocess.CompletedProcess:
    print(">>> " + " ".join(map(str, cmd)), file=sys.stderr)
    return subprocess.run(cmd, **kw)


LEGACY_SUITES = ["test_protolake.sh", "test_cli.sh", "test_remote_publish.sh"]
LEGACY_TOOLS = ["jq", "grpcurl", "curl", "python3"]


def smoke() -> int:
    """The Karate @smoke scenarios against a stack this runner brings up and tears down."""
    if run(["docker", "image", "inspect", KARATE_IMAGE],
           capture_output=True).returncode != 0:
        print(f">>> Building {KARATE_IMAGE} (one-time; cached thereafter)...",
              file=sys.stderr)
        if run(["docker", "build", "-t", KARATE_IMAGE,
                str(SCRIPT_DIR / "runner")]).returncode != 0:
            sys.exit("docker build failed")

    # Teardown registered before `up`, so a failed bring-up doesn't leak containers.
    def teardown():
        print(">>> docker compose down -v", file=sys.stderr)
        subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE_FILE), "down", "-v"],
            capture_output=True,
        )
    atexit.register(teardown)
    if run(["docker", "compose", "-f", str(COMPOSE_FILE),
            "up", "-d", "--wait"]).returncode != 0:
        sys.exit("docker compose up failed")

    # smoke.feature, plus any native Karate features under e2e/ (none yet).
    # Explicit paths, not ".": generated fixtures may land in gitignored
    # test/playground/<board>/ and would be scanned too.
    paths = ["smoke.feature"] + (["e2e/"] if any((SCRIPT_DIR / "e2e").glob("**/*.feature")) else [])
    # Karate 2 exits 0 on a path it cannot find, so a missing suite path
    # would pass vacuously.
    missing = [p for p in paths if not (SCRIPT_DIR / p).exists()]
    if missing:
        sys.exit(f"suite path(s) missing under {SCRIPT_DIR}: " + ", ".join(missing))
    rc = run([
        "docker", "run", "--rm", "--network", "host",
        "-v", f"{SCRIPT_DIR}:/test", "-w", "/test",
        KARATE_IMAGE, "--tags=@smoke", *paths,
    ]).returncode
    teardown()
    atexit.unregister(teardown)
    return rc


def legacy() -> int:
    """The full-pipeline bash suites, on this host, judged by exit status."""
    missing = [tool for tool in LEGACY_TOOLS if shutil.which(tool) is None]
    if missing:
        sys.exit(f"the legacy suites need {', '.join(missing)} on this host "
                 "(macOS: brew install jq grpcurl)")
    failed = []
    for suite in LEGACY_SUITES:
        rc = run(["bash", str(SCRIPT_DIR / "legacy" / suite)]).returncode
        print(f">>> {suite}: {'PASS' if rc == 0 else f'FAIL (exit {rc})'}", file=sys.stderr)
        if rc != 0:
            failed.append(suite)
    if failed:
        print(f">>> legacy suites failed: {', '.join(failed)}", file=sys.stderr)
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="CoHub board e2e — entry point.")
    ap.add_argument("tier", nargs="?", default="smoke",
                    choices=["smoke", "e2e", "all"],
                    help="smoke: Karate @smoke; e2e: the legacy pipeline suites; all: both")
    args = ap.parse_args()

    # Prereq checks.
    if shutil.which("docker") is None:
        sys.exit("docker required")
    if run(["docker", "compose", "version"], capture_output=True).returncode != 0:
        sys.exit("docker compose v2 required")

    rc = 0
    if args.tier in ("smoke", "all"):
        rc |= smoke()
    if args.tier in ("e2e", "all"):
        rc |= legacy()
    return rc


if __name__ == "__main__":
    sys.exit(main())
