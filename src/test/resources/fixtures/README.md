# Test Fixtures

This directory contains test fixtures for ProtoLake storage tests.

## Structure

The test fixtures are created dynamically by the test classes to ensure clean state for each test. The base test class `StorageTestBase` provides utilities for creating:

- Lake structures with `lake.yaml` files
- Bundle structures with `bundle.yaml` files  
- Proto files for testing
- Complex multi-lake scenarios

## Usage

Tests use the helper methods in `StorageTestBase` to create test data:

```java
// Create a simple lake
createLakeYaml(path, "lake-name", "prefix");

// Create a bundle
createBundleYaml(path, "bundle-name", "com.package.prefix");

// Create a complete lake structure with bundles
createTestLakeStructure("lake-name", "prefix", Map.of(
    "bundle1", "com.test",
    "bundle2", "com.test.sub"
));
```

The fixtures are created in temporary directories and cleaned up after each test.
