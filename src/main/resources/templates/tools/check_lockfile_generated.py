#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""CI/pre-commit guard that detects tainted package-lock.json.

Checks for protolake workspace entries that should not be committed.
Covers npm lockfile v1, v2, and v3 formats.

Exit codes:
  0 = clean (or missing in non-CI mode)
  1 = tainted (or missing in CI mode)
"""

import argparse
import json
import os
import sys


def check_lockfile(target_dir, ci_mode=False):
    """Check package-lock.json for protolake workspace entries.

    Args:
        target_dir: Directory containing package-lock.json
        ci_mode: If True, missing lockfile is an error

    Returns:
        (is_clean, findings) where findings is a list of description strings
    """
    lockfile_path = os.path.join(target_dir, 'package-lock.json')

    if not os.path.exists(lockfile_path):
        if ci_mode:
            return False, ["package-lock.json is missing"]
        else:
            return True, []

    with open(lockfile_path, 'r') as f:
        try:
            lockdata = json.load(f)
        except json.JSONDecodeError as e:
            return False, [f"package-lock.json is invalid JSON: {e}"]

    findings = []

    # Check lockfile v2/v3 "packages" field
    packages = lockdata.get('packages', {})
    for key, value in packages.items():
        if _is_protolake_path(key):
            link_info = " (link: true)" if value.get('link') else ""
            findings.append(f"{key}{link_info}")
        if isinstance(value, dict):
            resolved = value.get('resolved', '')
            if isinstance(resolved, str) and _is_protolake_resolved(resolved):
                if key not in [f.split(' ')[0] for f in findings]:
                    findings.append(f"{key} (resolved: {resolved})")

    # Check lockfile v1 "dependencies" field
    dependencies = lockdata.get('dependencies', {})
    _check_dependencies_recursive(dependencies, findings)

    is_clean = len(findings) == 0
    return is_clean, findings


def _is_protolake_path(path):
    """Check if a path references protolake workspace."""
    return '/protolake/' in path or '\\protolake\\' in path or path.startswith('protolake/')


def _is_protolake_resolved(resolved):
    """Check if a resolved path points into protolake."""
    if resolved.startswith('file:'):
        remainder = resolved[5:]
        return '/protolake/' in remainder or '\\protolake\\' in remainder or remainder.startswith('protolake/')
    return False


def _check_dependencies_recursive(deps, findings, prefix=""):
    """Recursively check v1 dependencies for protolake entries."""
    if not isinstance(deps, dict):
        return
    for name, info in deps.items():
        if not isinstance(info, dict):
            continue
        version = info.get('version', '')
        if isinstance(version, str) and _is_protolake_resolved(version):
            findings.append(f"{prefix}{name} (version: {version})")
        resolved = info.get('resolved', '')
        if isinstance(resolved, str) and _is_protolake_resolved(resolved):
            findings.append(f"{prefix}{name} (resolved: {resolved})")
        # Recurse into nested dependencies
        nested = info.get('dependencies', {})
        if isinstance(nested, dict):
            _check_dependencies_recursive(nested, findings, prefix=f"{prefix}{name}/")


def main():
    parser = argparse.ArgumentParser(
        description='Check package-lock.json for protolake workspace entries',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Local development: warn if missing, fail if tainted
  python check_lockfile.py --target .

  # CI mode: error if missing OR tainted
  python check_lockfile.py --target . --ci
"""
    )
    parser.add_argument('--target', default='.', help='Directory containing package-lock.json')
    parser.add_argument('--ci', action='store_true', help='CI mode: error if lockfile is missing')

    args = parser.parse_args()

    is_clean, findings = check_lockfile(args.target, ci_mode=args.ci)

    if is_clean:
        if findings:
            # Shouldn't happen, but defensive
            for f in findings:
                print(f"  {f}")
        sys.exit(0)
    else:
        if "missing" in (findings[0] if findings else ""):
            print(f"ERROR: package-lock.json is missing in {args.target}", file=sys.stderr)
            sys.exit(1)

        print("ERROR: package-lock.json contains workspace entries from protolake.")
        for f in findings:
            print(f"  Found: {f}")
        print()
        print("  These are local-only and should not be committed.")
        print("  To restore the clean lockfile:")
        print()
        print("    git restore package-lock.json")
        print("    # or: git checkout -- package-lock.json")
        sys.exit(1)


if __name__ == '__main__':
    main()
