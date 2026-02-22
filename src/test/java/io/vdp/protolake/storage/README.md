# ProtoLake Storage Tests

This directory contains tests for the ProtoLake storage implementation.

## Test Structure

### Base Class
- **StorageTestBase** - Provides common test utilities including:
  - Test directory setup and cleanup
  - Proto, lake, and bundle creation helpers
  - YAML file generation
  - File assertions

### Unit Tests
- **SqliteStorageServiceTest** - Tests database operations:
  - Lake CRUD operations
  - Bundle CRUD operations
  - Build recording and querying
  - Build pruning

### Validation Tests
- **ValidationTest** - Tests validation rules:
  - Lake validation (name, path, prefix, config)
  - Bundle validation (package format, version, language config)
  - Complex validation scenarios

## Running Tests

```bash
# Run all storage tests
mvn test -Dtest="io.vdp.protolake.storage.*Test"

# Run specific test class
mvn test -Dtest="SqliteStorageServiceTest"
```

## Test Data

Tests create test data dynamically using helper methods:
- `createTestLake()` - Creates lake proto
- `createTestBundle()` - Creates bundle proto
- `createTestLakeStructure()` - Creates complete filesystem structure
- `createLakeYaml()` / `createBundleYaml()` - Creates YAML files

Each test runs in isolation with its own temporary directory.
