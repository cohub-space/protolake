#!/usr/bin/env python3
"""PyPI publisher for Proto Lake bundles"""

import argparse
import os
import shutil
import sys
from pathlib import Path

# Add parent directory to path for utilities
sys.path.insert(0, str(Path(__file__).parent))
from publisher_utils import ensure_directory_exists, calculate_checksum


def publish_to_local_repo(wheel_path, package_name, version, repo_path):
    """Publish wheel to local PyPI repository using simple index format"""

    # Normalize package name for PyPI (lowercase, hyphens)
    normalized_name = package_name.replace('_', '-').lower()

    # Create package directory in simple index structure
    package_dir = Path(repo_path) / normalized_name
    ensure_directory_exists(package_dir)

    # Copy wheel directly to package directory
    wheel_name = os.path.basename(wheel_path)
    target_wheel = package_dir / wheel_name
    shutil.copy2(wheel_path, target_wheel)
    print(f"Copied wheel to {target_wheel}")

    # Update package index
    update_package_index(package_dir)

    # Update root simple index
    update_root_index(Path(repo_path))

    return target_wheel


def update_package_index(package_dir):
    """Create/update package-specific index.html"""
    # List all wheel files
    wheel_files = sorted([f for f in os.listdir(package_dir) if f.endswith('.whl')])

    # Create index.html
    html_lines = ['<!DOCTYPE html>', '<html>', '<body>']
    for wheel in wheel_files:
        html_lines.append(f'<a href="{wheel}">{wheel}</a><br/>')
    html_lines.extend(['</body>', '</html>'])

    index_path = package_dir / "index.html"
    index_path.write_text('\n'.join(html_lines))
    print(f"Updated package index: {index_path}")


def update_root_index(repo_path):
    """Update root simple index listing all packages"""
    # List all package directories
    packages = []
    for item in sorted(os.listdir(repo_path)):
        item_path = repo_path / item
        if item_path.is_dir() and not item.startswith('.'):
            packages.append(item)

    # Create root index.html
    html_lines = ['<!DOCTYPE html>', '<html>', '<body>']
    for pkg in packages:
        html_lines.append(f'<a href="{pkg}/">{pkg}</a><br/>')
    html_lines.extend(['</body>', '</html>'])

    index_path = repo_path / "index.html"
    index_path.write_text('\n'.join(html_lines))
    print(f"Updated root index: {index_path}")


def main():
    parser = argparse.ArgumentParser(description='Publish Proto Lake bundle to PyPI')
    parser.add_argument('wheel_path', help='Path to the wheel file')
    parser.add_argument('--package-name', required=True,
                        help='PyPI package name')
    parser.add_argument('--version', default='1.0.0',
                        help='Version')
    parser.add_argument('--repo', default=os.path.expanduser('~/.cache/pip/simple'),
                        help='Local PyPI repository path')
    parser.add_argument('--index-url', help='PyPI index URL (for production)')

    args = parser.parse_args()

    # Verify wheel exists
    if not os.path.exists(args.wheel_path):
        print(f"Error: Wheel file not found: {args.wheel_path}", file=sys.stderr)
        sys.exit(1)

    try:
        if args.index_url:
            # Production mode - use twine
            print(f"Would upload to PyPI: {args.index_url}")
            print("Production PyPI upload not implemented yet")
            sys.exit(1)
        else:
            # Local repository mode
            published_wheel = publish_to_local_repo(
                args.wheel_path,
                args.package_name,
                args.version,
                args.repo
            )

            print(f"\nSuccessfully published to local PyPI repository:")
            print(f"  Package: {args.package_name}")
            print(f"  Version: {args.version}")
            print(f"  Location: {published_wheel}")
            print(f"\nTo install locally:")
            print(f"  pip install --index-url file://{args.repo} {args.package_name}=={args.version}")

    except Exception as e:
        print(f"Error publishing to PyPI: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()