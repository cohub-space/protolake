#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Creates fat JARs for proto bundles"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def create_manifest(group_id, artifact_id, version):
    """Create JAR manifest content"""
    return f"""Manifest-Version: 1.0
Bundle-SymbolicName: {group_id}:{artifact_id}
Bundle-Version: {version}
Bundle-Name: {artifact_id} Proto Bundle
Built-By: Proto Lake
"""


def main():
    parser = argparse.ArgumentParser(description='Bundle Java proto libraries into fat JAR')
    parser.add_argument('--output', required=True, help='Output JAR path')
    parser.add_argument('--java-jars', nargs='*', default=[], help='Java JAR files to include')
    parser.add_argument('--proto-sources', nargs='*', default=[], help='Proto source files')
    parser.add_argument('--group-id', required=True, help='Maven group ID')
    parser.add_argument('--artifact-id', required=True, help='Maven artifact ID')
    parser.add_argument('--version', required=True, help='Version')
    parser.add_argument('--jandex-jar', default=None,
                        help='Path to Jandex CLI JAR for generating META-INF/jandex.idx')

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
        print(f"Creating fat JAR for {args.group_id}:{args.artifact_id}...")

        # Extract all JAR files
        print("Extracting dependency JARs...")
        for jar in args.java_jars:
            if os.path.exists(jar):
                print(f"  Extracting {os.path.basename(jar)}")
                with zipfile.ZipFile(jar, 'r') as zf:
                    # Extract everything except META-INF signatures
                    for info in zf.infolist():
                        if not (info.filename.startswith('META-INF/') and
                                (info.filename.endswith('.SF') or
                                 info.filename.endswith('.DSA') or
                                 info.filename.endswith('.RSA'))):
                            zf.extract(info, tmpdir)

        # Create META-INF directory and manifest
        meta_inf = Path(tmpdir) / 'META-INF'
        meta_inf.mkdir(exist_ok=True)

        manifest_content = create_manifest(args.group_id, args.artifact_id, args.version)
        (meta_inf / 'MANIFEST.MF').write_text(manifest_content)

        # Copy proto sources to root of JAR
        print("Copying proto sources...")
        for proto_spec in args.proto_sources:
            if '=' in proto_spec:
                src, dest = proto_spec.split('=', 1)
            else:
                src = proto_spec
                # Remove bazel-out prefixes to get clean path
                dest = src
                for prefix in ['bazel-out/', 'external/']:
                    if dest.startswith(prefix):
                        # Find the next / after the prefix
                        idx = dest.find('/', len(prefix))
                        if idx > 0:
                            dest = dest[idx + 1:]
                # Also handle _virtual_imports paths
                if '_virtual_imports/' in dest:
                    # Extract the actual proto path after _virtual_imports/*/
                    parts = dest.split('_virtual_imports/')
                    if len(parts) > 1:
                        after_virtual = parts[1]
                        # Skip the first directory (import name)
                        slash_idx = after_virtual.find('/')
                        if slash_idx > 0:
                            dest = after_virtual[slash_idx + 1:]

            if os.path.exists(src):
                dest_path = Path(tmpdir) / dest
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dest_path)
                print(f"  Copied {src} -> {dest}")

        # Create the fat JAR
        print("Creating JAR file...")
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        # Create ZIP file with all contents
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as jar:
            for root, dirs, files in os.walk(tmpdir):
                for file in files:
                    file_path = os.path.join(root, file)
                    # Add file to JAR with path relative to tmpdir
                    arcname = os.path.relpath(file_path, tmpdir)
                    jar.write(file_path, arcname)

        # Run Jandex to generate META-INF/jandex.idx (enables Quarkus gRPC service discovery)
        if args.jandex_jar:
            print("Generating Jandex index...")
            subprocess.run(
                ['java', '-jar', args.jandex_jar, '-m', str(output_path)],
                check=True,
            )
            print("  Added META-INF/jandex.idx")

        print(f"Successfully created {args.output}")
        print(f"  Group ID: {args.group_id}")
        print(f"  Artifact ID: {args.artifact_id}")
        print(f"  Version: {args.version}")


if __name__ == '__main__':
    main()