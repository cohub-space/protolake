#!/usr/bin/env python3
"""Maven publisher for Proto Lake bundles"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_jar_name(jar_path):
    """Extract Maven coordinates from JAR filename"""
    # Expected format: artifact-id-version.jar
    filename = os.path.basename(jar_path)
    if filename.endswith("_bundle.jar"):
        filename = filename[:-11]  # Remove _bundle.jar

    # Read coordinates from JAR manifest or embedded metadata
    # For now, parse from command line args
    return None


def generate_pom(group_id, artifact_id, version, description=None):
    """Generate a minimal POM file"""
    project = ET.Element("project")
    project.set("xmlns", "http://maven.apache.org/POM/4.0.0")
    project.set("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
    project.set("xsi:schemaLocation",
                "http://maven.apache.org/POM/4.0.0 " +
                "http://maven.apache.org/xsd/maven-4.0.0.xsd")

    ET.SubElement(project, "modelVersion").text = "4.0.0"
    ET.SubElement(project, "groupId").text = group_id
    ET.SubElement(project, "artifactId").text = artifact_id
    ET.SubElement(project, "version").text = version
    ET.SubElement(project, "packaging").text = "jar"

    if description:
        ET.SubElement(project, "description").text = description

    # Add protobuf dependency
    dependencies = ET.SubElement(project, "dependencies")
    protobuf_dep = ET.SubElement(dependencies, "dependency")
    ET.SubElement(protobuf_dep, "groupId").text = "com.google.protobuf"
    ET.SubElement(protobuf_dep, "artifactId").text = "protobuf-java"
    ET.SubElement(protobuf_dep, "version").text = "3.25.3"  # Fixed version

    return ET.tostring(project, encoding="unicode", method="xml")


def calculate_checksums(file_path):
    """Calculate MD5 and SHA1 checksums"""
    md5 = hashlib.md5()
    sha1 = hashlib.sha1()

    with open(file_path, 'rb') as f:
        for chunk in iter(lambda: f.read(4096), b''):
            md5.update(chunk)
            sha1.update(chunk)

    return {
        'md5': md5.hexdigest(),
        'sha1': sha1.hexdigest()
    }


def publish_to_local_repo(jar_path, group_id, artifact_id, version, repo_path):
    """Publish JAR to local Maven repository"""
    # Convert group ID to path
    group_path = group_id.replace('.', '/')

    # Create directory structure
    artifact_dir = Path(repo_path) / group_path / artifact_id / version
    artifact_dir.mkdir(parents=True, exist_ok=True)

    # Copy JAR
    jar_name = f"{artifact_id}-{version}.jar"
    target_jar = artifact_dir / jar_name
    shutil.copy2(jar_path, target_jar)
    print(f"Copied JAR to {target_jar}")

    # Generate POM
    pom_content = generate_pom(group_id, artifact_id, version,
                               description=f"Proto definitions for {artifact_id}")
    pom_path = artifact_dir / f"{artifact_id}-{version}.pom"
    pom_path.write_text(pom_content)
    print(f"Generated POM at {pom_path}")

    # Generate checksums
    for file_path in [target_jar, pom_path]:
        checksums = calculate_checksums(file_path)

        # Write MD5
        md5_path = file_path.with_suffix(file_path.suffix + '.md5')
        md5_path.write_text(checksums['md5'])

        # Write SHA1
        sha1_path = file_path.with_suffix(file_path.suffix + '.sha1')
        sha1_path.write_text(checksums['sha1'])

    print(f"Generated checksums for {jar_name}")

    # Update maven-metadata.xml
    update_maven_metadata(artifact_dir.parent, artifact_id, version)

    return target_jar


def update_maven_metadata(artifact_dir, artifact_id, version):
    """Update maven-metadata.xml with new version"""
    metadata_path = artifact_dir / "maven-metadata.xml"

    if metadata_path.exists():
        # Parse existing metadata
        tree = ET.parse(metadata_path)
        root = tree.getroot()
    else:
        # Create new metadata
        root = ET.Element("metadata")
        ET.SubElement(root, "groupId").text = str(artifact_dir.parent.name)
        ET.SubElement(root, "artifactId").text = artifact_id

    # Update versioning
    versioning = root.find("versioning")
    if versioning is None:
        versioning = ET.SubElement(root, "versioning")

    # Update latest version
    latest = versioning.find("latest")
    if latest is None:
        latest = ET.SubElement(versioning, "latest")
    latest.text = version

    # Update release version
    release = versioning.find("release")
    if release is None:
        release = ET.SubElement(versioning, "release")
    release.text = version

    # Update versions list
    versions = versioning.find("versions")
    if versions is None:
        versions = ET.SubElement(versioning, "versions")

    # Add version if not already present
    version_list = [v.text for v in versions.findall("version")]
    if version not in version_list:
        ET.SubElement(versions, "version").text = version

    # Write updated metadata
    tree = ET.ElementTree(root)
    tree.write(metadata_path, encoding="UTF-8", xml_declaration=True)

    # Generate checksums for metadata
    checksums = calculate_checksums(metadata_path)
    metadata_path.with_suffix('.xml.md5').write_text(checksums['md5'])
    metadata_path.with_suffix('.xml.sha1').write_text(checksums['sha1'])


def main():
    parser = argparse.ArgumentParser(description='Publish Proto Lake bundle to Maven')
    parser.add_argument('jar_path', help='Path to the JAR file')
    parser.add_argument('--group-id', required=True,
                        help='Maven group ID')
    parser.add_argument('--artifact-id', required=True,
                        help='Maven artifact ID')
    parser.add_argument('--version', default='1.0.0',
                        help='Version (default: 1.0.0)')
    parser.add_argument('--repo', default=os.path.expanduser('~/.m2/repository'),
                        help='Maven repository path (default: ~/.m2/repository)')
    parser.add_argument('--skip-checksums', action='store_true',
                        help='Skip generating checksums')

    args = parser.parse_args()

    # Verify JAR exists
    if not os.path.exists(args.jar_path):
        print(f"Error: JAR file not found: {args.jar_path}", file=sys.stderr)
        sys.exit(1)

    try:
        published_jar = publish_to_local_repo(
            args.jar_path,
            args.group_id,
            args.artifact_id,
            args.version,
            args.repo
        )

        print(f"\nSuccessfully published to Maven repository:")
        print(f"  Group ID: {args.group_id}")
        print(f"  Artifact ID: {args.artifact_id}")
        print(f"  Version: {args.version}")
        print(f"  Location: {published_jar}")

    except Exception as e:
        print(f"Error publishing to Maven: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()