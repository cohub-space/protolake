#!/usr/bin/env python3
"""Package editor for JS/TS workspace-based local install.

Manages npm workspace entries in package.json and .gitignore entries
for protolake local development. Designed as a reusable CLI tool.
"""

import json
import os
import shutil
import sys


def add_workspace_entry(target_dir, glob="protolake/*"):
    """Add a workspace glob to package.json (idempotent).

    Handles both array format (["glob"]) and object format (packages: ["glob"]).

    Returns:
        True if entry was added or already present, False on error.
    """
    pkg_path = os.path.join(target_dir, 'package.json')
    if not os.path.exists(pkg_path):
        print(f"Error: package.json not found at {target_dir}", file=sys.stderr)
        return False

    with open(pkg_path, 'r') as f:
        try:
            pkg = json.load(f)
        except json.JSONDecodeError as e:
            print(f"Error: invalid package.json at {target_dir}: {e}", file=sys.stderr)
            return False

    workspaces = pkg.get('workspaces')

    if workspaces is None:
        # No workspaces field — add as array
        pkg['workspaces'] = [glob]
    elif isinstance(workspaces, list):
        # Array format: ["glob1", "glob2"]
        if glob not in workspaces:
            workspaces.append(glob)
    elif isinstance(workspaces, dict):
        # Object format: packages: ["glob1", "glob2"]
        packages = workspaces.get('packages')
        if packages is None:
            workspaces['packages'] = [glob]
        elif isinstance(packages, list):
            if glob not in packages:
                packages.append(glob)
        else:
            print(f"Error: workspaces.packages is not an array in {pkg_path}", file=sys.stderr)
            return False
    else:
        print(f"Error: workspaces field is not an array or object in {pkg_path}", file=sys.stderr)
        return False

    with open(pkg_path, 'w') as f:
        json.dump(pkg, f, indent=2)
        f.write('\n')

    return True


def ensure_gitignore(target_dir, entry="protolake/"):
    """Add entry to .gitignore if not present. Create file if missing.

    Returns:
        True if entry was added or already present.
    """
    gitignore_path = os.path.join(target_dir, '.gitignore')

    existing_lines = []
    if os.path.exists(gitignore_path):
        with open(gitignore_path, 'r') as f:
            existing_lines = f.read().splitlines()

    # Check if entry already present
    if entry in existing_lines:
        return True

    # Append entry
    with open(gitignore_path, 'a') as f:
        # Add newline before entry if file doesn't end with one
        if existing_lines and existing_lines[-1] != '':
            f.write('\n')
        f.write(entry + '\n')

    return True


def install_package_to_workspace(pkg_dir, package_name, target_dir):
    """Install an extracted npm package into a workspace directory.

    Copies the package to <target>/protolake/<flat_name>/, sets up
    workspace entry in package.json, and adds protolake/ to .gitignore.

    Args:
        pkg_dir: Path to the extracted package directory (contains package.json)
        package_name: npm package name (e.g., "@cohub/user-proto")
        target_dir: Target JS/TS project root (contains package.json)

    Returns:
        True on success, False on error.
    """
    flat_name = package_name.replace('@', '').replace('/', '-')
    protolake_dir = os.path.join(target_dir, 'protolake')
    dest_dir = os.path.join(protolake_dir, flat_name)

    # Clean destination if exists
    if os.path.exists(dest_dir):
        shutil.rmtree(dest_dir)

    # Copy package
    os.makedirs(protolake_dir, exist_ok=True)
    shutil.copytree(pkg_dir, dest_dir)

    # Set up workspace entry and .gitignore
    if not add_workspace_entry(target_dir):
        return False
    ensure_gitignore(target_dir)

    return True


def check_workspace_entry(target_dir, glob="protolake/*"):
    """Check if a workspace entry exists in package.json.

    Returns:
        True if entry exists, False otherwise.
    """
    pkg_path = os.path.join(target_dir, 'package.json')
    if not os.path.exists(pkg_path):
        return False

    with open(pkg_path, 'r') as f:
        try:
            pkg = json.load(f)
        except json.JSONDecodeError:
            return False

    workspaces = pkg.get('workspaces')
    if isinstance(workspaces, list):
        return glob in workspaces
    elif isinstance(workspaces, dict):
        packages = workspaces.get('packages', [])
        return isinstance(packages, list) and glob in packages

    return False


def main():
    if len(sys.argv) < 3:
        print("Usage: pkg_editor.py workspace <add|check> <glob> --target <path>", file=sys.stderr)
        sys.exit(1)

    command = sys.argv[1]
    action = sys.argv[2]

    if command != 'workspace':
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)

    # Parse remaining args
    glob = None
    target = None
    i = 3
    while i < len(sys.argv):
        if sys.argv[i] == '--target' and i + 1 < len(sys.argv):
            target = sys.argv[i + 1]
            i += 2
        elif glob is None:
            glob = sys.argv[i]
            i += 1
        else:
            i += 1

    if glob is None:
        glob = "protolake/*"
    if target is None:
        target = "."

    if action == 'add':
        ok = add_workspace_entry(target, glob)
        if ok:
            ensure_gitignore(target)
        sys.exit(0 if ok else 1)
    elif action == 'check':
        exists = check_workspace_entry(target, glob)
        if exists:
            print(f"Workspace entry '{glob}' found in {target}/package.json")
            sys.exit(0)
        else:
            print(f"Workspace entry '{glob}' not found in {target}/package.json")
            sys.exit(2)
    else:
        print(f"Unknown action: {action}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
