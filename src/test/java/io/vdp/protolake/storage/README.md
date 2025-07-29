# ProtoLake Storage Tests

This directory contains comprehensive tests for the ProtoLake storage implementation.

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
  - Transaction handling
  - Error recovery

### Discovery Tests
- **LakeDiscoveryServiceTest** - Tests lake discovery from filesystem:
  - lake.yaml parsing
  - Prefix calculation
  - Nested lake detection
  - Refresh operations

- **BundleDiscoveryServiceTest** - Tests bundle discovery:
  - bundle.yaml parsing
  - Package prefix conversion
  - Configuration parsing
  - Bundle synchronization

### Integration Tests
- **StorageIntegrationTest** - End-to-end workflow tests:
  - Complete create → discover → build workflows
  - Multi-lake scenarios
  - Filesystem synchronization
  - Branch-based development
  - Performance testing

### Validation Tests
- **ValidationTest** - Tests validation rules:
  - Lake validation (name, path, prefix, config)
  - Bundle validation (package format, version, language config)
  - Complex validation scenarios

## Test Configuration

Tests use a temporary directory structure and isolated SQLite databases. The ApplicationStartupListener is excluded in tests to prevent conflicts.

Key configuration in `application.properties`:
```properties
protolake.storage.base-path=${java.io.tmpdir}/proto-lake-test
protolake.storage.db-path=${protolake.storage.base-path}/test.db
quarkus.arc.exclude-types=io.vdp.protolake.storage.ApplicationStartupListener
```

## Running Tests

```bash
# Run all storage tests
mvn test -Dtest="io.vdp.protolake.storage.*Test"

# Run specific test class
mvn test -Dtest="SqliteStorageServiceTest"

# Run with debug logging
mvn test -Dtest="StorageIntegrationTest" -Dquarkus.log.level=DEBUG
```

## Test Data

Tests create test data dynamically using helper methods:
- `createTestLake()` - Creates lake proto
- `createTestBundle()` - Creates bundle proto  
- `createTestLakeStructure()` - Creates complete filesystem structure
- `createLakeYaml()` / `createBundleYaml()` - Creates YAML files

Each test runs in isolation with its own temporary directory.
