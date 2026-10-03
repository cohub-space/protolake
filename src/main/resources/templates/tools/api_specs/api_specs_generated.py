#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Checks a bundle's exposure annotations and writes its API specs.

The api_specs aspect (tools/api_specs.bzl) runs this once per bundle, on the
bundle's descriptor set. It
  1. checks the exposure annotations of the bundle's RPCs: the WebSocket
     option on a unary or server-streaming RPC is an ERROR, a
     google.api.http binding on a client- or bidi-streaming RPC a WARNING;
  2. writes <bundle>.openapi.json from the google.api.http bindings, with
     protoc-gen-openapiv2, its server-streaming responses rewritten to the
     framing Envoy's transcoder sends;
  3. writes <bundle>.asyncapi.json for the RPCs that carry the WebSocket
     option, framed as the WebSocket bridge frames them (asyncapi_generated).
A bundle without bindings gets no OpenAPI document, one without WebSocket
RPCs no AsyncAPI document. RPCs marked custom stay in both: their author
serves them, and consumers still need the contract.

The findings go to a JSON file that protolake reads after the build; an
ERROR there fails the build, before anything is published. The tool exits
0 unless it crashes, so one bundle's findings never hide another's.
"""

import argparse
import json
import os
import posixpath
import re
import subprocess
import sys

# No __pycache__ beside the modules: run from protolake's templates/, Quarkus
# would read the bytecode as a template.
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import asyncapi_generated as asyncapi  # noqa: E402
import protodesc_generated as protodesc  # noqa: E402

METHOD_OPTIONS = "google.protobuf.MethodOptions"
HTTP_OPTION = "google.api.http"
EXPOSURE_OPTION = "cohub.api.v1.exposure"

_HTTP_PATTERNS = ("get", "put", "post", "delete", "patch")

# How Envoy's gRPC-JSON transcoder frames a server stream once the ingress sets
# stream_newline_delimited: each message as a bare JSON object on its own line,
# under Content-Type application/json. OpenAPI 2.0 cannot say "stream", so the
# rewritten response carries this in a vendor extension as well as in prose.
STREAM_FRAMING_EXTENSION = "x-stream-framing"
STREAM_FRAMING = "newline-delimited"


class Findings:
    """The checks' findings and what the tool wrote, for protolake to read."""

    def __init__(self, bundle):
        self.bundle = bundle
        self.items = []
        self.specs = []
        self.asyncapi_generator = None

    def add(self, severity, message, rpc=None, location=None):
        item = {"severity": severity, "message": message}
        if rpc is not None:
            item["rpc"] = rpc.full_name
            item["file"] = rpc.file.name
        if location:
            item["line"], item["column"] = location[0], location[1]
        self.items.append(item)

    def error(self, message, rpc=None, location=None):
        self.add("ERROR", message, rpc, location)

    def warning(self, message, rpc=None, location=None):
        self.add("WARNING", message, rpc, location)

    @property
    def has_errors(self):
        return any(item["severity"] == "ERROR" for item in self.items)

    def write(self, path):
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({
                "bundle": self.bundle,
                "findings": self.items,
                "specs": self.specs,
                "asyncapi_generator": self.asyncapi_generator,
            }, handle, indent=2)
            handle.write("\n")


class Rpc:
    """One RPC of the bundle, with the exposure its annotations declare."""

    def __init__(self, pool, file, service, method):
        self.file = file
        self.service = service
        self.method = method
        self.http = pool.option(method.options, HTTP_OPTION, METHOD_OPTIONS)
        exposure = pool.option(method.options, EXPOSURE_OPTION, METHOD_OPTIONS) or {}
        self.websocket = bool(exposure.get("websocket", False))
        self._extension_numbers = {
            name: pool.extensions[name].number
            for name in (HTTP_OPTION, EXPOSURE_OPTION) if name in pool.extensions
        }

    @property
    def full_name(self):
        return self.service.full_name + "." + self.method.name

    @property
    def kind(self):
        streaming = (self.method.client_streaming, self.method.server_streaming)
        return {
            (False, False): "unary",
            (False, True): "server-streaming",
            (True, False): "client-streaming",
            (True, True): "bidi-streaming",
        }[streaming]

    @property
    def binding_count(self):
        """How many REST bindings the google.api.http option declares."""
        if not self.http:
            return 0
        rules = [self.http] + list(self.http.get("additional_bindings", []))
        return sum(1 for rule in rules if _has_pattern(rule))

    def location(self, option=None):
        """(line, column) of the option on this RPC, else of the RPC itself."""
        path = self.method.path
        number = self._extension_numbers.get(option)
        loc = None
        if number is not None:
            loc = self.file.location(path + (4, number))
        loc = loc or self.file.location(path)
        return loc[:2] if loc else None


def _has_pattern(rule):
    if any(rule.get(name) for name in _HTTP_PATTERNS):
        return True
    custom = rule.get("custom")
    return bool(custom and custom.get("path"))


def collect_rpcs(pool, files):
    """The RPCs of the given files, in declaration order.

    Whether an RPC is custom changes nothing here: its author serves it, so
    it stays in the specs like any other.
    """
    return [Rpc(pool, file, service, method)
            for file in files for service in file.services for method in service.methods]


def check_rpcs(rpcs, findings):
    """The exposure checks protolake runs when it builds a bundle."""
    for rpc in rpcs:
        if rpc.websocket and not rpc.method.client_streaming:
            findings.error(
                "%s is a %s RPC but sets (cohub.api.v1.exposure).websocket; only client- and "
                "bidi-streaming RPCs reach browsers over a WebSocket, and gRPC-web already serves "
                "unary and server-streaming RPCs to them. Remove the option."
                % (rpc.full_name, rpc.kind), rpc, rpc.location(EXPOSURE_OPTION))
        if rpc.binding_count and rpc.method.client_streaming:
            findings.warning(
                "%s is a %s RPC with a google.api.http binding; REST transcoding cannot carry a "
                "request stream. Mark it (cohub.api.v1.exposure).websocket = true to serve it to "
                "browsers." % (rpc.full_name, rpc.kind), rpc, rpc.location(HTTP_OPTION))


# ---------------------------------------------------------------------------
# Plugins
# ---------------------------------------------------------------------------

def plugin_version(executable):
    """The x.y.z version a plugin reports for --version, or None."""
    try:
        completed = subprocess.run([executable, "--version"], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    match = re.search(r"(\d+\.\d+\.\d+)", completed.stdout.decode("utf-8", "replace"))
    return match.group(1) if match else None


def require_plugin(executable, expected):
    """Raises PluginError unless the plugin is installed at the expected version.

    The expected version is part of the Bazel action's command line, so a
    plugin upgrade re-runs the action; this guards the other half, that the
    image installed the version the templates name.
    """
    actual = plugin_version(executable)
    if actual is None:
        raise protodesc.PluginError("%s is not installed or reports no version" % executable)
    if actual != expected:
        raise protodesc.PluginError("%s is version %s; protolake expects %s"
                                    % (executable, actual, expected))


# ---------------------------------------------------------------------------
# OpenAPI
# ---------------------------------------------------------------------------

def go_import_path(proto_name):
    """A Go import path for a file without go_package.

    protoc-gen-openapiv2 refuses a request in which any file lacks a Go
    import path, though it writes no Go; lake protos rarely set go_package.
    """
    directory = posixpath.dirname(proto_name)
    return "apispec/" + directory if directory else "apispec"


def openapi_parameter(bundle, pool):
    params = ["allow_merge=true", "merge_file_name=" + bundle, "output_format=json"]
    params += ["M%s=%s" % (file.name, go_import_path(file.name))
               for file in pool.files if not file.go_package]
    return ",".join(params)


def _stream_result(schema):
    """The message schema inside grpc-gateway's {"result", "error"} stream wrapper."""
    if not isinstance(schema, dict) or schema.get("type") != "object":
        return None
    properties = schema.get("properties")
    if not isinstance(properties, dict) or set(properties) != {"result", "error"}:
        return None
    if not str(schema.get("title", "")).startswith("Stream result of"):
        return None
    return properties["result"]


def _schema_name(schema):
    ref = schema.get("$ref", "") if isinstance(schema, dict) else ""
    return ref.rsplit("/", 1)[-1] if ref else "JSON"


def rewrite_server_streams(doc):
    """Rewrites each server-streaming response to newline-delimited bare messages.

    protoc-gen-openapiv2 describes a stream in grpc-gateway's framing, each
    message wrapped as {"result": ...} with an in-band "error". Envoy's
    transcoder writes each message bare, one per line, and a status that
    arrives after the first message travels only in HTTP trailers. Returns
    the operations rewritten, as (path, method, operationId).
    """
    rewritten = []
    for path, verb, operation in _operations(doc):
        response = (operation.get("responses") or {}).get("200")
        if not isinstance(response, dict):
            continue
        result = _stream_result(response.get("schema"))
        if result is None:
            continue
        response["schema"] = result
        response["description"] = (
            "A successful response: a stream of %s messages, each a JSON object on its own "
            "line (newline-delimited JSON). An error before the first message answers with "
            "its HTTP status and a google.rpc.Status body; a later one ends the stream, its "
            "status only in HTTP trailers." % _schema_name(result))
        response[STREAM_FRAMING_EXTENSION] = STREAM_FRAMING
        rewritten.append((path, verb, operation.get("operationId")))
    return rewritten


def _operations(doc):
    """(path, method, operation) for each operation; a path's parameters list is skipped."""
    for path, item in (doc.get("paths") or {}).items():
        if isinstance(item, dict):
            for verb, operation in item.items():
                if isinstance(operation, dict):
                    yield path, verb, operation


def _default_operation_ids(rpc):
    """The operationIds protoc-gen-openapiv2 gives an RPC's bindings by default."""
    base = "%s_%s" % (rpc.service.name, rpc.method.name)
    return [base] + ["%s%d" % (base, n) for n in range(2, rpc.binding_count + 1)]


def check_openapi(doc, rpcs, rewritten):
    """Problems that make the OpenAPI document wrong to publish."""
    problems = []
    if doc.get("swagger") != "2.0":
        problems.append("swagger is %r, expected '2.0'" % doc.get("swagger"))
    problems += unresolved_refs(doc)
    rewritten_ids = set(operation_id for _, _, operation_id in rewritten)
    by_id = dict((operation.get("operationId"), operation) for _, _, operation in _operations(doc))
    for rpc in rpcs:
        if not (rpc.binding_count and rpc.method.server_streaming):
            continue
        for operation_id in _default_operation_ids(rpc):
            if operation_id in by_id and operation_id not in rewritten_ids:
                problems.append("the server-streaming response of %s (%s) is not in grpc-gateway's "
                                "stream shape, so it was not rewritten to the transcoder's framing"
                                % (rpc.full_name, operation_id))
    return problems


def unresolved_refs(doc):
    """Local $refs in doc that point at nothing."""
    problems = []
    for ref in sorted(set(_refs(doc))):
        if not ref.startswith("#/"):
            problems.append("non-local reference %s" % ref)
        elif _resolve(doc, ref) is None:
            problems.append("dangling reference %s" % ref)
    return problems


def _refs(node):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                yield value
            else:
                yield from _refs(value)
    elif isinstance(node, list):
        for value in node:
            yield from _refs(value)


def _resolve(doc, ref):
    node = doc
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def stamp_info(doc, bundle, version):
    """Names the document after the bundle, unless the protos named it.

    protoc-gen-openapiv2 titles a merged document after its first file and
    leaves the version unset; an openapiv2_swagger option overrides both.
    """
    info = doc.setdefault("info", {})
    title = str(info.get("title", ""))
    if not title or title.endswith(".proto"):
        info["title"] = bundle
    if info.get("version") in (None, "", "version not set"):
        info["version"] = version


def generate_openapi(args, pool, files, rpcs, findings):
    require_plugin(args.openapiv2, args.openapiv2_version)
    request = protodesc.code_generator_request(
        [file.name for file in files], openapi_parameter(args.bundle, pool), pool.files)
    outputs = protodesc.run_plugin(args.openapiv2, request)
    name = args.bundle + ".swagger.json"
    if name not in outputs:
        raise protodesc.PluginError("%s wrote %s, not %s"
                                    % (args.openapiv2, sorted(outputs) or "nothing", name))
    doc = json.loads(outputs[name])
    rewritten = rewrite_server_streams(doc)
    stamp_info(doc, args.bundle, args.bundle_version)
    problems = check_openapi(doc, rpcs, rewritten)
    if problems:
        findings.error("The OpenAPI document protoc-gen-openapiv2 wrote for bundle %s is not "
                       "publishable: %s" % (args.bundle, "; ".join(problems)))
        return
    if not doc.get("paths"):
        findings.warning("Bundle %s has google.api.http bindings, but protoc-gen-openapiv2 rendered "
                         "no REST paths (google.api.visibility rules?), so it gets no OpenAPI "
                         "document." % args.bundle)
        return
    write_document(args.out_dir, args.bundle + ".openapi.json", doc, findings)


# ---------------------------------------------------------------------------
# AsyncAPI
# ---------------------------------------------------------------------------

def generate_asyncapi(args, pool, files, rpcs, findings):
    marked = [rpc for rpc in rpcs if rpc.websocket]
    services = sorted(set(rpc.service.full_name for rpc in marked))
    try:
        require_plugin(args.connect_openapi, args.connect_openapi_version)
        request = protodesc.code_generator_request(
            [file.name for file in files], asyncapi.plugin_parameter(args.bundle, services),
            pool.files)
        outputs = protodesc.run_plugin(args.connect_openapi, request)
        base = asyncapi.from_plugin(pool, marked, outputs.get(asyncapi.plugin_output(args.bundle)))
        findings.asyncapi_generator = "protoc-gen-connect-openapi " + args.connect_openapi_version
    except (protodesc.PluginError, asyncapi.CheckFailed, ValueError) as exc:
        findings.warning("protoc-gen-connect-openapi's AsyncAPI output for bundle %s did not pass "
                         "protolake's check (%s); protolake's built-in generator wrote %s.asyncapi.json "
                         "instead." % (args.bundle, exc, args.bundle))
        base = asyncapi.builtin(pool, marked)
        findings.asyncapi_generator = "built-in"
    doc = asyncapi.document(args.bundle, args.bundle_version, base)
    problems = unresolved_refs(doc) + asyncapi.check_document(doc)
    if problems:
        findings.error("The AsyncAPI document for bundle %s is not publishable: %s"
                       % (args.bundle, "; ".join(problems)))
        return
    write_document(args.out_dir, args.bundle + ".asyncapi.json", doc, findings)


def write_document(out_dir, name, doc, findings):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8") as handle:
        json.dump(doc, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    findings.specs.append(name)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def bundle_files(pool, package):
    """The files of the bundle at a Bazel package, by import path."""
    prefix = package.rstrip("/") + "/" if package else ""
    return [file for file in pool.files if file.name.startswith(prefix)]


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--bundle", required=True, help="Bundle name (bundle.yaml name)")
    parser.add_argument("--package", required=True, help="The bundle's lake-relative directory")
    parser.add_argument("--bundle-version", required=True, help="The bundle's version")
    parser.add_argument("--descriptor-set", required=True, action="append",
                        help="FileDescriptorSet with the bundle's files and their imports")
    parser.add_argument("--out-dir", required=True, help="Directory for the spec documents")
    parser.add_argument("--findings", required=True, help="Findings file to write")
    parser.add_argument("--openapiv2", required=True, help="protoc-gen-openapiv2 executable")
    parser.add_argument("--openapiv2-version", required=True)
    parser.add_argument("--connect-openapi", required=True,
                        help="protoc-gen-connect-openapi executable")
    parser.add_argument("--connect-openapi-version", required=True)
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    os.makedirs(args.out_dir, exist_ok=True)
    findings = Findings(args.bundle)
    pool = protodesc.DescriptorPool.from_set_files(args.descriptor_set)
    files = bundle_files(pool, args.package)
    rpcs = collect_rpcs(pool, files)
    check_rpcs(rpcs, findings)
    if not findings.has_errors:
        if any(rpc.binding_count for rpc in rpcs):
            try:
                generate_openapi(args, pool, files, rpcs, findings)
            except (protodesc.PluginError, ValueError) as exc:
                findings.error("protoc-gen-openapiv2 could not write the OpenAPI document for "
                               "bundle %s: %s" % (args.bundle, exc))
        if any(rpc.websocket for rpc in rpcs):
            generate_asyncapi(args, pool, files, rpcs, findings)
    if findings.has_errors:
        # A spec beside an ERROR could still be picked up by a later step.
        for name in findings.specs:
            os.remove(os.path.join(args.out_dir, name))
        findings.specs = []
    findings.write(args.findings)
    return 0


if __name__ == "__main__":
    sys.exit(main())
