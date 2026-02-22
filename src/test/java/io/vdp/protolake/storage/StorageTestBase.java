package io.vdp.protolake.storage;

import io.vdp.protolake.util.LakeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import protolake.v1.Bundle;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for storage-related tests.
 * 
 * Provides common utilities for:
 * - Creating test lakes and bundles
 * - Setting up test filesystem structures
 * - Database cleanup
 * - YAML file generation
 */
public abstract class StorageTestBase {
    
    protected Path testBasePath;
    protected Path testDbPath;
    
    @BeforeEach
    void setupBase() throws IOException {
        // Use the same base path as configured in application.properties
        testBasePath = Paths.get(System.getProperty("java.io.tmpdir"), "proto-lake-test");
        
        // Clean and recreate the directory for each test
        if (Files.exists(testBasePath)) {
            deleteRecursively(testBasePath);
        }
        Files.createDirectories(testBasePath);
        
        // Database path
        testDbPath = testBasePath.resolve("test.db");
    }
    
    @AfterEach
    void cleanupBase() throws IOException {
        // Clean up test directory
        if (testBasePath != null && Files.exists(testBasePath)) {
            deleteRecursively(testBasePath);
        }
    }
    
    /**
     * Creates a test lake proto with default values.
     */
    protected Lake createTestLake(String name, String displayName, String description) {
        return Lake.newBuilder()
            .setName(LakeUtil.toResourceName(name))
            .setDisplayName(displayName)
            .setDescription(description)
            .setConfig(LakeConfig.getDefaultInstance())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
    
    /**
     * Creates a test lake with a prefix.
     */
    protected Lake createTestLakeWithPrefix(String name, String prefix) {
        return createTestLake(name, name + " Lake", "Test lake with prefix")
            .toBuilder()
            .setLakePrefix(prefix)
            .build();
    }
    
    /**
     * Creates a test bundle proto.
     */
    protected Bundle createTestBundle(String lakeName, String bundleName, String bundlePrefix) {
        return Bundle.newBuilder()
            .setName(String.format("lakes/%s/bundles/%s", lakeName, bundleName))
            .setDisplayName(bundleName + " Bundle")
            .setDescription("Test bundle")
            .setBundlePrefix(bundlePrefix)
            .setVersion("1.0.0")
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
    
    /**
     * Creates a lake.yaml file at the specified path.
     */
    protected void createLakeYaml(Path lakePath, String lakeName, String lakePrefix) throws IOException {
        Files.createDirectories(lakePath);
        
        String yamlContent = String.format("""
            name: "%s"
            lake_prefix: "%s"

            config:
              module_bazel:
                protobuf_version: "31.1"
                grpc_version: "1.78.0"
                rules_proto_grpc_version: "5.8.0"
            """, lakeName, lakePrefix != null ? lakePrefix : "");
        
        Files.writeString(lakePath.resolve("lake.yaml"), yamlContent);
    }
    
    /**
     * Creates a bundle.yaml file at the specified path.
     */
    protected void createBundleYaml(Path bundlePath, String bundleName, String bundlePrefix) throws IOException {
        Files.createDirectories(bundlePath);
        
        String yamlContent = String.format("""
            bundle:
              name: "%s"
              bundle_prefix: "%s"
              version: "1.0.0"
              display_name: "%s Bundle"
              description: "Test bundle"
            
            config:
              languages:
                java:
                  enabled: true
                  group_id: "com.test"
                  artifact_id: "%s_proto"
                python:
                  enabled: true
                  package_name: "test_%s_proto"
            """, bundleName, bundlePrefix != null ? bundlePrefix : "", bundleName, bundleName, bundleName);
        
        Files.writeString(bundlePath.resolve("bundle.yaml"), yamlContent);
    }
    
    /**
     * Creates a simple test proto file.
     */
    protected void createProtoFile(Path dir, String fileName, String packageName) throws IOException {
        Files.createDirectories(dir);
        
        String protoContent = String.format("""
            syntax = "proto3";
            
            package %s;
            
            message TestMessage {
              string id = 1;
              string name = 2;
            }
            """, packageName);
        
        Files.writeString(dir.resolve(fileName), protoContent);
    }
    
    /**
     * Creates a complete test lake structure with bundles.
     */
    protected Path createTestLakeStructure(String lakeName, String lakePrefix, 
                                         Map<String, String> bundles) throws IOException {
        Path lakePath = testBasePath;
        if (lakePrefix != null && !lakePrefix.isEmpty()) {
            lakePath = lakePath.resolve(lakePrefix);
        }
        lakePath = lakePath.resolve(lakeName);
        
        // Create lake.yaml
        createLakeYaml(lakePath, lakeName, lakePrefix);
        
        // Create bundles
        for (Map.Entry<String, String> bundle : bundles.entrySet()) {
            String bundleName = bundle.getKey();
            String bundlePrefix = bundle.getValue();
            
            Path bundlePath = lakePath;
            if (bundlePrefix != null && !bundlePrefix.isEmpty()) {
                bundlePath = bundlePath.resolve(bundlePrefix.replace('.', '/'));
            }
            bundlePath = bundlePath.resolve(bundleName);
            
            createBundleYaml(bundlePath, bundleName, bundlePrefix);
            
            // Add a proto file
            String protoPackage = bundlePrefix != null && !bundlePrefix.isEmpty() 
                ? bundlePrefix + "." + bundleName 
                : bundleName;
            createProtoFile(bundlePath.resolve("v1"), "test.proto", protoPackage);
        }
        
        return lakePath;
    }
    
    /**
     * Recursively deletes a directory.
     */
    protected void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Best effort
                    }
                });
        }
    }
    
    /**
     * Asserts that a file exists and contains expected content.
     */
    protected void assertFileContains(Path file, String... expectedContent) throws IOException {
        assertThat(file).exists();
        String content = Files.readString(file);
        
        for (String expected : expectedContent) {
            assertThat(content)
                .as("File " + file + " should contain: " + expected)
                .contains(expected);
        }
    }
    
    /**
     * Gets a configured SqliteStorageService for testing.
     * Subclasses should inject the actual service.
     */
    protected abstract SqliteStorageService getStorageService();
}
