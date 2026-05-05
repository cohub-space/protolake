#!/usr/bin/env python3
"""CoHub board e2e — entry point.

Usage:
    ./test/run.py [smoke|e2e|all]   (default: smoke)
    python test/run.py [smoke|e2e|all]   (Windows / explicit invocation)

Brings up the protolake stack via test/docker-compose.yml, runs Karate
scenarios tagged @smoke or @e2e against it, tears down on exit.

protolake is an EXTERNAL repo (not VDP-emitted); the test/ tree is
hand-placed to match the layered board e2e standard. The @e2e suite
delegates to the legacy bash scripts under test/legacy/ as a
transition while the per-feature Karate equivalents are filled out.

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
KARATE_IMAGE = "cohub-karate-runner:1.5.2"
COMPOSE_FILE = SCRIPT_DIR / "docker-compose.yml"


def run(cmd, **kw) -> subprocess.CompletedProcess:
    print(">>> " + " ".join(map(str, cmd)), file=sys.stderr)
    return subprocess.run(cmd, **kw)


def main() -> int:
    ap = argparse.ArgumentParser(description="CoHub board e2e — entry point.")
    ap.add_argument("tier", nargs="?", default="smoke",
                    choices=["smoke", "e2e", "all"],
                    help="which Karate tag to run (default: smoke)")
    args = ap.parse_args()

    # Prereq checks.
    if shutil.which("docker") is None:
        sys.exit("docker required")
    if run(["docker", "compose", "version"], capture_output=True).returncode != 0:
        sys.exit("docker compose v2 required")

    # Build runner image if missing (cached after first run).
    if run(["docker", "image", "inspect", KARATE_IMAGE],
           capture_output=True).returncode != 0:
        print(f">>> Building {KARATE_IMAGE} (one-time; cached thereafter)...",
              file=sys.stderr)
        if run(["docker", "build", "-t", KARATE_IMAGE,
                str(SCRIPT_DIR / "runner")]).returncode != 0:
            sys.exit("docker build failed")

    if run(["docker", "compose", "-f", str(COMPOSE_FILE),
            "up", "-d", "--wait"]).returncode != 0:
        sys.exit("docker compose up failed")

    # Teardown on exit so failures don't leak containers.
    def teardown():
        print(">>> docker compose down -v", file=sys.stderr)
        subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE_FILE), "down", "-v"],
            capture_output=True,
        )
    atexit.register(teardown)

    # Run the Karate suite for the requested tier.
    docker_args = [
        "docker", "run", "--rm", "--network", "host",
        "-v", f"{SCRIPT_DIR}:/test", "-w", "/test",
        KARATE_IMAGE,
    ]
    if args.tier in ("smoke", "e2e"):
        docker_args += [f"--tags=@{args.tier}", "."]
    else:  # "all"
        docker_args.append(".")

    return run(docker_args).returncode


if __name__ == "__main__":
    sys.exit(main())
