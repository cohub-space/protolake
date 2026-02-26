#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""
Wrapper script that runs Gazelle in two passes:
1. First pass: Standard Gazelle generates proto_library rules
2. Second pass: Protolake extension generates service bundle rules
"""

import os
import subprocess
import sys

def run_command(cmd, cwd=None):
    """Run a command and return its exit code."""
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.stdout:
        print(result.stdout)
    if result.stderr:
        print(result.stderr, file=sys.stderr)
    return result.returncode

def main():
    workspace_root = os.environ.get('BUILD_WORKSPACE_DIRECTORY', '.')
    if workspace_root == '.':
        current = os.getcwd()
        while current != '/':
            if os.path.exists(os.path.join(current, 'MODULE.bazel')):
                workspace_root = current
                break
            current = os.path.dirname(current)

    print("=== Proto Lake Gazelle Wrapper ===")
    print(f"Workspace root: {workspace_root}")

    # Pass 1: Standard Gazelle generates proto_library rules
    print("\nPass 1: Running standard Gazelle...")
    gazelle_args = ["bazel", "run", "//:gazelle"]
    if len(sys.argv) > 1:
        gazelle_args.extend(["--"] + sys.argv[1:])

    exit_code = run_command(gazelle_args, cwd=workspace_root)
    if exit_code != 0:
        print("Standard Gazelle failed!", file=sys.stderr)
        return exit_code

    # Pass 2: Protolake Gazelle generates service bundle rules
    print("\nPass 2: Running Protolake Gazelle...")
    exit_code = run_command(["bazel", "run", "//:gazelle-protolake"], cwd=workspace_root)
    if exit_code != 0:
        print("Protolake Gazelle failed!", file=sys.stderr)
        return exit_code

    print("\nAll BUILD files generated successfully.")
    return 0

if __name__ == '__main__':
    sys.exit(main())
