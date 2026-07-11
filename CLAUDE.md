# Proto Lake

Centralized protocol buffer management system. Java/Quarkus tool that takes a git
repo of `.proto` files organized into bundles and produces language-specific
packages (Java/Python/npm) via a Bazel-driven pipeline. Runs as either a CLI
(via `./protolakew` Docker wrapper inside a lake) or a gRPC server. Architecture
and decisions live in the
[cohub-knowledge protolake docs](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/knowledge/protolake/protolake.md).

## Commands

```bash
# Build the Java service
./mvnw clean package -DskipTests

# Tests
./mvnw test                       # unit + integration (Quarkus, in-JVM)
./mvnw verify                     # + a few longer ITs
cd e2e && bash test_protolake.sh  # full end-to-end (~20 min cold)

# Local image (e2e tests expect this tag)
docker build -t protolake-proto-lake:latest .

# Run a lake against a local image (in the lake dir, not here)
PROTOLAKE_IMAGE=protolake-proto-lake:latest ./protolakew build
```

The e2e harness expects `../../protolake-gazelle/` as a sibling checkout; override
with `PROTOLAKE_GAZELLE_SOURCE_PATH`.

## Project structure

- `src/main/java/io/vdp/protolake/` — Java service (Quarkus, picocli CLI, gRPC,
  build pipeline, workspace initialization)
- `src/main/resources/templates/` — files that get rendered/copied into a lake at
  init time (Bazel `tools/`, publishers, gazelle wrapper, release-please workflows)
- `src/main/proto/` — service protos (`LakeService`, `BundleService`)
- `e2e/` — bash + Python scripts that build the Docker image and run a real lake
  through the full pipeline
- `Dockerfile` — multi-stage; produces `ghcr.io/cohub-space/protolake:latest`

The repo defaults to fetching `protolake-gazelle` at the tag pinned in
`WorkspaceInitializer.createModuleBazel` (currently `v0.5.0`). For local
development of both, use `PROTOLAKE_GAZELLE_SOURCE_PATH=/path/to/protolake-gazelle`.

## Knowledge

@../cohub-knowledge/docs/knowledge/protolake/index.md

@../cohub-knowledge/rules/java.md
@../cohub-knowledge/rules/api-design.md
@../cohub-knowledge/rules/code-comments.md
@../cohub-knowledge/rules/git-workflow.md
