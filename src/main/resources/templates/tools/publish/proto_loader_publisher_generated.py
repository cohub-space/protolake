#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Proto-loader publisher - creates npm packages with raw .proto files for @grpc/proto-loader.

Follows the google-proto-files pattern: raw protos + getProtoPath() helper.
The resulting package can be used with @grpc/proto-loader's includeDirs option
to resolve imports from filesystem paths.
"""

import argparse
import json
import os
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path


def create_proto_loader_package(output_path, package_name, version, proto_sources):
    """Create an npm package containing raw .proto files and a helper module.

    Args:
        output_path: Path to write the output .tgz file
        package_name: NPM package name (e.g., '@example/service-proto-loader')
        version: Package version
        proto_sources: List of 'src=dest' pairs for proto files
    """
    output_path = os.path.abspath(output_path)

    with tempfile.TemporaryDirectory() as tmpdir:
        pkg_dir = os.path.join(tmpdir, 'package')
        proto_dir = os.path.join(pkg_dir, 'proto')
        os.makedirs(proto_dir, exist_ok=True)

        # Copy proto files preserving directory structure
        for source_pair in proto_sources:
            if '=' in source_pair:
                src, dest = source_pair.split('=', 1)
            else:
                src = source_pair
                dest = source_pair

            dest_path = os.path.join(proto_dir, dest)
            os.makedirs(os.path.dirname(dest_path), exist_ok=True)
            shutil.copy2(src, dest_path)

        # Create package.json
        package_json = {
            'name': package_name,
            'version': version,
            'description': f'Raw proto files for {package_name} - use with @grpc/proto-loader',
            'main': 'index.js',
            'types': 'index.d.ts',
            'files': ['proto/', 'index.js', 'index.d.ts'],
            'peerDependencies': {
                '@grpc/grpc-js': '>=1.8.0',
                '@grpc/proto-loader': '>=0.7.0',
            },
            'keywords': ['protobuf', 'grpc', 'proto-loader'],
        }

        with open(os.path.join(pkg_dir, 'package.json'), 'w') as f:
            json.dump(package_json, f, indent=2)
            f.write('\n')

        # Create index.js - exports PROTO_ROOT and getProtoPath helper
        index_js = '''\
"use strict";
const path = require("path");

/**
 * Absolute path to the proto/ directory in this package.
 * Use as an includeDirs entry for @grpc/proto-loader.
 *
 * @example
 * const {{ PROTO_ROOT }} = require("{package_name}");
 * const packageDef = protoLoader.loadSync("example/service/v1/messages.proto", {{
 *   includeDirs: [PROTO_ROOT],
 * }});
 */
const PROTO_ROOT = path.join(__dirname, "proto");

/**
 * Returns the absolute path to a proto file within this package.
 *
 * @param {{...string}} paths - Path segments relative to proto root
 * @returns {{string}} Absolute path to the proto file
 *
 * @example
 * const {{ getProtoPath }} = require("{package_name}");
 * const protoPath = getProtoPath("example", "service", "v1", "messages.proto");
 */
function getProtoPath(...paths) {{
  return path.join(PROTO_ROOT, ...paths);
}}

module.exports = {{ PROTO_ROOT, getProtoPath }};
'''.format(package_name=package_name)

        with open(os.path.join(pkg_dir, 'index.js'), 'w') as f:
            f.write(index_js)

        # Create index.d.ts - TypeScript declarations
        index_dts = '''\
/**
 * Absolute path to the proto/ directory in this package.
 * Use as an includeDirs entry for @grpc/proto-loader.
 */
export declare const PROTO_ROOT: string;

/**
 * Returns the absolute path to a proto file within this package.
 * @param paths - Path segments relative to proto root
 * @returns Absolute path to the proto file
 */
export declare function getProtoPath(...paths: string[]): string;
'''

        with open(os.path.join(pkg_dir, 'index.d.ts'), 'w') as f:
            f.write(index_dts)

        # Create .tgz package
        with tarfile.open(output_path, 'w:gz') as tar:
            tar.add(pkg_dir, arcname='package')

    print(f"Created proto-loader package: {output_path}")
    return True


def publish_proto_loader_package(bundle_path, coordinates_path):
    """Publish the proto-loader package using the same modes as npm_publisher."""

    # Read coordinates
    with open(coordinates_path, 'r') as f:
        coordinates = f.read().strip()
    package_name, version = coordinates.rsplit('@', 1)

    bundle_path = os.path.abspath(bundle_path)

    with tempfile.TemporaryDirectory() as tmpdir:
        # Extract bundle
        import subprocess
        result = subprocess.run(
            ['tar', 'xzf', bundle_path],
            cwd=tmpdir,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"Error extracting bundle: {result.stderr}")
            return False

        pkg_dir = next(Path(tmpdir).iterdir())

        publish_mode = os.environ.get('NPM_PUBLISH_MODE', 'file')

        if publish_mode == 'skip':
            print(f"JS publishing skipped for proto-loader package {package_name}")
            return True

        elif publish_mode == 'file':
            packages_dir = Path.home() / '.proto-lake' / 'npm-packages'
            dest = packages_dir / package_name.replace('/', '-') / version
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists():
                shutil.rmtree(dest)
            shutil.copytree(pkg_dir, dest)
            print(f"Proto-loader package copied to: {dest}")
            print(f"  Add to package.json: \"{package_name}\": \"file:{dest}\"")
        elif publish_mode == 'link':
            result = subprocess.run(
                ['npm', 'link'],
                cwd=pkg_dir,
                capture_output=True,
                text=True,
            )
            if result.returncode != 0:
                print(f"Failed to link: {result.stderr}")
                return False
            print(f"Proto-loader package linked: {package_name}")
        elif publish_mode == 'workspace':
            import pkg_editor_generated as pkg_editor

            js_targets_str = os.environ.get('JS_TARGETS', '')
            if not js_targets_str.strip():
                print("Warning: workspace mode but JS_TARGETS not set", file=sys.stderr)
                return True

            js_targets = [t.strip() for t in js_targets_str.replace('\n', ',').split(',') if t.strip()]
            all_ok = True
            for target_dir in js_targets:
                if not os.path.exists(os.path.join(target_dir, 'package.json')):
                    print(f"Warning: no package.json at {target_dir}, skipping", file=sys.stderr)
                    continue
                ok = pkg_editor.install_package_to_workspace(str(pkg_dir), package_name, target_dir)
                if ok:
                    print(f"\u2713 Installed proto-loader {package_name} to {target_dir}/protolake/")
                else:
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

            print(f"Publishing proto-loader package to registry: {registry_url}")
            result = subprocess.run(
                ['npm', 'publish', f'--registry={registry_url}'],
                cwd=pkg_dir,
                capture_output=True,
                text=True,
            )
            if result.returncode == 0:
                print(f"Proto-loader package published: {package_name}@{version}")
            else:
                print(f"Failed to publish: {result.stderr}")
                if result.stdout:
                    print(f"  stdout: {result.stdout}")
                return False
        else:
            print(f"Unknown publish mode: {publish_mode}")
            print("Supported modes: skip, file, link, workspace, registry")
            return False

    return True


def main():
    parser = argparse.ArgumentParser(description='Build proto-loader npm package')

    # When called as a Bazel tool, args come via the args list
    parser.add_argument('--output', help='Output .tgz path')
    parser.add_argument('--package-name', help='NPM package name')
    parser.add_argument('--version', default='1.0.0', help='Package version')
    parser.add_argument('--proto-sources', nargs='*', default=[], help='Proto source files (src=dest pairs)')

    args, remaining = parser.parse_known_args()

    if args.output:
        # Build mode - create the package
        version = os.environ.get('VERSION', args.version)
        success = create_proto_loader_package(
            args.output,
            args.package_name,
            version,
            args.proto_sources,
        )
    elif remaining:
        # Publish mode - extract and publish
        bundle_path = remaining[0]
        coords_path = remaining[1] if len(remaining) > 1 else None
        if not coords_path:
            print("Usage: proto_loader_publisher.py <bundle.tgz> <coords.txt>")
            sys.exit(1)
        success = publish_proto_loader_package(bundle_path, coords_path)
    else:
        parser.print_help()
        sys.exit(1)

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
