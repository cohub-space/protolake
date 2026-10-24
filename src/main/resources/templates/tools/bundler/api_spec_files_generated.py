# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""The API spec documents a bundle package carries.

The api_specs aspect writes a bundle's OpenAPI and AsyncAPI documents
without a version, since an aspect has no access to bundle.yaml; each
bundler packs them with the version it publishes the package under, so a
consumer pins the specs with the package. A version the protos set
themselves (an openapiv2_swagger option) stays.
"""

import json
import os

SPEC_SUFFIXES = (".openapi.json", ".asyncapi.json")

# protoc-gen-openapiv2's version placeholder, which the spec tool also uses.
UNSET_VERSION = "version not set"


def api_spec_files(spec_dirs, version):
    """The documents in the aspect's output directories, as (file name, bytes).

    Each document's info.version is the bundle version unless the protos set
    one. Files are returned sorted by name; there are none for a bundle with
    neither REST bindings nor WebSocket RPCs.
    """
    files = []
    for spec_dir in spec_dirs or []:
        if not os.path.isdir(spec_dir):
            continue
        for name in sorted(os.listdir(spec_dir)):
            if not name.endswith(SPEC_SUFFIXES):
                continue
            with open(os.path.join(spec_dir, name), encoding="utf-8") as handle:
                doc = json.load(handle)
            info = doc.setdefault("info", {})
            if info.get("version") in (None, "", UNSET_VERSION):
                info["version"] = version
            files.append((name, (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8")))
    return sorted(files)
