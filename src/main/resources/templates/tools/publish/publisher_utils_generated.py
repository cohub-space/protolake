#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Common utilities for Proto Lake publishers"""

import hashlib
import os
from pathlib import Path


def ensure_directory_exists(path):
    """Ensure a directory exists, creating it if necessary."""
    Path(path).mkdir(parents=True, exist_ok=True)


def calculate_checksum(file_path, algorithm='sha256'):
    """Calculate checksum of a file."""
    hash_func = hashlib.new(algorithm)

    with open(file_path, 'rb') as f:
        for chunk in iter(lambda: f.read(4096), b''):
            hash_func.update(chunk)

    return hash_func.hexdigest()


def generate_checksums(file_path):
    """Generate MD5 and SHA1 checksums for a file."""
    md5_checksum = calculate_checksum(file_path, 'md5')
    sha1_checksum = calculate_checksum(file_path, 'sha1')

    # Write checksum files
    md5_path = f"{file_path}.md5"
    sha1_path = f"{file_path}.sha1"

    with open(md5_path, 'w') as f:
        f.write(md5_checksum)

    with open(sha1_path, 'w') as f:
        f.write(sha1_checksum)

    return {
        'md5': md5_checksum,
        'sha1': sha1_checksum
    }


def create_maven_metadata(group_id, artifact_id, versions, latest_version):
    """Create maven-metadata.xml content."""
    versions_xml = '\n'.join([f'      <version>{v}</version>' for v in sorted(versions)])

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>{group_id}</groupId>
  <artifactId>{artifact_id}</artifactId>
  <versioning>
    <latest>{latest_version}</latest>
    <release>{latest_version}</release>
    <versions>
{versions_xml}
    </versions>
    <lastUpdated>{get_timestamp()}</lastUpdated>
  </versioning>
</metadata>
"""


def get_timestamp():
    """Get current timestamp in Maven format."""
    from datetime import datetime
    return datetime.now().strftime('%Y%m%d%H%M%S')


def update_local_maven_metadata(repo_path, group_id, artifact_id, new_version):
    """Update maven-metadata.xml in local repository."""
    # Convert group ID to path
    group_parts = group_id.split('.')
    artifact_base = Path(repo_path) / os.path.join(*group_parts) / artifact_id
    metadata_file = artifact_base / "maven-metadata-local.xml"

    # Read existing versions
    versions = set()
    if metadata_file.exists():
        # Parse existing metadata
        import xml.etree.ElementTree as ET
        tree = ET.parse(metadata_file)
        root = tree.getroot()
        versioning = root.find('versioning')
        if versioning:
            for version in versioning.findall('.//version'):
                versions.add(version.text)

    # Add new version
    versions.add(new_version)

    # Generate new metadata
    metadata_content = create_maven_metadata(group_id, artifact_id, versions, new_version)
    metadata_file.write_text(metadata_content)

    # Generate checksums for metadata
    generate_checksums(metadata_file)