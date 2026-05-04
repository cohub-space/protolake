#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Generate a Maven POM XML for a proto bundle.

Writes POM XML to stdout. Consumed by a `genrule` sibling of
`maven_publish` (rules_jvm_external) — `maven_publish.pom` takes a
generated POM file and rules_jvm_external substitutes the coordinates
at publish time.

The actual upload (HTTP PUT to AR, checksums, retries) is handled by
`maven_publish` itself; this script's only job is the POM payload.
"""

import argparse
import sys
import xml.etree.ElementTree as ET


def generate_pom(group_id, artifact_id, version, description=None,
                 protobuf_version="4.33.5", grpc_version="1.78.0",
                 extra_deps=None):
    """Build the POM ElementTree.

    extra_deps: iterable of "groupId:artifactId:version" strings for any
    deps beyond the protobuf/grpc baseline (e.g. cross-bundle proto
    libraries). Caller controls ordering.
    """
    project = ET.Element("project")
    project.set("xmlns", "http://maven.apache.org/POM/4.0.0")
    project.set("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
    project.set("xsi:schemaLocation",
                "http://maven.apache.org/POM/4.0.0 "
                "http://maven.apache.org/xsd/maven-4.0.0.xsd")

    ET.SubElement(project, "modelVersion").text = "4.0.0"
    ET.SubElement(project, "groupId").text = group_id
    ET.SubElement(project, "artifactId").text = artifact_id
    ET.SubElement(project, "version").text = version
    ET.SubElement(project, "packaging").text = "jar"

    if description:
        ET.SubElement(project, "description").text = description

    dependencies = ET.SubElement(project, "dependencies")

    _add_dep(dependencies, "com.google.protobuf", "protobuf-java",
             protobuf_version)
    _add_dep(dependencies, "io.grpc", "grpc-protobuf", grpc_version)
    _add_dep(dependencies, "io.grpc", "grpc-stub", grpc_version)

    for coord in extra_deps or ():
        parts = coord.split(":")
        if len(parts) != 3:
            raise SystemExit(
                f"--maven-dep must be 'groupId:artifactId:version', got: {coord!r}"
            )
        _add_dep(dependencies, *parts)

    return project


def _add_dep(parent, group_id, artifact_id, version):
    dep = ET.SubElement(parent, "dependency")
    ET.SubElement(dep, "groupId").text = group_id
    ET.SubElement(dep, "artifactId").text = artifact_id
    ET.SubElement(dep, "version").text = version


def main():
    parser = argparse.ArgumentParser(description="Generate a Maven POM XML")
    parser.add_argument("--group-id", required=True)
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--description")
    parser.add_argument("--protobuf-version", default="4.33.5")
    parser.add_argument("--grpc-version", default="1.78.0")
    parser.add_argument("--maven-dep", action="append", default=[],
                        help="Additional dependency in 'groupId:artifactId:version' "
                             "form. Repeatable.")
    parser.add_argument("--out", help="Output file (default: stdout)")

    args = parser.parse_args()

    project = generate_pom(
        args.group_id,
        args.artifact_id,
        args.version,
        description=args.description,
        protobuf_version=args.protobuf_version,
        grpc_version=args.grpc_version,
        extra_deps=args.maven_dep,
    )

    xml_str = '<?xml version="1.0" encoding="UTF-8"?>\n' + \
              ET.tostring(project, encoding="unicode", method="xml")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(xml_str)
    else:
        sys.stdout.write(xml_str)


if __name__ == "__main__":
    main()
