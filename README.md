# Proto Lake

Proto Lake is a centralized protocol buffer management system. It takes
a git repository of `.proto` files organized into **bundles** and produces
language-specific packages (JAR, Python wheel, npm tarball) that
services consume via standard package managers — solving the
share-protos-across-services problem without each service running its
own codegen.

For deeper architecture and design rationale see the
[cohub-knowledge protolake docs](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/knowledge/protolake/protolake.md).

## Quick start

Prereqs: Docker, git.

```bash
# 1. Initialize a new lake (docker-direct, since `protolakew` doesn't exist yet).
mkdir my-lake && cd my-lake
docker run --rm -v "$(pwd):/proto-lake" \
  -e PROTO_LAKE_BASE_PATH=/proto-lake \
  ghcr.io/cohub-space/protolake:latest \
  init --name my-lake \
       --java-group-id com.example.proto \
       --python-package-prefix example_proto \
       --js-package-scope @example

# 2. Create a bundle inside the lake.
./protolakew create-bundle --name my-service \
  --java-artifact-id my-service-proto \
  --python-package-name example_my_service_proto \
  --js-package-name @example/my-service-proto

# 3. Add your protos under my-service/v1/, then build.
./protolakew build

# 4. Install to local registries (~/.m2, ~/.cache/pip, npm link).
./protolakew publish
```

After step 4, the published artifacts are usable by local builds:
- Java: `~/.m2/repository/com/example/proto/my-service-proto/<version>/`
- Python: `~/.cache/pip/simple/example_my_service_proto/`
- JavaScript/TypeScript: `~/.proto-lake/npm-packages/` (or `npm link`ed
  if you passed `--js-target <project>`)

## Daily commands

The `protolakew` wrapper script (generated into your lake by
`init`) is the main entry point — it runs the protolake docker image
with the right mounts and env. Common commands:

| Command | What it does |
|---|---|
| `protolakew build` | Run gazelle → buf validation → bazel build. No publish. |
| `protolakew build --install-local` | Build + publish to local registries. |
| `protolakew publish [--bundle PATH]` | Build + publish for one bundle (path-form, e.g. `cohub/vdp`) or the whole lake. Used by tag-driven CI. |
| `protolakew validate` | Run buf lint / breaking checks without a build. CI gate. |
| `protolakew create-bundle --name X` | Scaffold a new bundle under the lake. |
| `protolakew dep show <bundle>` | Print the dependency snippet (Maven/Gradle/npm) for consumers. |

Add `--pull always` for CI to ensure the latest image; `--js-target
<path>` (repeatable) to npm-workspace-install into a frontend project.

## Publishing to a remote registry

ProtoLake supports two publishing modes — pick one:

**Tag-driven via release-please (recommended).** Lake-init seeds a
`release-please-config.json`, `.release-please-manifest.json`, and two
`.github/workflows/` files. Configure repository variables
(`GCP_PROJECT_ID`, `GCP_REGION`, `WIF_PROVIDER`,
`CI_SERVICE_ACCOUNT`) and push to `main`. release-please opens a
release PR; merging it tags `<package-name>-v<version>` per bundle,
which fires `publish.yml` and runs `protolakew publish --bundle <path>`.
Bundle versions are bumped automatically based on
`feat:` / `fix:` commit messages. See the cross-cutting design doc:
[`internal-lib-versioning.md`](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/designs/cross-cutting/internal-lib-versioning.md).

**Manual flag-based.** For ad-hoc publishes:

```bash
./protolakew publish \
  --maven-repo https://us-central1-maven.pkg.dev/PROJECT/maven-internal \
  --pypi-repo  https://us-central1-python.pkg.dev/PROJECT/python-internal/ \
  --npm-registry-url https://us-central1-npm.pkg.dev/PROJECT/npm-internal/ \
  --registry-token "$(gcloud auth print-access-token)"
```

The bearer token maps internally to `MAVEN_USER=oauth2accesstoken` +
`MAVEN_PASSWORD=<token>` for `maven_publish` (Google AR convention).

## Configuration

Two files live in every lake. See the [knowledge doc's "Configuration
overview"](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/knowledge/protolake/protolake.md#configuration-overview)
for the full schema and worked examples.

- **`lake.yaml`** at the lake root — language defaults (Java group_id,
  Python package_prefix, JS scope), Bazel module versions, remote-cache
  config.
- **`bundle.yaml`** in each bundle directory — bundle name, version, and
  per-language overrides. The `version` field is the source of truth;
  `protolake-gazelle` reads it at BUILD-generation time and bakes it
  into publish coordinates.

## Local dev (working on protolake itself)

```bash
# Build a local image; tag matches what e2e expects.
./mvnw package -DskipTests
docker build -t protolake-proto-lake:latest .

# Use that image when running protolakew.
PROTOLAKE_IMAGE=protolake-proto-lake:latest ./protolakew build
```

To work on `protolake-gazelle` simultaneously, point at a local
checkout:

```bash
PROTOLAKE_GAZELLE_SOURCE_PATH=/path/to/protolake-gazelle ./protolakew build
```

The image's default gazelle ref is `v0.3.0`; override via
`PROTOLAKE_GAZELLE_GIT_TAG` or `PROTOLAKE_GAZELLE_GIT_COMMIT`.

For the full dev iteration loops (templates, gazelle, both), tests, and
release process, see
[`dev-workflow.md`](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/knowledge/protolake/dev-workflow.md).

## Tests

```bash
./mvnw test                       # Java unit + IT
./mvnw verify                     # + integration tests
cd e2e && bash test_protolake.sh  # full end-to-end (~20 min cold)
```

The e2e harness expects `../../protolake-gazelle/` as a sibling
checkout, or override with `PROTOLAKE_GAZELLE_SOURCE_PATH`.

## Architecture (briefly)

Proto Lake consists of several services packaged in a single Docker image:

- **Lake / Bundle services** — gRPC + CLI surface for managing lakes
  and bundles
- **Build Orchestrator** — runs the pipeline (gazelle → buf →
  bazel build → publish)
- **Workspace Initializer** — generates `MODULE.bazel`, `BUILD.bazel`,
  `tools/`, `protolakew`, and the release-please scaffolding
- **Storage** — SQLite at `~/.proto-lake/protolake.db` for lake/bundle
  metadata and build history

Full architecture in the cohub-knowledge
[protolake.md](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/knowledge/protolake/protolake.md)
and [build-pipeline.md](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/designs/protolake/build-pipeline.md).

## API surface

The same binary runs as either a CLI (this README) or a gRPC server
(`docker run … serve`, port 9090) for programmatic integration. The
gRPC API is documented in
[`api-design.md`](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/designs/protolake/api-design.md).
Prefer the CLI for daily use.

## Related projects

- [protolake-gazelle](https://github.com/cohub-space/protolake-gazelle) — Gazelle extension that emits BUILD files for proto bundles
- [protolake-ui](../protolake-ui/) — Optional desktop app for managing lakes via the gRPC API
