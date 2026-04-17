# Proto Lake E2E Tests

End-to-end test suite for the protolake build pipeline. These tests build the
protolake Docker image locally, exercise the full pipeline (gazelle → buf →
bazel → bundle → publish), and assert on the generated artifacts.

## Layout

| Path | Purpose |
|---|---|
| `test_protolake.sh` | gRPC API path: build image → start container → create lake via gRPC → bundles → build → verify BUILD files, Bazel targets, JAR/wheel/npm artifacts, local Maven publish, GetDependencySnippet RPC |
| `test_cli.sh` | CLI path: same flow but via the `protolakew` wrapper (`init`, `create-bundle`, `build`) — covers the real user workflow |
| `test_remote_publish.sh` | Remote publishing flow: starts a mock HTTP server, runs `protolakew build` with `--maven-repo`/`--pypi-repo`/`--npm-registry-url` flags, asserts uploads were captured |
| `docker-compose.yml` | Compose service for `test_protolake.sh` (image build + service exposure) |
| `test-protos/` | Input fixtures (`company_a/platform/service_a` and `company_b/apps/service_b`) — committed |
| `test-lake-output/` | Generated lake (gitignored) |
| `test-cli-output/` | CLI test output (gitignored) |
| `test-remote-output/` | Remote publish test output (gitignored) |

## Prerequisites

- Docker (for image build and runtime)
- `protolake-gazelle` checked out as a sibling repo at `../../protolake-gazelle/` — or pointed at via `PROTOLAKE_GAZELLE_SOURCE_PATH`
- `grpcurl` for `test_protolake.sh` (`brew install grpcurl`)
- `python3` for `test_remote_publish.sh` (mock server)
- ~20 minutes for a cold build (C++ gRPC compilation)

## Run a single test

```bash
cd e2e
bash test_protolake.sh        # gRPC API path
bash test_cli.sh              # CLI/protolakew path
bash test_remote_publish.sh   # Remote publishing
```

Each script self-cleans on exit (removes its output dir + named volumes).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PROTOLAKE_GAZELLE_SOURCE_PATH` | `../../protolake-gazelle` (resolved at script start) | Local checkout of protolake-gazelle to mount into the container |
| `BUILD_TIMEOUT` | `1200` (s) | Cold-build budget before the test fails |

## Iterating on protolake or protolake-gazelle changes

For changes to **protolake** (Java/Quarkus templates and source):

```bash
# from protolake/ repo root
./mvnw package -DskipTests
docker build -t protolake-proto-lake:latest .   # the image tag the e2e expects
cd e2e
bash test_protolake.sh
```

For changes to **protolake-gazelle** (Go):

```bash
# from cohub project root or par workspace
export PROTOLAKE_GAZELLE_SOURCE_PATH=$(cd protolake-gazelle && pwd)
cd protolake/e2e
bash test_protolake.sh   # MODULE.bazel local_path_override picks it up via the mount
```

## What each test asserts (high level)

`test_protolake.sh` Phases:

| Phase | Asserts |
|---|---|
| 1. Docker build | image builds, container becomes healthy |
| 2. Create lake | gRPC `CreateLake` returns success, lake dir exists |
| 3. Create bundles | two bundles created in lake, each with `bundle.yaml` |
| 4. Copy proto files | proto inputs land at expected paths |
| 5. Build (install_local=true) | gazelle generates BUILD files, bazel build succeeds, artifacts published to local Maven |
| 6. Verify BUILD files | proto_library, java_grpc_library, py_grpc_library, etc. present with correct deps |
| 7. Verify Bazel targets | listable via `bazel query` inside container |
| 8. Verify build artifacts | JARs, wheels, npm tarballs exist in `bazel-bin` and contain expected files |
| 9. Verify local publishing | `~/.m2`, `~/.cache/pip/simple`, npm targets contain published artifacts |
| 9.5. GetDependencySnippet RPC | gRPC returns Maven/PyPI/npm dep snippets matching bundle metadata |

`test_cli.sh` mirrors the same flow but invoked through the `protolakew` wrapper (the real user-facing CLI).

`test_remote_publish.sh` swaps local repo destinations for HTTP URLs pointing at a local mock server, then asserts the mock server received the expected PUT/POST requests.

## Adding a new test

The simplest extension is a new `# Phase X` block inside `test_protolake.sh` between existing phases. Use the existing helpers (`pass`, `fail`, `check_file`, `check_file_contains`, `run_in_docker`, `grpc_call`) for consistency. Increment counters via `pass`/`fail` so the final summary reports correctly.

For larger additions (a separate concern, different setup), prefer a new `test_<area>.sh` next to the existing scripts.
