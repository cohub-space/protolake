# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""The AsyncAPI 3.1 document for a bundle's WebSocket-marked RPCs.

protoc-gen-connect-openapi already describes streaming RPCs as WebSocket
channels at /ws/<package.Service>/<Method>, the address cohub's WebSocket
bridge serves, with JSON Schemas for their messages. It is young, so
protolake checks its output before relying on it (from_plugin): a channel
at the right address for every marked RPC, carrying the RPC's request and
response, and schemas whose references resolve. When the check fails, the
built-in generator below describes the same RPCs from the descriptors.

Either way protolake writes the document itself (document): the plugin's
channels for the marked RPCs only, each with the bridge's three frames
instead of bare messages, a WebSocket binding declaring the
Sec-WebSocket-Protocol handshake, and the close codes in its description,
which AsyncAPI has no field for. Channel and operation keys are
protolake's, so a fallback changes only the schemas' wording.
"""

import json
import re

import protodesc_generated as protodesc

SCHEMA_PREFIX = "#/components/schemas/"
STATUS_SCHEMA = "google.rpc.Status"
CHANNEL_TEMPLATE = "/ws/{package}.{service}/{method}"

_JSON_TYPES = {"null", "boolean", "object", "array", "number", "string", "integer"}

PROTOCOL = (
    "Client- and bidi-streaming RPCs of bundle {bundle}, served to browsers by cohub's "
    "WebSocket bridge. A client opens a channel's address with two Sec-WebSocket-Protocol "
    "entries: a framing, cohub.ws.v1.json for the JSON frames described here or "
    "cohub.ws.v1.proto for binary frames, and its bearer token as "
    "cohub.ws.v1.bearer.<token>, the token base64url-encoded without padding. The bridge "
    "answers with the framing it accepts and never echoes the token.\n\n"
    "Every WebSocket message is one frame. In JSON a Data frame is {{\"data\": <message>}}, "
    "End is {{\"end\": true}} and Status is {{\"status\": <google.rpc.Status>}}; in binary, "
    "Data is 0x00 followed by the message's protobuf bytes, End is 0x01, and Status is 0x02 "
    "followed by the google.rpc.Status bytes. The client sends Data frames, then one End frame "
    "when it has no more requests. The service answers with Data frames and, when the call "
    "finishes, one Status frame; the bridge then closes the socket."
)

CLOSE_CODES = (
    "The close code repeats the call's result for clients that read only the close event:\n\n"
    "| Close code | Meaning |\n"
    "|---|---|\n"
    "| 1000 | The call succeeded. |\n"
    "| 4000 + the gRPC status code | The call failed with that status: 4012 UNIMPLEMENTED, "
    "4016 UNAUTHENTICATED, 4007 PERMISSION_DENIED, 4014 UNAVAILABLE (reconnect). The reason "
    "carries the start of the status message. |\n"
    "| 4100 | The caller's token is about to expire: fetch a fresh token, then reconnect. |\n"
    "| 1009 | A frame exceeded the size limit. |\n"
    "| 1002 | A frame was malformed. |"
)

PROTOCOL_HEADER = (
    "Two entries: the framing (cohub.ws.v1.json or cohub.ws.v1.proto) and the caller's "
    "bearer token as cohub.ws.v1.bearer.<token>, base64url-encoded without padding."
)


class CheckFailed(Exception):
    """protoc-gen-connect-openapi's output is not one protolake relies on."""


class Channel:
    """What the document needs about one marked RPC."""

    def __init__(self, rpc, description, request_ref, response_ref):
        self.rpc = rpc
        self.address = channel_address(rpc)
        self.description = description
        self.request_ref = request_ref
        self.response_ref = response_ref


class Base:
    """The channels and message schemas a generator contributes."""

    def __init__(self, channels, schemas):
        self.channels = channels
        self.schemas = schemas


def channel_address(rpc):
    """The bridge's address for an RPC: /ws/<package.Service>/<Method>."""
    return "/ws/%s/%s" % (rpc.service.full_name, rpc.method.name)


def schema_ref(type_name):
    return SCHEMA_PREFIX + type_name.lstrip(".")


def plugin_output(bundle):
    return bundle + ".asyncapi.json"


def plugin_parameter(bundle, services):
    params = [
        "format=json",
        "asyncapi-path=" + plugin_output(bundle),
        "asyncapi-channel-template=" + CHANNEL_TEMPLATE,
    ]
    params += ["services=" + service for service in services]
    return ",".join(params)


# ---------------------------------------------------------------------------
# The plugin's output, checked
# ---------------------------------------------------------------------------

def from_plugin(pool, rpcs, content):
    """Takes the channels and schemas of the marked RPCs from the plugin's document.

    Raises CheckFailed, naming the first problem, when the document is not
    one protolake relies on.
    """
    if content is None:
        raise CheckFailed("no AsyncAPI document in its output")
    try:
        doc = json.loads(content)
    except ValueError as exc:
        raise CheckFailed("its AsyncAPI document is not JSON: %s" % exc)
    if not isinstance(doc, dict):
        raise CheckFailed("its AsyncAPI document is not a JSON object")
    version = str(doc.get("asyncapi", ""))
    if not version.startswith("3.1."):
        raise CheckFailed("its document is AsyncAPI %r, not 3.1" % version)
    channels = doc.get("channels")
    schemas = (doc.get("components") or {}).get("schemas")
    if not isinstance(channels, dict) or not isinstance(schemas, dict):
        raise CheckFailed("its document has no channels or no components.schemas")

    by_address = {}
    for channel in channels.values():
        if isinstance(channel, dict) and isinstance(channel.get("address"), str):
            by_address.setdefault(channel["address"], []).append(channel)

    taken = []
    for rpc in rpcs:
        address = channel_address(rpc)
        found = by_address.get(address, [])
        if len(found) != 1:
            raise CheckFailed("%d channels at %s for %s, expected one"
                              % (len(found), address, rpc.full_name))
        channel = found[0]
        messages = channel.get("messages")
        payloads = set()
        if isinstance(messages, dict):
            for message in messages.values():
                payload = message.get("payload") if isinstance(message, dict) else None
                if isinstance(payload, dict) and isinstance(payload.get("$ref"), str):
                    payloads.add(payload["$ref"])
        request_ref = schema_ref(rpc.method.input_type)
        response_ref = schema_ref(rpc.method.output_type)
        for ref in (request_ref, response_ref):
            if ref not in payloads:
                raise CheckFailed("the channel at %s carries no message of %s"
                                  % (address, ref[len(SCHEMA_PREFIX):]))
        description = channel.get("description")
        if not isinstance(description, str):
            description = rpc.file.comment(rpc.method.path)
        taken.append(Channel(rpc, description, request_ref, response_ref))

    roots = [ref[len(SCHEMA_PREFIX):] for c in taken for ref in (c.request_ref, c.response_ref)]
    kept = _reachable(schemas, roots)
    problems = []
    for name, schema in kept.items():
        _check_schema(schema, name, problems)
    if problems:
        raise CheckFailed("schema %s" % problems[0])
    return Base(taken, kept)


def _reachable(schemas, roots):
    """The schemas reachable from roots by $ref; raises CheckFailed on a dangling one."""
    kept = {}
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name in kept:
            continue
        if name not in schemas:
            raise CheckFailed("reference to %s, which components.schemas lacks" % name)
        kept[name] = schemas[name]
        for ref in _refs(schemas[name]):
            if not ref.startswith(SCHEMA_PREFIX):
                raise CheckFailed("schema %s refers to %s, outside components.schemas" % (name, ref))
            pending.append(ref[len(SCHEMA_PREFIX):])
    return dict((name, kept[name]) for name in sorted(kept))


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


def _check_schema(schema, where, problems):
    """Structural checks of a JSON Schema: the keywords protolake's readers rely on."""
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict):
        problems.append("%s is not a schema object" % where)
        return
    kind = schema.get("type")
    kinds = kind if isinstance(kind, list) else [kind] if kind is not None else []
    for item in kinds:
        if item not in _JSON_TYPES:
            problems.append("%s has type %r" % (where, item))
    for key in ("properties", "patternProperties", "$defs", "definitions"):
        if key in schema:
            value = schema[key]
            if not isinstance(value, dict):
                problems.append("%s.%s is not an object" % (where, key))
                continue
            for name, nested in value.items():
                _check_schema(nested, "%s.%s.%s" % (where, key, name), problems)
    for key in ("items", "additionalProperties", "unevaluatedProperties", "not", "contains",
                "propertyNames", "if", "then", "else"):
        if key in schema:
            value = schema[key]
            if isinstance(value, list) and key == "items":
                for i, nested in enumerate(value):
                    _check_schema(nested, "%s.items[%d]" % (where, i), problems)
            else:
                _check_schema(value, "%s.%s" % (where, key), problems)
    for key in ("allOf", "anyOf", "oneOf"):
        if key in schema:
            value = schema[key]
            if not isinstance(value, list) or not value:
                problems.append("%s.%s is not a non-empty list" % (where, key))
                continue
            for i, nested in enumerate(value):
                _check_schema(nested, "%s.%s[%d]" % (where, key, i), problems)
    if "required" in schema and not (isinstance(schema["required"], list)
                                     and all(isinstance(r, str) for r in schema["required"])):
        problems.append("%s.required is not a list of names" % where)
    if "$ref" in schema and not isinstance(schema["$ref"], str):
        problems.append("%s.$ref is not a string" % where)


# ---------------------------------------------------------------------------
# The built-in generator
# ---------------------------------------------------------------------------

_WELL_KNOWN = {
    "google.protobuf.Timestamp": {"type": "string", "format": "date-time",
                                  "description": "An RFC 3339 timestamp."},
    "google.protobuf.Duration": {"type": "string", "pattern": "^-?[0-9]+(\\.[0-9]+)?s$",
                                 "description": "Seconds with up to nine fractional digits, "
                                                "suffixed with s."},
    "google.protobuf.FieldMask": {"type": "string",
                                  "description": "Comma-separated field paths."},
    "google.protobuf.Struct": {"type": "object", "additionalProperties": True},
    "google.protobuf.Value": {"description": "Any JSON value."},
    "google.protobuf.ListValue": {"type": "array", "items": True},
    "google.protobuf.NullValue": {"type": "null"},
    "google.protobuf.Empty": {"type": "object", "additionalProperties": False},
    "google.protobuf.Any": {"type": "object", "properties": {"@type": {"type": "string"}},
                            "required": ["@type"], "additionalProperties": True},
    "google.protobuf.DoubleValue": {"type": ["number", "null"]},
    "google.protobuf.FloatValue": {"type": ["number", "null"]},
    "google.protobuf.Int64Value": {"type": ["string", "null"], "format": "int64"},
    "google.protobuf.UInt64Value": {"type": ["string", "null"], "format": "uint64"},
    "google.protobuf.Int32Value": {"type": ["integer", "null"], "format": "int32"},
    "google.protobuf.UInt32Value": {"type": ["integer", "null"], "format": "uint32"},
    "google.protobuf.BoolValue": {"type": ["boolean", "null"]},
    "google.protobuf.StringValue": {"type": ["string", "null"]},
    "google.protobuf.BytesValue": {"type": ["string", "null"], "format": "byte"},
}

_SCALARS = {
    protodesc.TYPE_DOUBLE: {"type": "number", "format": "double"},
    protodesc.TYPE_FLOAT: {"type": "number", "format": "float"},
    protodesc.TYPE_INT32: {"type": "integer", "format": "int32"},
    protodesc.TYPE_SINT32: {"type": "integer", "format": "int32"},
    protodesc.TYPE_SFIXED32: {"type": "integer", "format": "int32"},
    protodesc.TYPE_UINT32: {"type": "integer", "format": "uint32"},
    protodesc.TYPE_FIXED32: {"type": "integer", "format": "uint32"},
    # proto3 JSON writes 64-bit integers as strings.
    protodesc.TYPE_INT64: {"type": "string", "format": "int64"},
    protodesc.TYPE_SINT64: {"type": "string", "format": "int64"},
    protodesc.TYPE_SFIXED64: {"type": "string", "format": "int64"},
    protodesc.TYPE_UINT64: {"type": "string", "format": "uint64"},
    protodesc.TYPE_FIXED64: {"type": "string", "format": "uint64"},
    protodesc.TYPE_BOOL: {"type": "boolean"},
    protodesc.TYPE_STRING: {"type": "string"},
    protodesc.TYPE_BYTES: {"type": "string", "format": "byte"},
}


def _json_name(field):
    if field.json_name:
        return field.json_name
    parts = field.name.split("_")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


class _Schemas:
    """JSON Schemas for messages and enums, in proto3's JSON mapping."""

    def __init__(self, pool):
        self.pool = pool
        self.schemas = {}
        self.pending = []

    def ref(self, type_name):
        name = type_name.lstrip(".")
        if name not in self.schemas and name not in self.pending:
            self.pending.append(name)
        return {"$ref": SCHEMA_PREFIX + name}

    def build(self, roots):
        for root in roots:
            self.ref(root)
        while self.pending:
            name = self.pending.pop(0)
            self.schemas[name] = None  # claimed first, so a message that holds itself recurses once
            self.schemas[name] = self._schema(name)
        return dict((name, self.schemas[name]) for name in sorted(self.schemas))

    def _schema(self, name):
        if name in _WELL_KNOWN:
            return dict(_WELL_KNOWN[name])
        enum = self.pool.enum(name)
        if enum is not None:
            schema = {"type": "string", "title": enum.name,
                      "enum": [value_name for value_name, _ in enum.values]}
            return _described(schema, enum.file.comment(enum.path))
        message = self.pool.message(name)
        if message is None:
            return {"description": "%s is not in the descriptor set." % name}
        properties = {}
        for field in message.fields:
            properties[_json_name(field)] = _described(self._field(field),
                                                       message.file.comment(field.path))
        schema = {"type": "object", "title": message.name, "properties": properties,
                  "additionalProperties": False}
        return _described(schema, message.file.comment(message.path))

    def _field(self, field):
        if field.type in (protodesc.TYPE_MESSAGE, protodesc.TYPE_GROUP):
            entry = self.pool.message(field.type_name)
            if entry is not None and entry.map_entry and field.repeated:
                value = next((f for f in entry.fields if f.number == 2), None)
                return {"type": "object",
                        "additionalProperties": self._single(value) if value else True}
        single = self._single(field)
        return {"type": "array", "items": single} if field.repeated else single

    def _single(self, field):
        if field.type in (protodesc.TYPE_MESSAGE, protodesc.TYPE_GROUP, protodesc.TYPE_ENUM):
            return self.ref(field.type_name)
        return dict(_SCALARS.get(field.type, {}))


def _described(schema, comment):
    if not comment:
        return schema
    if "$ref" in schema:
        # Keywords beside a $ref are ignored, so the reference moves into allOf.
        schema = {"allOf": [schema]}
    schema["description"] = comment
    return schema


def builtin(pool, rpcs):
    """Describes the marked RPCs from their descriptors alone."""
    channels = [Channel(rpc, rpc.file.comment(rpc.method.path),
                        schema_ref(rpc.method.input_type), schema_ref(rpc.method.output_type))
                for rpc in rpcs]
    roots = [type_name for rpc in rpcs
             for type_name in (rpc.method.input_type, rpc.method.output_type)]
    return Base(channels, _Schemas(pool).build(roots))


# ---------------------------------------------------------------------------
# The document
# ---------------------------------------------------------------------------

def channel_key(rpc):
    return re.sub(r"[^A-Za-z0-9_-]", "_", rpc.full_name)


def _data_message(direction, ref):
    type_name = ref[len(SCHEMA_PREFIX):]
    return {
        "name": "Data",
        "title": "Data: %s" % type_name,
        "summary": "One %s message, from the %s." % (type_name, direction),
        "contentType": "application/json",
        "payload": {
            "type": "object",
            "properties": {"data": {"$ref": ref}},
            "required": ["data"],
            "additionalProperties": False,
        },
    }


_STATUS = {
    "type": "object",
    "title": "Status",
    "description": "The call's gRPC status (google.rpc.Status).",
    "properties": {
        "code": {"type": "integer", "format": "int32", "description": "The gRPC status code."},
        "message": {"type": "string", "description": "The status message."},
        "details": {
            "type": "array",
            "description": "Error details, each a google.protobuf.Any.",
            "items": {"type": "object", "properties": {"@type": {"type": "string"}},
                      "required": ["@type"], "additionalProperties": True},
        },
    },
}

_FRAME_MESSAGES = {
    "End": {
        "name": "End",
        "title": "End",
        "summary": "The client has no more requests (a half-close).",
        "contentType": "application/json",
        "payload": {"type": "object", "properties": {"end": {"const": True}},
                    "required": ["end"], "additionalProperties": False},
    },
    "Status": {
        "name": "Status",
        "title": "Status",
        "summary": "The call's result; the bridge closes the socket after it.",
        "contentType": "application/json",
        "payload": {"type": "object",
                    "properties": {"status": {"$ref": SCHEMA_PREFIX + STATUS_SCHEMA}},
                    "required": ["status"], "additionalProperties": False},
    },
}


def _binding():
    return {
        "ws": {
            "method": "GET",
            "headers": {
                "type": "object",
                "properties": {
                    "Sec-WebSocket-Protocol": {
                        "type": "string",
                        "description": PROTOCOL_HEADER,
                        "examples": ["cohub.ws.v1.json, cohub.ws.v1.bearer.ZXhhbXBsZS10b2tlbg"],
                    },
                },
                "required": ["Sec-WebSocket-Protocol"],
            },
            "query": {
                "type": "object",
                "properties": {
                    "traceparent": {"type": "string",
                                    "description": "A W3C trace context for the stream."},
                },
            },
            "bindingVersion": "0.1.0",
        },
    }


def document(bundle, version, base):
    """The AsyncAPI 3.1 document for a bundle's marked RPCs."""
    channels = {}
    operations = {}
    for channel in sorted(base.channels, key=lambda c: c.rpc.full_name):
        key = channel_key(channel.rpc)
        description = (channel.description + "\n\n" if channel.description else "") + CLOSE_CODES
        channels[key] = {
            "address": channel.address,
            "title": "%s/%s" % (channel.rpc.service.full_name, channel.rpc.method.name),
            "description": description,
            "messages": {
                "request": _data_message("client", channel.request_ref),
                "response": _data_message("service", channel.response_ref),
                "end": {"$ref": "#/components/messages/End"},
                "status": {"$ref": "#/components/messages/Status"},
            },
            "bindings": _binding(),
        }
        channel_ref = {"$ref": "#/channels/" + key}
        operations[key + "_send"] = {
            "action": "send",
            "channel": channel_ref,
            "summary": "Send requests to %s, then End." % channel.rpc.full_name,
            "messages": [{"$ref": "#/channels/%s/messages/request" % key},
                         {"$ref": "#/channels/%s/messages/end" % key}],
        }
        operations[key + "_receive"] = {
            "action": "receive",
            "channel": dict(channel_ref),
            "summary": "Receive the responses of %s, then its Status." % channel.rpc.full_name,
            "messages": [{"$ref": "#/channels/%s/messages/response" % key},
                         {"$ref": "#/channels/%s/messages/status" % key}],
        }
    schemas = dict(base.schemas)
    schemas.setdefault(STATUS_SCHEMA, _STATUS)
    return {
        "asyncapi": "3.1.0",
        "info": {
            "title": "%s WebSocket streams" % bundle,
            "version": version,
            "description": PROTOCOL.format(bundle=bundle),
        },
        "defaultContentType": "application/json",
        "channels": channels,
        "operations": operations,
        "components": {
            "schemas": dict((name, schemas[name]) for name in sorted(schemas)),
            "messages": _FRAME_MESSAGES,
        },
    }


def check_document(doc):
    """Problems with a document protolake wrote; references are checked separately."""
    problems = []
    if doc.get("asyncapi") != "3.1.0":
        problems.append("asyncapi is %r" % doc.get("asyncapi"))
    addresses = [channel.get("address") for channel in doc.get("channels", {}).values()]
    duplicates = sorted(set(a for a in addresses if addresses.count(a) > 1))
    if duplicates:
        problems.append("several channels at %s" % ", ".join(duplicates))
    if not doc.get("channels"):
        problems.append("no channels")
    for name, schema in doc.get("components", {}).get("schemas", {}).items():
        _check_schema(schema, name, problems)
    return problems
