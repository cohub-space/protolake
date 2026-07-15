#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Creates fat JARs for proto bundles"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
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
    parser.add_argument('--bundle-yaml', required=True,
                        help="Path to the bundle's bundle.yaml; the version is read from it at build time")
    parser.add_argument('--jandex-jar', default=None,
                        help='Path to Jandex CLI JAR for generating META-INF/jandex.idx')
    parser.add_argument('--descriptor-pb', default=None,
                        help='Optional proto descriptor (.pb) to pack as META-INF/proto-descriptors/<bundle>.pb')
    parser.add_argument('--bundle-name', default=None,
                        help='Bundle name used as descriptor filename in META-INF (defaults to artifact_id)')

    args = parser.parse_args()

    # bundle.yaml is the single source of truth for the bundle version.
    args.version = read_bundle_version(args.bundle_yaml)

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

        # Pack proto descriptor under META-INF/proto-descriptors/<bundle>.pb
        if args.descriptor_pb:
            if os.path.exists(args.descriptor_pb):
                pb_name = (args.bundle_name or args.artifact_id) + '.pb'
                dest_path = meta_inf / 'proto-descriptors' / pb_name
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(args.descriptor_pb, dest_path)
                print(f"  Packed descriptor {args.descriptor_pb} -> META-INF/proto-descriptors/{pb_name}")
            else:
                print(f"Warning: --descriptor-pb '{args.descriptor_pb}' does not exist; skipping")

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