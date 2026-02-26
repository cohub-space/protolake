#!/bin/bash
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
# Wrapper for globally-installed protoc-gen-es binary.
# Used by rules_proto_grpc proto_plugin to invoke the Connect-ES codegen.
# Requires --strategy=ProtoCompile=local in .bazelrc so the action can
# access the host filesystem.
exec /usr/local/bin/protoc-gen-es "$@"
