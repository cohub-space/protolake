# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""API specs and exposure checks for each bundle.

protolake applies the aspect to every bundle's `<bundle>_all_protos` target
after the main build (ApiSpecRunner), with each bundle's version:

    bazel build --aspects=//tools:api_specs.bzl%api_specs \\
        --aspects_parameters=bundle_versions=<bundle dir>=<version>;... \\
        --output_groups=api_specs -- //<bundle dir>:<bundle>_all_protos ...

and reads, beside the bundle's other outputs:

    <bundle>_api_specs/<bundle>.openapi.json    the google.api.http bindings
    <bundle>_api_specs/<bundle>.asyncapi.json   the WebSocket-marked RPCs
    <bundle>_api_findings.json                  the exposure checks' findings

An aspect keeps the specs out of the BUILD files gazelle writes, so every
lake gets them with the protolake image, without a gazelle release.
"""

load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

# The protoc plugins the protolake image installs (Dockerfile). Their versions
# and binary digests (one per platform the image is built for) sit in the
# action's command line, so any re-pin, even at the same version, re-runs the
# action instead of serving specs the old binary wrote from Bazel's caches;
# the tool checks that the installed binaries are these builds.
PROTOC_GEN_OPENAPIV2 = "/usr/local/bin/protoc-gen-openapiv2"
PROTOC_GEN_OPENAPIV2_VERSION = "2.31.0"
PROTOC_GEN_OPENAPIV2_SHA256 = [
    "a05f17a2a934692cc9d0b46a5a597f23cf2f1e34c76e0a457943aa5afffa33ba",  # linux amd64
    "6240a03543695ed7dfad404765cc681c7bcc7760773c479d37cdd80f7840e000",  # linux arm64
]
PROTOC_GEN_CONNECT_OPENAPI = "/usr/local/bin/protoc-gen-connect-openapi"
PROTOC_GEN_CONNECT_OPENAPI_VERSION = "0.28.0"
PROTOC_GEN_CONNECT_OPENAPI_SHA256 = [
    "fdaa7b60734db2513727f56bb5415406c76d1490cb14827a1af38dd32cb5d080",  # linux amd64
    "662c2ae8fa8c0bd0deb2f4068577b8809dc69b10645c4d12dc1d22ea4f90d3ab",  # linux arm64
]

_SUFFIX = "_all_protos"

def _versions(value):
    versions = {}
    for entry in value.split(";"):
        if "=" in entry:
            package, version = entry.split("=", 1)
            versions[package] = version
    return versions

def _import_path(source, root):
    if root and root != "." and source.path.startswith(root + "/"):
        return source.path[len(root) + 1:]
    return source.short_path

def _api_specs_impl(target, ctx):
    if ctx.rule.kind != "proto_library" or not ctx.label.name.endswith(_SUFFIX):
        return []
    bundle = ctx.label.name[:-len(_SUFFIX)]
    version = _versions(ctx.attr.bundle_versions).get(ctx.label.package)
    if not version:
        fail("protolake passes each bundle's version through bundle_versions; %s has none. " % ctx.label +
             "Build the lake with protolakew.")

    # Bazel's own descriptor sets carry no comments, which the specs' descriptions
    # come from, so the bundle's files are compiled again with source info; their
    # imports come from the existing sets.
    own = [dep[ProtoInfo] for dep in ctx.rule.attr.deps if ProtoInfo in dep]
    own_sets = [info.direct_descriptor_set for info in own]
    imports = [s for s in target[ProtoInfo].transitive_descriptor_sets.to_list() if s not in own_sets]
    sources = []
    roots = []
    for info in own:
        root = info.proto_source_root
        if root not in roots:
            roots.append(root)
        sources.extend([(source, _import_path(source, root)) for source in info.direct_sources])

    descriptor = ctx.actions.declare_file(bundle + "_api_descriptor.pb")
    protoc_args = ctx.actions.args()
    if imports:
        protoc_args.add_joined(imports, join_with = ctx.configuration.host_path_separator, format_joined = "--descriptor_set_in=%s")
    for root in roots:
        protoc_args.add("--proto_path=" + (root if root else "."))
    protoc_args.add("--include_imports")
    protoc_args.add("--include_source_info")
    protoc_args.add("--retain_options")
    protoc_args.add("--descriptor_set_out=" + descriptor.path)
    protoc_args.add_all([path for _, path in sources])
    ctx.actions.run(
        executable = ctx.executable._protoc,
        arguments = [protoc_args],
        inputs = depset([source for source, _ in sources] + imports),
        outputs = [descriptor],
        mnemonic = "ProtolakeApiDescriptor",
        progress_message = "Compiling the API descriptor of bundle %s" % bundle,
    )

    specs = ctx.actions.declare_directory(bundle + "_api_specs")
    findings = ctx.actions.declare_file(bundle + "_api_findings.json")
    args = ctx.actions.args()
    args.add("--bundle", bundle)
    args.add("--package", ctx.label.package)
    args.add("--bundle-version", version)
    args.add("--descriptor-set", descriptor)
    args.add("--out-dir", specs.path)
    args.add("--findings", findings.path)
    args.add("--openapiv2", PROTOC_GEN_OPENAPIV2)
    args.add("--openapiv2-version", PROTOC_GEN_OPENAPIV2_VERSION)
    args.add("--openapiv2-sha256", ",".join(PROTOC_GEN_OPENAPIV2_SHA256))
    args.add("--connect-openapi", PROTOC_GEN_CONNECT_OPENAPI)
    args.add("--connect-openapi-version", PROTOC_GEN_CONNECT_OPENAPI_VERSION)
    args.add("--connect-openapi-sha256", ",".join(PROTOC_GEN_CONNECT_OPENAPI_SHA256))
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = [descriptor],
        outputs = [specs, findings],
        mnemonic = "ProtolakeApiSpecs",
        progress_message = "Writing the API specs of bundle %s" % bundle,
        # The plugins live in the image, outside the execroot; the cache key
        # covers them through the pins above, not through their files.
        execution_requirements = {"no-sandbox": "1", "no-remote": "1"},
    )
    return [OutputGroupInfo(api_specs = depset([specs, findings]))]

api_specs = aspect(
    implementation = _api_specs_impl,
    attrs = {
        "bundle_versions": attr.string(
            default = "",
            doc = "<bundle dir>=<version> entries separated by ';', from protolake.",
        ),
        "_protoc": attr.label(
            default = "@com_google_protobuf//:protoc",
            executable = True,
            cfg = "exec",
        ),
        "_tool": attr.label(
            default = "//tools:api_specs_tool",
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Checks a bundle's exposure annotations and writes its OpenAPI and AsyncAPI specs.",
)
