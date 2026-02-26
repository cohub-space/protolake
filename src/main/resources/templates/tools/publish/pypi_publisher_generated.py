#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""PyPI publisher for Proto Lake bundles"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

# Add parent directory to path for utilities
sys.path.insert(0, str(Path(__file__).parent))
from publisher_utils_generated import ensure_directory_exists, calculate_checksum


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


def publish_to_remote_registry(wheel_path, registry_url, token):
    """Publish wheel to a remote PyPI registry (e.g., GCP Artifact Registry) using twine"""
    registry_url = registry_url.rstrip('/')

    # Try twine first
    try:
        result = subprocess.run(
            ['twine', '--version'],
            capture_output=True, text=True,
        )
        has_twine = result.returncode == 0
    except FileNotFoundError:
        has_twine = False

    if has_twine:
        print(f"Uploading with twine to: {registry_url}")
        env = os.environ.copy()
        env['TWINE_USERNAME'] = 'oauth2accesstoken'
        env['TWINE_PASSWORD'] = token
        result = subprocess.run(
            [
                'twine', 'upload',
                '--repository-url', registry_url,
                '--non-interactive',
                wheel_path,
            ],
            capture_output=True,
            text=True,
            env=env,
        )
        if result.returncode != 0:
            print(f"Error uploading with twine: {result.stderr}", file=sys.stderr)
            if result.stdout:
                print(f"  stdout: {result.stdout}", file=sys.stderr)
            sys.exit(1)
        print(f"Successfully uploaded: {os.path.basename(wheel_path)}")
    else:
        # Fallback: HTTP upload using urllib
        print(f"twine not found, using urllib fallback to: {registry_url}")
        import urllib.request
        import urllib.error

        wheel_name = os.path.basename(wheel_path)
        with open(wheel_path, 'rb') as f:
            wheel_data = f.read()

        # Construct multipart form data for PyPI upload
        boundary = '----ProtolakeUploadBoundary'
        fields = [
            (':action', 'file_upload'),
            ('protocol_version', '1'),
        ]
        body = b''
        for field_name, field_value in fields:
            body += f'--{boundary}\r\n'.encode()
            body += f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n'.encode()
            body += f'{field_value}\r\n'.encode()

        body += f'--{boundary}\r\n'.encode()
        body += f'Content-Disposition: form-data; name="content"; filename="{wheel_name}"\r\n'.encode()
        body += b'Content-Type: application/octet-stream\r\n\r\n'
        body += wheel_data
        body += f'\r\n--{boundary}--\r\n'.encode()

        req = urllib.request.Request(
            registry_url + '/',
            data=body,
            method='POST',
            headers={
                'Content-Type': f'multipart/form-data; boundary={boundary}',
                'Authorization': f'Bearer {token}',
            },
        )
        try:
            with urllib.request.urlopen(req) as resp:
                print(f"Uploaded {wheel_name} ({resp.status})")
        except urllib.error.HTTPError as e:
            print(f"Error uploading {wheel_name}: {e.code} {e.reason}", file=sys.stderr)
            resp_body = e.read().decode('utf-8', errors='replace')
            if resp_body:
                print(f"  Response: {resp_body[:500]}", file=sys.stderr)
            sys.exit(1)

    return wheel_path


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
        if args.repo.startswith('https://') or args.repo.startswith('http://'):
            # Remote registry mode
            token = os.environ.get('REGISTRY_TOKEN', '')
            if not token:
                print("Error: REGISTRY_TOKEN env var required for remote registry",
                      file=sys.stderr)
                sys.exit(1)

            publish_to_remote_registry(args.wheel_path, args.repo, token)

            print(f"\nSuccessfully published to remote PyPI registry:")
            print(f"  Package: {args.package_name}")
            print(f"  Version: {args.version}")
            print(f"  Registry: {args.repo}")

        elif args.index_url:
            # Legacy --index-url flag (redirect to remote)
            token = os.environ.get('REGISTRY_TOKEN', '')
            if not token:
                print("Error: REGISTRY_TOKEN env var required for remote upload",
                      file=sys.stderr)
                sys.exit(1)

            publish_to_remote_registry(args.wheel_path, args.index_url, token)

            print(f"\nSuccessfully published to PyPI:")
            print(f"  Package: {args.package_name}")
            print(f"  Version: {args.version}")
            print(f"  Registry: {args.index_url}")

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