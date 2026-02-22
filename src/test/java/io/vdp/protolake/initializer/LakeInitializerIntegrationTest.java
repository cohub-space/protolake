package io.vdp.protolake.initializer;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.template.TemplateEngine;
import io.vdp.protolake.util.git.GitCommand;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.ModuleBazelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for LakeInitializer.
 * 
 * Tests the complete lake initialization process including:
 * - Directory structure creation
 * - Configuration file generation
 * - Git repository initialization
 * - Lake prefix handling
 */
@QuarkusTest
public class LakeInitializerIntegrationTest extends InitializerTestBase {
    
    @Inject
    LakeInitializer lakeInitializer;
    
    @Inject
    WorkspaceInitializer workspaceInitializer;
    
    @Inject
    TemplateEngine templateEngine;
    
    @Inject
    GitCommand gitCommand;
    
    // Inject the configured base path for testing
    @ConfigProperty(name = "protolake.storage.base-path")
    String configuredBasePath;
    
    @BeforeEach
    void setup() throws Exception {
        // The base path is already configured in test application.properties
        // Just verify it's using our test directory
        Path expectedBasePath = Paths.get(System.getProperty("java.io.tmpdir"), "proto-lake-test");
        
        // Ensure the test directory exists and is clean
        if (Files.exists(expectedBasePath)) {
            deleteRecursively(expectedBasePath);
        }
        Files.createDirectories(expectedBasePath);
        
        // Update basePath in the test base class to match the configured path
        basePath = expectedBasePath;
    }
    
    @Test
    void testInitializeLakeWithDefaults() throws IOException {
        // Create a simple lake with minimal configuration
        Lake lake = createTestLake("test-lake", "Test Lake", "A test lake");
        
        // Initialize the lake
        Lake result = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        );
        
        // Verify the lake was created at the correct path
        Path lakePath = LakeUtil.getLocalPath(result, basePath.toString());
        assertDirectoryExists(lakePath);
        
        // Verify all expected files were created
        assertFileExists(lakePath, 
            "lake.yaml",
            "MODULE.bazel",
            ".bazelrc",
            "BUILD.bazel",
            "README.md"
        );
        
        // Verify lake.yaml content
        assertFileContains(lakePath.resolve("lake.yaml"),
            "name: \"test-lake\""  // Just the lake ID
        );
        
        // Verify Git repository was initialized
        assertDirectoryExists(lakePath.resolve(".git"));
        
        // Verify basic directory structure
        assertDirectoryStructure(lakePath,
            "bundles",
            "common",
            "tools",
            "tools/bundler",
            "tools/publish"
        );
    }
    
    @Test
    void testInitializeLakeWithPrefix() throws IOException {
        // Create a lake with a prefix
        Lake lake = createTestLake("team-alpha", "Team Alpha Lake", "Lake for team alpha");
        lake = lake.toBuilder()
            .setLakePrefix("org/teams")
            .build();
        
        // Initialize the lake
        Lake result = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        );
        
        // Verify the lake was created at the prefixed path
        Path expectedPath = basePath.resolve("org/teams/team-alpha");
        assertDirectoryExists(expectedPath);
        
        // Verify lake.yaml contains the prefix
        assertFileContains(expectedPath.resolve("lake.yaml"),
            "name: \"team-alpha\"",  // Just the lake ID
            "lake_prefix: \"org/teams\""
        );
    }
    
    @Test
    void testInitializeLakeWithCustomVersions() throws IOException {
        // Create lake with custom MODULE.bazel versions
        LakeConfig config = LakeConfig.newBuilder()
            .setModuleBazel(ModuleBazelConfig.newBuilder()
                .setProtobufVersion("28.3")
                .setGrpcVersion("1.65.0")
                .setRulesProtoGrpcVersion("5.1.0")
                .build())
            .build();
            
        Lake lake = createTestLake("versioned-lake", "Versioned Lake", "Lake with custom versions")
            .toBuilder()
            .setConfig(config)
            .build();
        
        // Initialize the lake
        Lake result = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        );
        
        Path lakePath = LakeUtil.getLocalPath(result, basePath.toString());
        
        // Verify MODULE.bazel contains the custom versions
        assertFileContains(lakePath.resolve("MODULE.bazel"),
            "bazel_dep(name = \"protobuf\", version = \"28.3\"",  // protobuf version
            "bazel_dep(name = \"rules_proto_grpc\", version = \"5.1.0\"",  // rules_proto_grpc version
            "io.grpc:grpc-api:1.65.0"  // gRPC version in maven.install
        );
        
        // Verify lake.yaml was created
        assertFileExists(lakePath, "lake.yaml");
    }
    
    @Test
    void testInitializeLakeWithExistingDirectory() throws IOException {
        // Create a lake
        Lake lake = createTestLake("existing-lake", "Existing Lake", "Should fail");

        // Create the directory first with a lake.yaml to simulate an already-initialized lake
        Path lakePath = LakeUtil.getLocalPath(lake, basePath.toString());
        Files.createDirectories(lakePath);
        Files.writeString(lakePath.resolve("lake.yaml"), "name: existing-lake\n");

        // Try to initialize - should fail because lake.yaml already exists
        assertThatThrownBy(() -> lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),  // Pass just the lake ID, not the full resource name
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lake already initialized");
    }
    
    @Test
    void testInitializeLakeCreatesTools() throws IOException {
        // Create a lake
        Lake lake = createTestLake("tools-lake", "Tools Lake", "Lake with tools");
        
        // Initialize the lake
        Lake result = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        );
        
        Path lakePath = LakeUtil.getLocalPath(result, basePath.toString());
        Path toolsPath = lakePath.resolve("tools");
        
        // Verify all bundler tools
        assertExecutableFile(toolsPath.resolve("bundler/jar_bundler.py"));
        assertExecutableFile(toolsPath.resolve("bundler/wheel_builder.py"));
        assertExecutableFile(toolsPath.resolve("bundler/npm_bundler.py"));
        
        // Verify all publishing tools
        assertExecutableFile(toolsPath.resolve("publish/maven_publisher.py"));
        assertExecutableFile(toolsPath.resolve("publish/pypi_publisher.py"));
        assertExecutableFile(toolsPath.resolve("publish/npm_publisher.py"));
        assertFileExists(toolsPath, "publish/publisher_utils.py");
        
        // Verify utility scripts
        assertExecutableFile(toolsPath.resolve("gazelle_wrapper.py"));
        
        // Verify proto bundle rules
        assertFileExists(toolsPath, "proto_bundle.bzl");
    }
    
    @Test
    void testInitializeLakeWithBufConfiguration() throws IOException {
        // Create a lake with validation enabled
        Lake lake = createTestLake("validated-lake", "Validated Lake", "Lake with buf validation");
        
        // Initialize the lake
        Lake result = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        );
        
        Path lakePath = LakeUtil.getLocalPath(result, basePath.toString());
        
        // Verify buf configuration files
        assertFileExists(lakePath, "buf.yaml", "buf.gen.yaml");
        
        // Verify buf.yaml content
        assertFileContains(lakePath.resolve("buf.yaml"),
            "version: v1",
            "breaking:",
            "lint:"
        );
    }
    
    @Test
    void testCleanupOnFailure() throws IOException {
        // Test that cleanup happens when initialization fails AFTER directory creation
        // We do NOT cleanup pre-existing directories to avoid data loss
        
        // Create a lake that will fail during initialization
        Lake lake = createTestLake("fail-lake", "Fail Lake", "Should cleanup on failure");

        // Calculate the expected lake path
        Path lakePath = LakeUtil.getLocalPath(lake, basePath.toString());

        // Create a scenario where initialization fails after directory is created
        // by creating a lake.yaml to simulate an already-initialized lake
        Files.createDirectories(lakePath);
        Files.writeString(lakePath.resolve("lake.yaml"), "name: fail-lake\n");

        // Try to initialize - should fail because lake.yaml already exists
        assertThatThrownBy(() -> lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lake already initialized");

        // Verify the directory was NOT cleaned up (we don't delete pre-existing directories)
        assertThat(lakePath).exists();

        // Clean up for the test
        Files.delete(lakePath.resolve("lake.yaml"));
        Files.delete(lakePath);
    }
    
    @Test
    void testCleanupOnInitializationFailure() throws IOException {
        // Test that re-initializing an already-initialized lake is rejected
        // and existing content is preserved

        Lake lake = createTestLake("preserve-lake", "Preserve Lake", "Should not delete existing");
        Path lakePath = LakeUtil.getLocalPath(lake, basePath.toString());

        // Create existing directory with lake.yaml and some content
        Files.createDirectories(lakePath);
        Files.writeString(lakePath.resolve("lake.yaml"), "name: preserve-lake\n");
        Path existingFile = lakePath.resolve("important-data.txt");
        Files.writeString(existingFile, "This should not be deleted");

        // Try to initialize - should fail because lake.yaml already exists
        assertThatThrownBy(() -> lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(lake.getName()),
            lake.getDisplayName(),
            lake.getDescription(),
            lake.getConfig(),
            lake.getLakePrefix()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lake already initialized");

        // Verify the existing content was preserved
        assertThat(lakePath).exists();
        assertThat(existingFile).exists();
        assertThat(Files.readString(existingFile)).isEqualTo("This should not be deleted");

        // Clean up
        Files.delete(existingFile);
        Files.delete(lakePath.resolve("lake.yaml"));
        Files.delete(lakePath);
    }
    
    // Helper method to create a test lake
    private Lake createTestLake(String name, String displayName, String description) {
        return Lake.newBuilder()
            .setName(LakeUtil.toResourceName(name))
            .setDisplayName(displayName)
            .setDescription(description)
            .setConfig(LakeConfig.getDefaultInstance())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
}
