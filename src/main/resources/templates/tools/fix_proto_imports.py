#!/usr/bin/env python3
"""
Fix proto imports in BUILD files for Bazel 8 compatibility.

In Bazel 8, proto_library is a built-in rule and doesn't need to be loaded
from @rules_proto. This script removes the obsolete load statements.
"""

import os
import re
import sys
from pathlib import Path


def fix_build_file(build_path):
    """Fix proto imports in a single BUILD file."""
    if not os.path.exists(build_path):
        return False

    with open(build_path, 'r') as f:
        content = f.read()

    original_content = content

    # Remove load statements for proto_library from rules_proto
    # Pattern matches:
    # load("@rules_proto//proto:defs.bzl", "proto_library")
    # load("@rules_proto//proto:defs.bzl", "proto_library", "proto_lang_toolchain")
    pattern = r'load\s*\(\s*"@rules_proto//proto:defs\.bzl"\s*,\s*[^)]+\)\s*\n?'
    content = re.sub(pattern, '', content)

    # Also remove any empty lines that might be left
    content = re.sub(r'\n\s*\n\s*\n', '\n\n', content)

    if content != original_content:
        with open(build_path, 'w') as f:
            f.write(content)
        return True

    return False


def find_build_files(root_dir):
    """Find all BUILD and BUILD.bazel files."""
    build_files = []

    for root, dirs, files in os.walk(root_dir):
        # Skip bazel output directories
        dirs[:] = [d for d in dirs if not d.startswith('bazel-')]

        for file in files:
            if file in ('BUILD', 'BUILD.bazel'):
                build_files.append(os.path.join(root, file))

    return build_files


def main():
    """Main entry point."""
    # Get workspace root - either from env or current directory
    workspace_root = os.environ.get('BUILD_WORKSPACE_DIRECTORY', '.')

    print(f"Fixing proto imports in BUILD files under: {workspace_root}")

    build_files = find_build_files(workspace_root)
    print(f"Found {len(build_files)} BUILD files")

    fixed_count = 0
    for build_file in build_files:
        if fix_build_file(build_file):
            print(f"  Fixed: {build_file}")
            fixed_count += 1

    print(f"\nFixed {fixed_count} BUILD files")
    return 0


if __name__ == '__main__':
    sys.exit(main())