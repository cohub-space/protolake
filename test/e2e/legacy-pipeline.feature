@e2e
Feature: protolake legacy bash pipeline
  # Wrapper that delegates to the existing test_protolake.sh / test_cli.sh /
  # test_remote_publish.sh suites under test/legacy/. They cover the full
  # build pipeline (gazelle → buf → bazel → bundle → publish) and are
  # preserved during the migration to the standard test/ layout. Each
  # script self-cleans on exit.
  #
  # Cold builds take ~20 minutes (C++ gRPC compilation); these are nightly /
  # pre-promote candidates, not PR gates.
  #
  # TODO(t/PL-e2bc-protolake-e2e-restructure): superseded by VDP-e8e9;
  # reopen if a follow-up wants the bash logic translated into native
  # Karate scenarios for clearer per-area assertions.

  Scenario: legacy test_protolake.sh — gRPC API path through full build
    * def script = '/test/legacy/test_protolake.sh'
    * def result = karate.exec({ line: 'bash ' + script, useShell: true, redirectErrorStream: true })
    * print result
    * match result contains 'Build completed'

  Scenario: legacy test_cli.sh — CLI path through protolakew wrapper
    * def script = '/test/legacy/test_cli.sh'
    * def result = karate.exec({ line: 'bash ' + script, useShell: true, redirectErrorStream: true })
    * print result
    * match result !contains 'FAIL'

  Scenario: legacy test_remote_publish.sh — remote publish flow with mock server
    * def script = '/test/legacy/test_remote_publish.sh'
    * def result = karate.exec({ line: 'bash ' + script, useShell: true, redirectErrorStream: true })
    * print result
    * match result !contains 'FAIL'
