@smoke
Feature: protolake smoke — service responds
  # Generated scaffold-once by CoHub.
  # Customize freely — won't be regenerated. See test/README.md to add
  # @e2e scenarios (under test/e2e/) for richer flows.

  Background:
    * configure connectTimeout = 5000
    * configure readTimeout = 5000

  Scenario: proto_lake HTTP /q/health responds 200
    * url services.proto_lake.httpUrl
    Given path '/q/health'
    When method get
    Then status 200

  Scenario: proto_lake gRPC reflection lists LakeService
    * def cmd = 'grpcurl -plaintext ' + services.proto_lake.grpcTarget + ' list'
    * def result = karate.exec({ line: cmd, redirectErrorStream: true })
    * match result contains 'protolake.v1.LakeService'
