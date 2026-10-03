# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""Compiled protobuf descriptors, read without the protobuf runtime.

The API spec tool runs inside a lake's Bazel build, where only the Python
standard library is available, so it decodes a FileDescriptorSet's wire
format itself: enough of descriptor.proto to see each RPC's streaming shape
and options, the messages a JSON Schema describes, and the comments that
become descriptions. It also speaks the protoc plugin protocol, so the tool
can run a plugin on the set without protoc.

Custom options are read through their declarations in the set, looked up by
full name, so an option that reuses an extension number never reads as
cohub's or Google's.
"""

import struct
import subprocess

# FieldDescriptorProto.Type
TYPE_DOUBLE = 1
TYPE_FLOAT = 2
TYPE_INT64 = 3
TYPE_UINT64 = 4
TYPE_INT32 = 5
TYPE_FIXED64 = 6
TYPE_FIXED32 = 7
TYPE_BOOL = 8
TYPE_STRING = 9
TYPE_GROUP = 10
TYPE_MESSAGE = 11
TYPE_BYTES = 12
TYPE_UINT32 = 13
TYPE_ENUM = 14
TYPE_SFIXED32 = 15
TYPE_SFIXED64 = 16
TYPE_SINT32 = 17
TYPE_SINT64 = 18

LABEL_REPEATED = 3

_FIXED64_TYPES = (TYPE_DOUBLE, TYPE_FIXED64, TYPE_SFIXED64)
_FIXED32_TYPES = (TYPE_FLOAT, TYPE_FIXED32, TYPE_SFIXED32)


class DescriptorError(ValueError):
    """The bytes are not a well-formed descriptor or plugin message."""


# ---------------------------------------------------------------------------
# Wire format
# ---------------------------------------------------------------------------

def _read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise DescriptorError("truncated varint")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise DescriptorError("varint longer than ten bytes")


def _skip_group(buf, pos, number):
    while True:
        key, pos = _read_varint(buf, pos)
        inner, wire_type = key >> 3, key & 7
        if wire_type == 4:
            if inner != number:
                raise DescriptorError("mismatched end of group %d" % number)
            return pos
        _, pos = _read_value(buf, pos, inner, wire_type)


def _read_value(buf, pos, number, wire_type):
    if wire_type == 0:
        return _read_varint(buf, pos)
    if wire_type == 1:
        end = pos + 8
        if end > len(buf):
            raise DescriptorError("truncated fixed64 field %d" % number)
        return int.from_bytes(buf[pos:end], "little"), end
    if wire_type == 2:
        length, pos = _read_varint(buf, pos)
        end = pos + length
        if end > len(buf):
            raise DescriptorError("truncated field %d" % number)
        return bytes(buf[pos:end]), end
    if wire_type == 5:
        end = pos + 4
        if end > len(buf):
            raise DescriptorError("truncated fixed32 field %d" % number)
        return int.from_bytes(buf[pos:end], "little"), end
    if wire_type == 3:
        return None, _skip_group(buf, pos, number)
    raise DescriptorError("unexpected wire type %d on field %d" % (wire_type, number))


def iter_fields(buf):
    """Yields (number, wire_type, value) for each field of a serialized message.

    value is an int for varint and fixed-width fields and bytes for
    length-delimited ones. Groups are skipped whole: descriptor.proto has
    none, and nothing this tool reads is one.
    """
    for number, wire_type, value, _, _ in _iter_spans(buf):
        if wire_type != 3:
            yield number, wire_type, value


def _iter_spans(buf):
    """Like iter_fields, with each field's byte span, groups included."""
    pos = 0
    while pos < len(buf):
        start = pos
        key, pos = _read_varint(buf, pos)
        number, wire_type = key >> 3, key & 7
        value, pos = _read_value(buf, pos, number, wire_type)
        yield number, wire_type, value, start, pos


def _replace_fields(buf, replace):
    """Re-serializes a message, copying every field's bytes but those replace() changes.

    replace(number, wire_type, value, index) returns None to keep a field as
    written, or new bytes for a length-delimited field; index counts the
    field's occurrences so far.
    """
    out = bytearray()
    seen = {}
    for number, wire_type, value, start, end in _iter_spans(buf):
        index = seen.get(number, 0)
        seen[number] = index + 1
        new = replace(number, wire_type, value, index) if wire_type == 2 else None
        out += buf[start:end] if new is None else encode_bytes_field(number, new)
    return bytes(out)


def strip_method_options(file_raw, methods, option_number):
    """A serialized FileDescriptorProto without one option on some methods.

    methods holds (service index, method index) pairs; every other byte of
    the file, source info included, is kept, so its descriptor paths still
    point where they did.
    """
    def in_method(method_raw):
        return _replace_fields(method_raw, lambda n, w, v, i: _without(v, option_number) if n == 4 else None)

    def in_service(service_index):
        def replace(number, wire_type, value, index):
            if number == 2 and (service_index, index) in methods:
                return in_method(value)
            return None
        return replace

    return _replace_fields(file_raw, lambda n, w, v, i: _replace_fields(v, in_service(i)) if n == 6 else None)


def _without(message_raw, number):
    out = bytearray()
    for field_number, _, _, start, end in _iter_spans(message_raw):
        if field_number != number:
            out += message_raw[start:end]
    return bytes(out)


def _varint_bytes(value):
    if value < 0:
        value += 1 << 64
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def encode_bytes_field(number, data):
    """Encodes one length-delimited field."""
    return _varint_bytes(number << 3 | 2) + _varint_bytes(len(data)) + data


def _packed_ints(value):
    pos = 0
    out = []
    while pos < len(value):
        item, pos = _read_varint(value, pos)
        out.append(item)
    return out


def _signed(value, bits):
    if value >= 1 << (bits - 1):
        value -= 1 << bits
    return value


# ---------------------------------------------------------------------------
# Descriptor model
# ---------------------------------------------------------------------------

class FieldDesc:
    """A FieldDescriptorProto: a message field, or an extension."""

    def __init__(self):
        self.name = ""
        self.number = 0
        self.label = 1
        self.type = 0
        self.type_name = ""
        self.extendee = ""
        self.json_name = ""
        self.oneof_index = None
        self.proto3_optional = False
        self.full_name = ""
        self.path = ()

    @property
    def repeated(self):
        return self.label == LABEL_REPEATED


class MessageDesc:
    """A DescriptorProto, with its nested types flattened into the pool."""

    def __init__(self):
        self.name = ""
        self.full_name = ""
        self.fields = []
        self.oneofs = []
        self.map_entry = False
        self.file = None
        self.path = ()


class EnumDesc:
    """An EnumDescriptorProto."""

    def __init__(self):
        self.name = ""
        self.full_name = ""
        self.values = []
        self.file = None
        self.path = ()


class MethodDesc:
    """A MethodDescriptorProto, with its options' raw bytes."""

    def __init__(self):
        self.name = ""
        self.input_type = ""
        self.output_type = ""
        self.client_streaming = False
        self.server_streaming = False
        self.options = b""
        self.service = None
        self.path = ()


class ServiceDesc:
    """A ServiceDescriptorProto, with its options' raw bytes."""

    def __init__(self):
        self.name = ""
        self.full_name = ""
        self.methods = []
        self.options = b""
        self.file = None
        self.path = ()


class FileDesc:
    """A FileDescriptorProto and its raw bytes, for the plugin request."""

    def __init__(self, raw):
        self.raw = raw
        self.name = ""
        self.package = ""
        self.dependencies = []
        self.messages = []
        self.enums = []
        self.services = []
        self.extensions = []
        self.go_package = ""
        # path tuple -> (line, column, leading comment), both 1-based
        self.locations = {}

    def location(self, path):
        """Returns (line, column, leading comment) for a descriptor path, or None."""
        return self.locations.get(tuple(path))

    def comment(self, path):
        """The leading comment of the element at path, stripped, or ""."""
        loc = self.locations.get(tuple(path))
        return _clean_comment(loc[2]) if loc else ""


def _clean_comment(text):
    lines = [line[1:] if line.startswith(" ") else line for line in text.split("\n")]
    return "\n".join(lines).strip()


def _join(prefix, name):
    return prefix + "." + name if prefix else name


def _parse_field(raw, scope, path):
    field = FieldDesc()
    field.path = path
    for number, _, value in iter_fields(raw):
        if number == 1:
            field.name = value.decode("utf-8")
        elif number == 2:
            field.extendee = value.decode("utf-8")
        elif number == 3:
            field.number = value
        elif number == 4:
            field.label = value
        elif number == 5:
            field.type = value
        elif number == 6:
            field.type_name = value.decode("utf-8")
        elif number == 9:
            field.oneof_index = value
        elif number == 10:
            field.json_name = value.decode("utf-8")
        elif number == 17:
            field.proto3_optional = bool(value)
    field.full_name = _join(scope, field.name)
    return field


def _parse_enum(raw, scope, file, path, sink):
    enum = EnumDesc()
    enum.file = file
    enum.path = path
    for number, _, value in iter_fields(raw):
        if number == 1:
            enum.name = value.decode("utf-8")
        elif number == 2:
            name, num = "", 0
            for inner, _, item in iter_fields(value):
                if inner == 1:
                    name = item.decode("utf-8")
                elif inner == 2:
                    num = _signed(item, 64)
            enum.values.append((name, num))
    enum.full_name = _join(scope, enum.name)
    sink["enums"].append(enum)
    return enum


def _parse_message(raw, scope, file, path, sink):
    message = MessageDesc()
    message.file = file
    message.path = path
    nested, enums, extensions = [], [], []
    for number, _, value in iter_fields(raw):
        if number == 1:
            message.name = value.decode("utf-8")
        elif number == 2:
            message.fields.append(value)
        elif number == 3:
            nested.append(value)
        elif number == 4:
            enums.append(value)
        elif number == 6:
            extensions.append(value)
        elif number == 7:
            for inner, _, item in iter_fields(value):
                if inner == 7:
                    message.map_entry = bool(item)
        elif number == 8:
            name = ""
            for inner, _, item in iter_fields(value):
                if inner == 1:
                    name = item.decode("utf-8")
            message.oneofs.append(name)
    message.full_name = _join(scope, message.name)
    message.fields = [_parse_field(raw_field, message.full_name, path + (2, i))
                      for i, raw_field in enumerate(message.fields)]
    sink["messages"].append(message)
    for i, item in enumerate(nested):
        _parse_message(item, message.full_name, file, path + (3, i), sink)
    for i, item in enumerate(enums):
        _parse_enum(item, message.full_name, file, path + (4, i), sink)
    for i, item in enumerate(extensions):
        sink["extensions"].append(_parse_field(item, message.full_name, path + (6, i)))
    return message


def _parse_method(raw, service, path):
    method = MethodDesc()
    method.service = service
    method.path = path
    for number, _, value in iter_fields(raw):
        if number == 1:
            method.name = value.decode("utf-8")
        elif number == 2:
            method.input_type = value.decode("utf-8")
        elif number == 3:
            method.output_type = value.decode("utf-8")
        elif number == 4:
            method.options += value
        elif number == 5:
            method.client_streaming = bool(value)
        elif number == 6:
            method.server_streaming = bool(value)
    return method


def _parse_service(raw, scope, file, path):
    service = ServiceDesc()
    service.file = file
    service.path = path
    methods = []
    for number, _, value in iter_fields(raw):
        if number == 1:
            service.name = value.decode("utf-8")
        elif number == 2:
            methods.append(value)
        elif number == 3:
            service.options += value
    service.full_name = _join(scope, service.name)
    service.methods = [_parse_method(item, service, path + (2, i)) for i, item in enumerate(methods)]
    return service


def _parse_locations(file, raw):
    for number, _, value in iter_fields(raw):
        if number != 1:
            continue
        path, span, leading = [], [], ""
        for inner, wire_type, item in iter_fields(value):
            if inner == 1:
                path.extend(_packed_ints(item) if wire_type == 2 else [item])
            elif inner == 2:
                span.extend(_packed_ints(item) if wire_type == 2 else [item])
            elif inner == 3:
                leading = item.decode("utf-8")
        if len(span) >= 3:
            file.locations.setdefault(tuple(path), (span[0] + 1, span[1] + 1, leading))


def parse_file(raw):
    """Parses one serialized FileDescriptorProto.

    Returns (file, sink): sink holds every message, enum and extension the
    file declares, nested ones included, for the pool's indexes.
    """
    file = FileDesc(raw)
    sink = {"messages": [], "enums": [], "extensions": []}
    messages, enums, services, extensions = [], [], [], []
    for number, _, value in iter_fields(raw):
        if number == 1:
            file.name = value.decode("utf-8")
        elif number == 2:
            file.package = value.decode("utf-8")
        elif number == 3:
            file.dependencies.append(value.decode("utf-8"))
        elif number == 4:
            messages.append(value)
        elif number == 5:
            enums.append(value)
        elif number == 6:
            services.append(value)
        elif number == 7:
            extensions.append(value)
        elif number == 8:
            for inner, _, item in iter_fields(value):
                if inner == 11:
                    file.go_package = item.decode("utf-8")
        elif number == 9:
            _parse_locations(file, value)
    file.messages = [_parse_message(item, file.package, file, (4, i), sink)
                     for i, item in enumerate(messages)]
    file.enums = [_parse_enum(item, file.package, file, (5, i), sink)
                  for i, item in enumerate(enums)]
    file.services = [_parse_service(item, file.package, file, (6, i))
                     for i, item in enumerate(services)]
    file.extensions = [_parse_field(item, file.package, (7, i)) for i, item in enumerate(extensions)]
    sink["extensions"].extend(file.extensions)
    return file, sink


class DescriptorPool:
    """The files of one or more FileDescriptorSets, in dependency order.

    Messages, enums and extensions are indexed by full name without the
    leading dot; type references in descriptors carry one, which
    `message()` and `enum()` accept.
    """

    def __init__(self, raw_files):
        by_name = {}
        sinks = {}
        for raw in raw_files:
            file, sink = parse_file(raw)
            if file.name not in by_name:
                by_name[file.name] = file
                sinks[file.name] = sink
        self.files = _dependency_order(by_name)
        self._by_name = by_name
        self.messages = {}
        self.enums = {}
        self.extensions = {}
        for file in self.files:
            sink = sinks[file.name]
            for message in sink["messages"]:
                self.messages[message.full_name] = message
            for enum in sink["enums"]:
                self.enums[enum.full_name] = enum
            for extension in sink["extensions"]:
                self.extensions[extension.full_name] = extension

    @classmethod
    def from_set_bytes(cls, *sets):
        """Builds a pool from serialized FileDescriptorSets; the first copy of a file wins."""
        raw_files = []
        for data in sets:
            for number, _, value in iter_fields(data):
                if number == 1:
                    raw_files.append(value)
        return cls(raw_files)

    @classmethod
    def from_set_files(cls, paths):
        """Builds a pool from FileDescriptorSet files."""
        sets = []
        for path in paths:
            with open(path, "rb") as handle:
                sets.append(handle.read())
        return cls.from_set_bytes(*sets)

    def file(self, name):
        return self._by_name.get(name)

    def message(self, type_name):
        return self.messages.get(type_name.lstrip("."))

    def enum(self, type_name):
        return self.enums.get(type_name.lstrip("."))

    def option(self, options, extension_name, extendee):
        """Decodes a custom option from an options message's raw bytes.

        Returns the option's message as a dict (see decode_message), or None
        when the option is unset or the set does not declare the extension
        on that extendee.
        """
        extension = self.extensions.get(extension_name)
        if extension is None or extension.extendee.lstrip(".") != extendee:
            return None
        chunks = [value for number, wire_type, value in iter_fields(options)
                  if number == extension.number and wire_type == 2]
        if not chunks:
            return None
        message = self.message(extension.type_name)
        if message is None:
            return None
        # Repeated occurrences of a message field merge, as their concatenation does.
        return decode_message(self, message, b"".join(chunks))


def _dependency_order(by_name):
    ordered, seen = [], set()

    def visit(name, stack):
        if name in seen or name not in by_name:
            return
        if name in stack:
            raise DescriptorError("import cycle through " + name)
        stack.add(name)
        for dependency in by_name[name].dependencies:
            visit(dependency, stack)
        stack.discard(name)
        seen.add(name)
        ordered.append(by_name[name])

    for name in by_name:
        visit(name, set())
    return ordered


def _scalar(field_type, value):
    if field_type == TYPE_BOOL:
        return bool(value)
    if field_type in (TYPE_INT32, TYPE_INT64, TYPE_ENUM):
        return _signed(value, 64)
    if field_type in (TYPE_SINT32, TYPE_SINT64):
        return (value >> 1) ^ -(value & 1)
    if field_type == TYPE_SFIXED32:
        return _signed(value, 32)
    if field_type == TYPE_SFIXED64:
        return _signed(value, 64)
    if field_type == TYPE_DOUBLE:
        return struct.unpack("<d", value.to_bytes(8, "little"))[0]
    if field_type == TYPE_FLOAT:
        return struct.unpack("<f", value.to_bytes(4, "little"))[0]
    return value


def _packed(field_type, value):
    out = []
    pos = 0
    while pos < len(value):
        if field_type in _FIXED64_TYPES:
            item, pos = int.from_bytes(value[pos:pos + 8], "little"), pos + 8
        elif field_type in _FIXED32_TYPES:
            item, pos = int.from_bytes(value[pos:pos + 4], "little"), pos + 4
        else:
            item, pos = _read_varint(value, pos)
        out.append(_scalar(field_type, item))
    return out


def decode_message(pool, message, raw):
    """Decodes raw bytes as `message`: a dict of field name to value.

    Repeated fields map to lists; message fields to nested dicts. Fields the
    descriptor does not declare are skipped.
    """
    by_number = dict((field.number, field) for field in message.fields)
    out = {}
    for number, wire_type, value in iter_fields(raw):
        field = by_number.get(number)
        if field is None:
            continue
        if field.type in (TYPE_MESSAGE, TYPE_GROUP):
            nested = pool.message(field.type_name)
            values = [decode_message(pool, nested, value) if nested and wire_type == 2 else {}]
        elif field.type == TYPE_STRING:
            values = [value.decode("utf-8")]
        elif field.type == TYPE_BYTES:
            values = [value]
        elif wire_type == 2:
            values = _packed(field.type, value)
        else:
            values = [_scalar(field.type, value)]
        if field.repeated:
            out.setdefault(field.name, []).extend(values)
        else:
            out[field.name] = values[-1]
    return out


# ---------------------------------------------------------------------------
# The protoc plugin protocol (google/protobuf/compiler/plugin.proto)
# ---------------------------------------------------------------------------

class PluginError(RuntimeError):
    """A plugin failed, or answered with an error."""


def code_generator_request(files_to_generate, parameter, raw_files):
    """Serializes a CodeGeneratorRequest.

    raw_files are serialized FileDescriptorProtos: every file the generated
    ones need, each after its dependencies, as protoc sends them.
    """
    out = bytearray()
    for name in files_to_generate:
        out += encode_bytes_field(1, name.encode("utf-8"))
    if parameter:
        out += encode_bytes_field(2, parameter.encode("utf-8"))
    for raw in raw_files:
        out += encode_bytes_field(15, raw)
    return bytes(out)


def parse_code_generator_response(raw):
    """Returns (error or None, [(file name, content)]) from a CodeGeneratorResponse."""
    error = None
    files = []
    for number, _, value in iter_fields(raw):
        if number == 1:
            error = value.decode("utf-8")
        elif number == 15:
            name, content, insertion_point = "", "", ""
            for inner, _, item in iter_fields(value):
                if inner == 1:
                    name = item.decode("utf-8")
                elif inner == 2:
                    insertion_point = item.decode("utf-8")
                elif inner == 15:
                    content = item.decode("utf-8")
            if not insertion_point:
                files.append((name, content))
    return error, files


def run_plugin(executable, request, timeout=600):
    """Runs a protoc plugin on a serialized request; returns its files as a dict."""
    try:
        completed = subprocess.run([executable], input=request, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=timeout, check=False)
    except FileNotFoundError:
        raise PluginError("%s is not installed" % executable)
    except subprocess.TimeoutExpired:
        raise PluginError("%s did not finish within %d seconds" % (executable, timeout))
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()
        raise PluginError("%s exited with %d: %s" % (executable, completed.returncode, detail))
    try:
        error, files = parse_code_generator_response(completed.stdout)
    except DescriptorError as exc:
        raise PluginError("%s answered with an unreadable response: %s" % (executable, exc))
    if error:
        raise PluginError(error)
    return dict(files)
