# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Connect-ES v2 proto compilation rule using protoc-gen-es.

Generates _pb.js + _pb.d.ts files containing both message types and
service descriptors (Connect-ES v2 unified output).

Uses rules_proto_grpc infrastructure with a wrapper script that
delegates to the globally-installed protoc-gen-es binary.
"""

load("@rules_proto_grpc//:defs.bzl", "proto_compile_impl",
     "proto_compile_attrs", "proto_compile_toolchains")
# Note: ProtoPluginInfo is from rules_proto_grpc internal API (no public export available)
load("@rules_proto_grpc//internal:providers.bzl", "ProtoPluginInfo")

es_proto_compile = rule(
    implementation = proto_compile_impl,
    attrs = dict(proto_compile_attrs, _plugins = attr.label_list(
        default = [Label("//tools:protoc_gen_es_plugin")],
        providers = [ProtoPluginInfo],
        cfg = "exec",
    )),
    toolchains = proto_compile_toolchains,
)
