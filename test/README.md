# protolake board e2e

Hand-placed per the
[CoHub board e2e standard](https://github.com/cohub-space/cohub-knowledge/blob/main/docs/designs/cross-cutting/e2e-tests.md).
protolake is **not yet a VDP-emitted board** — the structure mirrors what
the engine would emit if it were authored as a Board proto.

## Run

```bash
./test/run.py smoke    # @smoke — service starts + gRPC reflection works (<1 min)
./test/run.py e2e      # @e2e   — full bash pipeline (~20 min cold build)
./test/run.py all      # both tags
```

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
├── e2e/
│   └── legacy-pipeline.feature   @e2e wrappers around legacy/*.sh
├── legacy/                bash test scripts (preserved during migration)
│   ├── test_protolake.sh         gRPC API path through full build pipeline
│   ├── test_cli.sh               CLI path through protolakew wrapper
│   └── test_remote_publish.sh    remote publish flow with mock server
├── fixtures/
│   └── test-protos/       proto fixtures (company_a + company_b)
└── runner/
    └── Dockerfile         karate-runner image build context
```

## Migration status

The legacy bash scripts are wrapped as @e2e Karate scenarios via
`karate.exec` so they keep running through `./test/run.py e2e`. PL-e2c1
(CI gate) and PL-e2bc (per-area split) closed as superseded by
VDP-e8e9. If a follow-up wants native Karate equivalents of the bash
phases for clearer per-area assertions, open a new task.

## Add a scenario

Smoke: append a `Scenario:` to `smoke.feature` (must run in <5 min total).

E2E: drop a new `*.feature` under `test/e2e/`, tag scenarios with `@e2e`.
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
