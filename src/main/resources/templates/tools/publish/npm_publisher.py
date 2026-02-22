#!/usr/bin/env python3
"""NPM publisher for proto bundles - supports multiple local publishing strategies"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def get_bundle_and_coords(args):
    """Extract bundle and coordinates from command line args"""
    if len(args) < 2:
        print("Usage: npm_publisher.py <bundle.tgz> <coords.txt>")
        sys.exit(1)

    bundle_path = args[0]
    coords_path = args[1]

    if not os.path.exists(bundle_path):
        print(f"Bundle not found: {bundle_path}")
        sys.exit(1)

    if not os.path.exists(coords_path):
        print(f"Coordinates file not found: {coords_path}")
        sys.exit(1)

    return bundle_path, coords_path


def publish_npm_package(bundle_path, coordinates_path):
    """Publish NPM package to local registry or npm link"""

    # Read coordinates
    with open(coordinates_path, 'r') as f:
        coordinates = f.read().strip()
    package_name, version = coordinates.rsplit('@', 1)

    # Convert to absolute path before changing cwd for extraction
    bundle_path = os.path.abspath(bundle_path)

    with tempfile.TemporaryDirectory() as tmpdir:
        # Extract bundle
        print(f"Extracting bundle: {bundle_path}")
        result = subprocess.run(
            ['tar', 'xzf', bundle_path],
            cwd=tmpdir,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print(f"Error extracting bundle: {result.stderr}")
            return False

        # Find package directory
        pkg_dir = next(Path(tmpdir).iterdir())

        # Determine publishing mode from environment
        publish_mode = os.environ.get('NPM_PUBLISH_MODE', 'link')

        if publish_mode == 'link':
            # npm link for local development
            print(f"Linking package: {package_name}")
            result = subprocess.run(
                ['npm', 'link'],
                cwd=pkg_dir,
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                print(f"✓ Package linked: {package_name}")
                print(f"  Use 'npm link {package_name}' in your project")
                print(f"  Or add to package.json: \"{package_name}\": \"link:{pkg_dir}\"")
            else:
                print(f"✗ Failed to link: {result.stderr}")
                return False

        elif publish_mode == 'local-registry':
            # Publish to local registry (e.g., Verdaccio)
            registry_url = os.environ.get('NPM_REGISTRY', 'http://localhost:4873')
            print(f"Publishing to local registry: {registry_url}")

            # Set registry for this publish
            subprocess.run(
                ['npm', 'config', 'set', 'registry', registry_url],
                cwd=pkg_dir
            )

            result = subprocess.run(
                ['npm', 'publish'],
                cwd=pkg_dir,
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                print(f"✓ Published: {package_name}@{version}")
                print(f"  Install with: npm install {package_name} --registry {registry_url}")
            else:
                print(f"✗ Failed to publish: {result.stderr}")
                return False

        elif publish_mode == 'file':
            # Copy to local packages directory
            packages_dir = Path.home() / '.proto-lake' / 'npm-packages'
            packages_dir.mkdir(parents=True, exist_ok=True)

            dest = packages_dir / package_name.replace('/', '-') / version
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists():
                shutil.rmtree(dest)

            shutil.copytree(pkg_dir, dest)
            print(f"✓ Package copied to: {dest}")
            print(f"  Add to package.json: \"{package_name}\": \"file:{dest}\"")

        elif publish_mode == 'pack':
            # Just pack the tarball for manual distribution
            pack_dir = Path.home() / '.proto-lake' / 'npm-packs'
            pack_dir.mkdir(parents=True, exist_ok=True)

            pack_name = f"{package_name.replace('/', '-')}-{version}.tgz"
            dest = pack_dir / pack_name
            shutil.copy2(bundle_path, dest)
            print(f"✓ Package saved to: {dest}")
            print(f"  Install with: npm install {dest}")

        elif publish_mode == 'registry':
            # Publish to a remote npm registry (e.g., GCP Artifact Registry)
            registry_url = os.environ.get('NPM_REGISTRY_URL', '')
            token = os.environ.get('NPM_REGISTRY_TOKEN', '') or os.environ.get('REGISTRY_TOKEN', '')

            if not registry_url:
                print("Error: NPM_REGISTRY_URL env var required for registry mode",
                      file=sys.stderr)
                return False
            if not token:
                print("Error: NPM_REGISTRY_TOKEN or REGISTRY_TOKEN env var required for registry mode",
                      file=sys.stderr)
                return False

            # Write .npmrc with auth token for the registry host
            from urllib.parse import urlparse
            parsed = urlparse(registry_url)
            registry_host = f"//{parsed.netloc}{parsed.path}"
            npmrc_path = pkg_dir / '.npmrc'
            npmrc_path.write_text(f"{registry_host}:_authToken={token}\n")

            print(f"Publishing to registry: {registry_url}")
            result = subprocess.run(
                ['npm', 'publish', f'--registry={registry_url}'],
                cwd=pkg_dir,
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                print(f"✓ Published: {package_name}@{version}")
                print(f"  Install with: npm install {package_name} --registry {registry_url}")
            else:
                print(f"✗ Failed to publish: {result.stderr}")
                if result.stdout:
                    print(f"  stdout: {result.stdout}")
                return False

        else:
            print(f"Unknown publish mode: {publish_mode}")
            print("Supported modes: link, local-registry, file, pack, registry")
            return False

        return True


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description='Publish NPM proto bundle',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Environment variables:
  NPM_PUBLISH_MODE    Publishing mode (default: link)
                      - link: npm link for local development
                      - local-registry: publish to local registry
                      - file: copy to ~/.proto-lake/npm-packages
                      - pack: save tarball to ~/.proto-lake/npm-packs
                      - registry: publish to remote npm registry

  NPM_REGISTRY        Registry URL for local-registry mode
                      (default: http://localhost:4873)
  NPM_REGISTRY_URL    Registry URL for registry mode (e.g., GCP Artifact Registry)
  NPM_REGISTRY_TOKEN  Auth token for registry mode (falls back to REGISTRY_TOKEN)

Examples:
  # Default npm link mode
  bazel run //path/to:publish_to_npm

  # Publish to local registry
  NPM_PUBLISH_MODE=local-registry bazel run //path/to:publish_to_npm

  # Save to file system
  NPM_PUBLISH_MODE=file bazel run //path/to:publish_to_npm

  # Publish to remote registry
  NPM_PUBLISH_MODE=registry NPM_REGISTRY_URL=https://... bazel run //path/to:publish_to_npm
"""
    )

    # Bazel passes the bundle and coords as remaining args
    args = parser.parse_known_args()[1]

    if not args:
        parser.print_help()
        sys.exit(1)

    bundle_path, coords_path = get_bundle_and_coords(args)

    # Show current mode
    mode = os.environ.get('NPM_PUBLISH_MODE', 'link')
    print(f"Publishing mode: {mode}")

    success = publish_npm_package(bundle_path, coords_path)
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()