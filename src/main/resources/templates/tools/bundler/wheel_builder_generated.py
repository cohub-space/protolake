#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Creates Python wheels for proto bundles"""

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
    with stdlib only, so no yaml library. Fails loudly when the file is
    unreadable, no version line is found, or the version is malformed.
    """
    version = None
    try:
        with open(bundle_yaml_path, encoding='utf-8') as f:
            for line in f:
                match = re.match(r"^version:\s*['\"]?([^'\"\s]+)", line)
                if match:
                    version = match.group(1)
                    break
    except OSError as e:
        print(f"Error: cannot read bundle.yaml at {bundle_yaml_path}: {e}",
              file=sys.stderr)
        sys.exit(1)
    if version is None:
        print(f"Error: no top-level 'version:' line found in {bundle_yaml_path}",
              file=sys.stderr)
        sys.exit(1)
    if not re.fullmatch(r'[0-9A-Za-z.+~-]+', version):
        print(f"Error: malformed version {version!r} in {bundle_yaml_path}",
              file=sys.stderr)
        sys.exit(1)
    return version


def create_setup_py(package_name, version):
    """Create setup.py content for the wheel"""
    # Use double braces to escape them in f-string
    return f"""
from setuptools import setup, find_packages

setup(
    name="{package_name}",
    version="{version}",
    packages=find_packages(),
    include_package_data=True,
    package_data={{"": ["*.proto", "*.pyi"]}},
    install_requires=[
        "protobuf>=3.20.0",
        "grpcio>=1.50.0",
    ],
    python_requires=">=3.7",
    description="Proto Bundle for {package_name}",
    author="Proto Lake",
    classifiers=[
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
    ],
)
"""


def create_manifest_in():
    """Create MANIFEST.in to include proto files"""
    return """
# Include all proto files
recursive-include * *.proto

# Include all generated Python files
recursive-include * *.py *.pyi

# Exclude build artifacts
global-exclude __pycache__
global-exclude *.pyc
global-exclude *.pyo
"""


def strip_bazel_path(path):
    """Strip Bazel-specific prefixes from paths to get clean proto paths"""
    original = path

    # Handle bazel-out paths (e.g., bazel-out/k8-fastbuild/bin/...)
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

    # Handle _virtual_imports paths for proto files
    if '_virtual_imports/' in path:
        # Extract the actual proto path after _virtual_imports/*/
        parts = path.split('_virtual_imports/')
        if len(parts) > 1:
            after_virtual = parts[1]
            # Skip the first directory (import name)
            slash_idx = after_virtual.find('/')
            if slash_idx > 0:
                path = after_virtual[slash_idx + 1:]

    print(f"  Path mapping: {original} -> {path}")
    return path


def main():
    parser = argparse.ArgumentParser(description='Bundle Python proto libraries into wheel')
    parser.add_argument('--output', required=True, help='Output wheel path')
    parser.add_argument('--package-name', required=True, help='PyPI package name')
    parser.add_argument('--bundle-yaml', required=True,
                        help="Path to the bundle's bundle.yaml; the version is read from it at build time")
    parser.add_argument('--py-files', nargs='*', default=[], help='Python files')
    parser.add_argument('--proto-sources', nargs='*', default=[], help='Proto source files')

    args = parser.parse_args()

    # bundle.yaml is the single source of truth for the bundle version.
    args.version = read_bundle_version(args.bundle_yaml)

    # Debug: Show what we received
    print(f"Received {len(args.py_files)} Python files")
    print(f"Received {len(args.proto_sources)} proto sources")

    with tempfile.TemporaryDirectory() as tmpdir:
        print(f"Creating Python wheel for {args.package_name}...")

        # Copy Python files maintaining structure
        print("Copying Python files...")
        for py_file in args.py_files:
            if os.path.exists(py_file):
                # Determine relative path with proper stripping
                dest = strip_bazel_path(py_file)

                dest_path = Path(tmpdir) / dest
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(py_file, dest_path)

                # Create __init__.py files for all parent directories
                current = dest_path.parent
                while current != Path(tmpdir):
                    init_file = current / "__init__.py"
                    if not init_file.exists():
                        init_file.touch()
                    current = current.parent
            else:
                print(f"  WARNING: Python file not found: {py_file}")

        # Copy proto sources to root (same as Java JAR structure)
        print("Copying proto sources...")
        proto_count = 0
        for proto_spec in args.proto_sources:
            if '=' in proto_spec:
                src, dest = proto_spec.split('=', 1)
            else:
                src = proto_spec
                dest = strip_bazel_path(src)

            if os.path.exists(src):
                # Copy directly to tmpdir root, not a subdirectory
                dest_path = Path(tmpdir) / dest
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dest_path)
                proto_count += 1
            else:
                print(f"  WARNING: Proto source file not found: {src}")

        print(f"  Copied {proto_count} proto files")

        # Create setup.py
        setup_content = create_setup_py(args.package_name, args.version)
        (Path(tmpdir) / "setup.py").write_text(setup_content)

        # Create MANIFEST.in
        manifest_content = create_manifest_in()
        (Path(tmpdir) / "MANIFEST.in").write_text(manifest_content)

        # Debug: Show what's in the temp directory
        print("\nContents of wheel staging directory:")
        for root, dirs, files in os.walk(tmpdir):
            level = root.replace(tmpdir, '').count(os.sep)
            indent = ' ' * 2 * level
            print(f"{indent}{os.path.basename(root)}/")
            subindent = ' ' * 2 * (level + 1)
            for file in files[:10]:  # Limit to first 10 files per dir
                if file.endswith('.proto'):
                    print(f"{subindent}{file} (PROTO)")
                elif file.endswith('.py'):
                    print(f"{subindent}{file}")

        # Build the wheel
        print("\nBuilding wheel...")
        result = subprocess.run(
            [sys.executable, "-m", "pip", "wheel", ".", "--wheel-dir", ".", "--no-deps"],
            cwd=tmpdir,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print(f"Error building wheel: {result.stderr}", file=sys.stderr)
            sys.exit(1)

        # Find the generated wheel
        wheels = list(Path(tmpdir).glob("*.whl"))
        if not wheels:
            print("Error: No wheel file generated", file=sys.stderr)
            sys.exit(1)

        # Copy to output location
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(wheels[0], output_path)

        print(f"\nSuccessfully created {args.output}")
        print(f"  Package: {args.package_name}")
        print(f"  Version: {args.version}")


if __name__ == '__main__':
    main()