#!/usr/bin/env python3
"""Creates NPM packages for JavaScript/TypeScript proto bundles with dual module support"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def create_package_json(package_name, version, has_typescript, module_format="dual"):
    """Create package.json with dual module support"""
    package_json = {
        "name": package_name,
        "version": version,
        "description": f"Proto definitions for {package_name}",
        "dependencies": {
            "google-protobuf": "^3.21.2",
            "@grpc/grpc-js": "^1.9.0",
            "grpc-web": "^1.5.0"
        }
    }

    if module_format == "dual":
        # Dual module support with subpath exports
        package_json.update({
            "main": "./index.cjs",
            "module": "./index.mjs",
            "types": "./index.d.ts",
            "exports": {
                ".": {
                    "import": "./index.mjs",
                    "require": "./index.cjs",
                    "types": "./index.d.ts"
                },
                "./node": {
                    "import": "./node/index.mjs",
                    "require": "./node/index.cjs",
                    "types": "./node/index.d.ts"
                },
                "./web": {
                    "import": "./web/index.mjs",
                    "require": "./web/index.cjs",
                    "types": "./web/index.d.ts"
                }
            },
            "files": [
                "**/*.js",
                "**/*.cjs",
                "**/*.mjs",
                "**/*.d.ts",
                "**/*.proto"
            ]
        })
    else:
        # Single module format
        package_json["main"] = "./index.js"
        package_json["types"] = "./index.d.ts"
        if module_format == "esm":
            package_json["type"] = "module"

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


def organize_js_files(js_files, pkg_dir):
    """Organize JS files into node/ and web/ subdirectories"""
    node_files = []
    web_files = []

    for js_file in js_files:
        if os.path.exists(js_file):
            # Determine if this is a web or node file
            if 'grpc_web' in js_file or 'grpc-web' in js_file:
                web_files.append(js_file)
            else:
                node_files.append(js_file)

    # Copy files to appropriate subdirectories
    if node_files:
        node_dir = pkg_dir / 'node'
        node_dir.mkdir(exist_ok=True)
        copy_files_to_subdir(node_files, node_dir)

    if web_files:
        web_dir = pkg_dir / 'web'
        web_dir.mkdir(exist_ok=True)
        copy_files_to_subdir(web_files, web_dir)

    return bool(node_files), bool(web_files)


def copy_files_to_subdir(files, subdir):
    """Copy files to subdirectory maintaining structure"""
    for file_path in files:
        # Determine relative path
        rel_path = strip_bazel_path(file_path)
        dest = subdir / rel_path

        # Create parent directories
        dest.parent.mkdir(parents=True, exist_ok=True)

        # Copy file
        shutil.copy2(file_path, dest)

        # Also generate .mjs and .cjs wrappers if it's a .js file
        if dest.suffix == '.js':
            create_module_wrappers(dest)


def create_module_wrappers(js_file):
    """Create .mjs and .cjs wrapper files for a .js file"""
    js_path = Path(js_file)
    content = js_path.read_text()

    # Create .cjs wrapper (CommonJS)
    cjs_path = js_path.with_suffix('.cjs')
    cjs_path.write_text(content)

    # Create .mjs wrapper (ES Module)
    # This is simplified - real implementation would need proper conversion
    mjs_path = js_path.with_suffix('.mjs')
    mjs_content = content
    # Basic conversion: replace require() with import
    if 'require(' in mjs_content:
        # This is a simplified conversion - production would need proper AST transformation
        mjs_content = "// Auto-converted to ESM\n" + mjs_content
    mjs_path.write_text(mjs_content)


def create_index_files(pkg_dir, has_node, has_web):
    """Create index files that re-export everything"""

    # Main index.cjs (CommonJS)
    cjs_content = """// Auto-generated CommonJS index
"""
    if has_node:
        cjs_content += "exports.node = require('./node/index.cjs');\n"
    if has_web:
        cjs_content += "exports.web = require('./web/index.cjs');\n"

    # Main index.mjs (ESM)
    esm_content = """// Auto-generated ESM index
"""
    if has_node:
        esm_content += "export * as node from './node/index.mjs';\n"
    if has_web:
        esm_content += "export * as web from './web/index.mjs';\n"

    (pkg_dir / "index.cjs").write_text(cjs_content)
    (pkg_dir / "index.mjs").write_text(esm_content)

    # Create wrapper index.d.ts
    dts_content = """// Auto-generated TypeScript definitions
"""
    if has_node:
        dts_content += "export * as node from './node';\n"
    if has_web:
        dts_content += "export * as web from './web';\n"

    (pkg_dir / "index.d.ts").write_text(dts_content)

    # Create subdir index files
    if has_node:
        create_subdir_index(pkg_dir / 'node')
    if has_web:
        create_subdir_index(pkg_dir / 'web')


def create_subdir_index(subdir):
    """Create index files for a subdirectory"""
    # Find all JS files
    js_files = list(subdir.rglob('*.js'))

    # Create index.js that exports everything
    exports = []
    for js_file in js_files:
        rel_path = js_file.relative_to(subdir).with_suffix('')
        module_path = './' + str(rel_path).replace('\\', '/')
        export_name = str(rel_path).replace('/', '_').replace('\\', '_')
        exports.append(f"export * as {export_name} from '{module_path}';")

    index_js = subdir / 'index.js'
    index_js.write_text('\n'.join(exports))

    # Create .cjs and .mjs versions
    create_module_wrappers(index_js)

    # Create index.d.ts
    index_dts = subdir / 'index.d.ts'
    index_dts.write_text(index_js.read_text())


def main():
    parser = argparse.ArgumentParser(description='Bundle JavaScript proto libraries into NPM package')
    parser.add_argument('--output', required=True, help='Output tarball path')
    parser.add_argument('--package-name', required=True, help='NPM package name')
    parser.add_argument('--version', required=True, help='Version')
    parser.add_argument('--js-files', nargs='*', default=[], help='JavaScript files')
    parser.add_argument('--proto-sources', nargs='*', default=[], help='Proto source files')
    parser.add_argument('--typescript', action='store_true', help='Include TypeScript definitions')
    parser.add_argument('--module-format', default='dual',
                        choices=['commonjs', 'esm', 'dual'],
                        help='Module format to generate')

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

        # Copy and organize JavaScript files
        print("Organizing JavaScript files...")
        has_node, has_web = organize_js_files(args.js_files, pkg_dir)

        if not has_node and not has_web:
            print("Warning: No JavaScript files found!")

        # Copy proto sources to package root
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
        package_json_content = create_package_json(
            args.package_name,
            args.version,
            args.typescript,
            args.module_format
        )
        (pkg_dir / "package.json").write_text(package_json_content)

        # Create index files
        print("Creating index files...")
        create_index_files(pkg_dir, has_node, has_web)

        # Create README
        readme = f"""# {args.package_name}

Auto-generated proto definitions package.

## Installation

```bash
npm install {args.package_name}
```

## Usage

### Node.js (gRPC)
```javascript
import {{ node }} from '{args.package_name}';
// or
const {{ node }} = require('{args.package_name}').node;
```

### Browser (gRPC-Web)
```javascript
import {{ web }} from '{args.package_name}';
// or  
const {{ web }} = require('{args.package_name}').web;
```

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

        print(f"✓ Created NPM package: {args.output}")


if __name__ == '__main__':
    main()