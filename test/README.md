# protolake board e2e

Hand-placed per the
[CoHub board e2e standard](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/designs/cross-cutting/e2e-tests.md).
protolake is **not yet a VDP-emitted board** — the structure mirrors what
the engine would emit if it were authored as a Board proto.

## Run

```bash
./test/run.py smoke    # Karate @smoke — service starts + gRPC reflection works (<1 min)
./test/run.py e2e      # the legacy pipeline suites, on this host (~20 min cold build each)
./test/run.py all      # both
```

The legacy suites drive the stack themselves with `docker compose`, `jq`,
`grpcurl`, `curl` and `python3`, so they run on the host, not in the Karate
runner image; `run.py` checks those tools first (macOS:
`brew install jq grpcurl`). They also need a protolake-gazelle checkout:
the sibling `../protolake-gazelle`, or `PROTOLAKE_GAZELLE_SOURCE_PATH`. A
suite passes only when its script exits 0.

First run builds `cohub-karate-runner:1.5.2` locally (Karate OSS +
grpcurl on `eclipse-temurin:17-jre-jammy`); subsequent runs use the
cached image. The protolake stack is brought up + torn down by `run.py`.

(On Windows or when `chmod +x` doesn't apply, invoke as
`python test/run.py smoke`.)

## Layout

```
test/
├── README.md              this file
├── run.py                 entry point (Python, cross-platform)
├── docker-compose.yml     protolake stack (proto-lake-service)
├── karate-config.js       per-env URLs + ports
├── smoke.feature          @smoke probes (HTTP /q/health + gRPC reflection)
├── e2e/                   native Karate @e2e features (none yet)
├── legacy/                full-pipeline bash suites, run on the host by `run.py e2e`
│   ├── test_protolake.sh         gRPC API path through full build pipeline
│   ├── test_cli.sh               CLI path through protolakew wrapper
│   └── test_remote_publish.sh    remote publish flow with mock server
├── fixtures/
│   └── test-protos/       proto fixtures (company_a + company_b)
└── runner/
    └── Dockerfile         karate-runner image build context
```

## Legacy suites

The bash suites under `legacy/` are the only end-to-end coverage of the
build pipeline (gazelle → buf → bazel → bundle → publish). Each runs from
`test/`, where the stack's `docker-compose.yml` lives, and exits non-zero
on any failed check. Native Karate equivalents, for clearer per-area
assertions, would go under `e2e/`.

## Add a scenario

Smoke: append a `Scenario:` to `smoke.feature` (must run in <5 min total).

E2E: drop a new `*.feature` under `test/e2e/` and tag its scenarios `@smoke`
to run in the smoke tier; `run.py` passes `e2e/` to Karate once it holds a
feature.
Service URLs are accessible via `services.proto_lake.httpUrl` /
`services.proto_lake.grpcTarget` from `karate-config.js`.

For gRPC, `karate-config.js` exposes a `grpc` helper that wraps grpcurl
and parses JSON responses:

```gherkin
  Scenario: list services on protolake
    * def list = grpc.list(services.proto_lake.grpcTarget)
    * match list contains 'protolake.v1.LakeService'

  Scenario: create a lake via gRPC and assert on response
    * def resp = grpc.call(services.proto_lake.grpcTarget,
                           'protolake.v1.LakeService/CreateLake',
                           { lake: { name: 'test-lake', display_name: 'Test' } })
    * match resp.name == 'lakes/test-lake'
```

`grpc.call` accepts `opts.headers` for custom metadata. Unary RPCs only.
