#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""NPM publisher for proto bundles - supports multiple local publishing strategies"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def read_bundle_version(bundle_yaml_path):
    """Read the top-level `version:` from a bundle.yaml.

    Minimal line parser on purpose — this tool runs under bazel py runtimes
    with stdlib only, so no yaml library. Fails loudly if no version found.
    """
    with open(bundle_yaml_path, encoding='utf-8') as f:
        for line in f:
            match = re.match(r"^version:\s*['\"]?([^'\"\s]+)", line)
            if match:
                return match.group(1)
    print(f"Error: no top-level 'version:' line found in {bundle_yaml_path}",
          file=sys.stderr)
    sys.exit(1)


def publish_npm_package(bundle_path, package_name, version):
    """Publish NPM package to local registry or npm link"""

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

        if publish_mode == 'skip':
            print(f"JS publishing skipped for {package_name}")
            print(f"  Use --js-target=<path> for local workspace install")
            print(f"  Use --npm-registry-url=<url> for remote registry publish")
            return True  # Success — skipping is intentional

        elif publish_mode == 'link':
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

        elif publish_mode == 'workspace':
            import pkg_editor_generated as pkg_editor

            js_targets_str = os.environ.get('JS_TARGETS', '')
            if not js_targets_str.strip():
                print("Warning: NPM_PUBLISH_MODE=workspace but JS_TARGETS not set", file=sys.stderr)
                print("  Use --js-target=<path> in protolakew", file=sys.stderr)
                return True  # Non-fatal

            # Accept both comma and newline separators
            js_targets = [t.strip() for t in js_targets_str.replace('\n', ',').split(',') if t.strip()]

            all_ok = True
            for target_dir in js_targets:
                target_pkg = os.path.join(target_dir, 'package.json')
                if not os.path.exists(target_pkg):
                    print(f"Warning: no package.json at {target_dir}, skipping", file=sys.stderr)
                    continue

                ok = pkg_editor.install_package_to_workspace(str(pkg_dir), package_name, target_dir)
                if ok:
                    flat_name = package_name.replace('@', '').replace('/', '-')
                    print(f"\u2713 Installed {package_name} to {target_dir}/protolake/")
                    print(f"  Next: run 'npm install' in {target_dir}")
                    print()
                    print(f"  \u26a0 Lockfile notice:")
                    print(f"    npm install will modify package-lock.json with local workspace entries.")
                    print(f"    These changes are local-only \u2014 do not commit them.")
                    print(f"    Before committing, restore the clean lockfile:")
                    print()
                    print(f"      git restore package-lock.json")
                    print(f"      # or: git checkout -- package-lock.json")
                    print()
                    print(f"    Or add the lockfile guard to CI:")
                    print()
                    print(f"      python tools/check_lockfile.py --target .")
                else:
                    print(f"\u2717 Failed to install to: {target_dir}", file=sys.stderr)
                    all_ok = False

            return all_ok

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
            print("Supported modes: skip, link, local-registry, file, pack, workspace, registry")
            return False

        return True


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description='Publish NPM proto bundle',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Environment variables:
  NPM_PUBLISH_MODE    Publishing mode (default: skip)
                      - skip: skip JS publishing (default without --js-target)
                      - link: npm link for local development
                      - local-registry: publish to local registry
                      - file: copy to ~/.proto-lake/npm-packages
                      - pack: save tarball to ~/.proto-lake/npm-packs
                      - workspace: install to JS/TS project workspace (via --js-target)
                      - registry: publish to remote npm registry

  JS_TARGETS          Comma-separated target project paths (for workspace mode)
  NPM_REGISTRY        Registry URL for local-registry mode
                      (default: http://localhost:4873)
  NPM_REGISTRY_URL    Registry URL for registry mode (e.g., GCP Artifact Registry)
  NPM_REGISTRY_TOKEN  Auth token for registry mode (falls back to REGISTRY_TOKEN)

Examples:
  # Default: skip JS publishing
  bazel run //path/to:publish_to_npm

  # Workspace install to a JS/TS project
  NPM_PUBLISH_MODE=workspace JS_TARGETS=/path/to/app bazel run //path/to:publish_to_npm

  # npm link for local development
  NPM_PUBLISH_MODE=link bazel run //path/to:publish_to_npm

  # Save to file system (legacy default)
  NPM_PUBLISH_MODE=file bazel run //path/to:publish_to_npm

  # Publish to remote registry
  NPM_PUBLISH_MODE=registry NPM_REGISTRY_URL=https://... bazel run //path/to:publish_to_npm
"""
    )
    parser.add_argument('bundle_path',
                        help='Path to the .tgz bundle file')
    parser.add_argument('--package-name', required=True,
                        help='NPM package name (e.g. @scope/pkg)')
    parser.add_argument('--version', default=None,
                        help='Package version (for logging/paths only — the '
                             'bundle tarball is already version-stamped)')
    parser.add_argument('--bundle-yaml', default=None,
                        help="Path to the bundle's bundle.yaml; used to resolve "
                             'the version when --version is absent')

    args = parser.parse_args()

    if args.version is None:
        if args.bundle_yaml is None:
            parser.error('one of --version or --bundle-yaml is required')
        args.version = read_bundle_version(args.bundle_yaml)

    if not os.path.exists(args.bundle_path):
        print(f"Bundle not found: {args.bundle_path}", file=sys.stderr)
        sys.exit(1)

    # Show current mode
    mode = os.environ.get('NPM_PUBLISH_MODE', 'link')
    print(f"Publishing mode: {mode}")

    success = publish_npm_package(args.bundle_path, args.package_name, args.version)
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()