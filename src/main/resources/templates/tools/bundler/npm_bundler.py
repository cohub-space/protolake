#!/usr/bin/env python3
"""Creates NPM packages for Connect-ES v2 proto bundles (ESM with @bufbuild/protobuf)"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def create_package_json(package_name, version):
    """Create package.json for Connect-ES ESM package"""
    package_json = {
        "name": package_name,
        "version": version,
        "description": f"Proto definitions for {package_name}",
        "type": "module",
        "main": "./index.js",
        "types": "./index.d.ts",
        "exports": {
            ".": {
                "import": "./index.js",
                "types": "./index.d.ts"
            },
            "./*": {
                "import": "./*",
                "types": "./*"
            }
        },
        "peerDependencies": {
            "@bufbuild/protobuf": "^2.0.0",
            "@connectrpc/connect": "^2.0.0"
        },
        "files": [
            "**/*.js",
            "**/*.d.ts",
            "**/*.proto"
        ]
    }

    return json.dumps(package_json, indent=2)


def strip_bazel_path(path):
    """Strip Bazel-specific prefixes from paths"""
    original = path

    # Handle bazel-out paths
    if path.startswith('bazel-out/'):
        # Find /bin/ and skip past it
        bin_idx = path.find('/bin/')
        if bin_idx > 0:
            path = path[bin_idx + 5:]  # Skip past "/bin/"

    # Handle external repository paths
    elif path.startswith('external/'):
        # Skip the external/ prefix
        path = path[9:]

    # Handle ../ prefixes that might come from external repos
    while path.startswith('../'):
        path = path[3:]

    print(f"  Path mapping: {original} -> {path}")
    return path


def copy_es_files(es_files, pkg_dir):
    """Copy Connect-ES generated files preserving directory structure"""
    copied = 0
    for file_path in es_files:
        if not os.path.exists(file_path):
            print(f"  WARNING: ES file not found: {file_path}")
            continue

        # Determine relative path
        rel_path = strip_bazel_path(file_path)
        dest = pkg_dir / rel_path

        # Create parent directories
        dest.parent.mkdir(parents=True, exist_ok=True)

        # Copy file
        shutil.copy2(file_path, dest)
        copied += 1

    return copied


def create_index_files(pkg_dir):
    """Create barrel index.js/index.d.ts re-exporting all _pb modules"""

    # Find all _pb.js files
    pb_files = sorted(pkg_dir.rglob('*_pb.js'))

    if not pb_files:
        print("Warning: No _pb.js files found for index generation")
        return

    # Generate exports
    js_exports = []
    dts_exports = []

    for pb_file in pb_files:
        rel_path = pb_file.relative_to(pkg_dir)
        module_path = './' + str(rel_path).replace('\\', '/')
        js_exports.append(f"export * from '{module_path}';")
        dts_exports.append(f"export * from '{module_path}';")

    # Write index.js
    (pkg_dir / "index.js").write_text(
        "// Auto-generated barrel export for Connect-ES proto package\n" +
        '\n'.join(js_exports) + '\n'
    )

    # Write index.d.ts
    (pkg_dir / "index.d.ts").write_text(
        "// Auto-generated barrel export for Connect-ES proto package\n" +
        '\n'.join(dts_exports) + '\n'
    )


def main():
    parser = argparse.ArgumentParser(description='Bundle Connect-ES proto libraries into NPM package')
    parser.add_argument('--output', required=True, help='Output tarball path')
    parser.add_argument('--package-name', required=True, help='NPM package name')
    parser.add_argument('--version', required=True, help='Version')
    parser.add_argument('--es-files', nargs='*', default=[], help='Connect-ES generated files (_pb.js, _pb.d.ts)')
    parser.add_argument('--proto-sources', nargs='*', default=[], help='Proto source files')
    args = parser.parse_args()

    # Handle environment variable expansion for version
    if args.version.startswith('${') and args.version.endswith('}'):
        # Extract variable name and default value
        var_content = args.version[2:-1]  # Remove ${ and }
        if ':-' in var_content:
            var_name, default_value = var_content.split(':-', 1)
            args.version = os.environ.get(var_name, default_value)
        else:
            # No default value provided
            args.version = os.environ.get(var_content, '1.0.0')

    # Ensure version is valid - if it still contains ${, use default
    if '${' in args.version:
        print(f"Warning: Version '{args.version}' contains unexpanded variables, using default '1.0.0'")
        args.version = '1.0.0'

    with tempfile.TemporaryDirectory() as tmpdir:
        print(f"Creating NPM package for {args.package_name}...")

        # Create package directory
        # NPM packages with @ need special handling
        if args.package_name.startswith('@'):
            # @scope/package -> scope-package for directory
            pkg_dir_name = args.package_name.replace('@', '').replace('/', '-')
        else:
            pkg_dir_name = args.package_name

        pkg_dir = Path(tmpdir) / pkg_dir_name
        pkg_dir.mkdir()

        # Copy Connect-ES generated files
        print("Copying Connect-ES generated files...")
        es_count = copy_es_files(args.es_files, pkg_dir)
        print(f"  Copied {es_count} ES files")

        if es_count == 0:
            print("Warning: No Connect-ES files found!")

        # Copy proto sources to package
        print("Copying proto sources...")
        proto_count = 0
        for proto_spec in args.proto_sources:
            if '=' in proto_spec:
                src, dest = proto_spec.split('=', 1)
            else:
                src = proto_spec
                dest = strip_bazel_path(proto_spec)

            if os.path.exists(src):
                dest_path = pkg_dir / dest
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dest_path)
                proto_count += 1
            else:
                print(f"  WARNING: Proto file not found: {src}")

        print(f"  Copied {proto_count} proto files")

        # Create package.json
        print("Creating package.json...")
        package_json_content = create_package_json(args.package_name, args.version)
        (pkg_dir / "package.json").write_text(package_json_content)

        # Create barrel index files
        print("Creating index files...")
        create_index_files(pkg_dir)

        # Create README
        readme = f"""# {args.package_name}

Auto-generated proto definitions package (Connect-ES v2).

## Installation

```bash
npm install {args.package_name}
```

## Usage

```javascript
import {{ SomeMessage, SomeService }} from '{args.package_name}';
```

Requires `@bufbuild/protobuf` and `@connectrpc/connect` as peer dependencies.

Generated from Proto Lake.
"""
        (pkg_dir / "README.md").write_text(readme)

        # Create tarball
        print(f"Creating tarball: {args.output}")
        # Convert output to absolute path since we're running tar from tmpdir
        output_abs = os.path.abspath(args.output)
        result = subprocess.run(
            ['tar', 'czf', output_abs, pkg_dir_name],
            cwd=tmpdir,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print(f"Error creating tarball: {result.stderr}")
            sys.exit(1)

        print(f"Created NPM package: {args.output}")


if __name__ == '__main__':
    main()
