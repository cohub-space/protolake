package io.vdp.protolake.initializer;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.template.TemplateEngine;
import io.vdp.protolake.util.git.GitCommand;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.JavaConfig;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.LanguageConfig;
import protolake.v1.PythonConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for BundleInitializer.
 * 
 * Tests the complete bundle initialization process including:
 * - Bundle directory creation with prefix handling
 * - bundle.yaml generation with correct configuration
 * - Proto file generation
 * - Git integration
 */
@QuarkusTest
public class BundleInitializerIntegrationTest extends InitializerTestBase {
    
    @Inject
    BundleInitializer bundleInitializer;
    
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
    
    private Lake testLake;
    private Path lakePath;
    
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
        
        // Create a test lake for bundle tests
        testLake = createTestLake("test-lake");
        testLake = lakeInitializer.initializeLake(
            LakeUtil.extractLakeId(testLake.getName()),
            testLake.getDisplayName(),
            testLake.getDescription(),
            testLake.getConfig(),
            testLake.getLakePrefix()
        );
        lakePath = LakeUtil.getLocalPath(testLake, configuredBasePath);
    }
    
    @Test
    void testInitializeBundleWithDefaults() throws IOException {
        // Create a simple bundle
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "user-service",
            "User Service",
            "User management protos",
            null, // no prefix
            null  // default config
        );
        
        // Verify bundle was created at correct path
        Path bundlePath = BundleUtil.calculateBundlePath(testLake, bundle, basePath.toString());
        assertDirectoryExists(bundlePath);
        assertThat(bundlePath).isEqualTo(lakePath.resolve("user-service"));
        
        // Verify bundle.yaml was created
        assertFileExists(bundlePath, "bundle.yaml");
        assertFileContains(bundlePath.resolve("bundle.yaml"),
            "name: user-service",
            "display_name: User Service",
            "description: User management protos",
            "version: \"1.0.0\""
        );
        
        // Verify example proto was created
        assertFileExists(bundlePath, "example.proto");
        assertFileContains(bundlePath.resolve("example.proto"),
            "package user_service;", // Should use bundle name as package when no prefix
            "service UserServiceService {",
            "message UserServiceRequest {",
            "message UserServiceResponse {"
        );
        
        // Verify README was created
        assertFileExists(bundlePath, "README.md");
        assertFileContains(bundlePath.resolve("README.md"),
            "# User Service",
            "User management protos"
        );
    }
    
    @Test
    void testInitializeBundleWithDotPrefix() throws IOException {
        // Create bundle with dot-notation prefix
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "user",
            "User Bundle",
            "User proto definitions",
            "com.company.protos", // dot notation prefix
            null
        );
        
        // Verify bundle was created at correct nested path
        Path expectedPath = lakePath.resolve("com/company/protos/user");
        Path bundlePath = BundleUtil.calculateBundlePath(testLake, bundle, basePath.toString());
        assertThat(bundlePath).isEqualTo(expectedPath);
        assertDirectoryExists(bundlePath);
        
        // Verify bundle.yaml contains the prefix
        assertFileContains(bundlePath.resolve("bundle.yaml"),
            "name: user",
            "bundle_prefix: com.company.protos"
        );
        
        // Verify proto package combines prefix and bundle name
        assertFileContains(bundlePath.resolve("example.proto"),
            "package com.company.protos.user;"
        );
    }
    
    @Test
    void testInitializeBundleWithLanguageConfig() throws IOException {
        // Create bundle with custom language configuration
        BundleConfig config = BundleConfig.newBuilder()
            .setLanguages(LanguageConfig.newBuilder()
                .setJava(JavaConfig.newBuilder()
                    .setEnabled(true)
                    .setGroupId("com.mycompany.proto")
                    .setArtifactId("user-proto-custom")
                    .build())
                .setPython(PythonConfig.newBuilder()
                    .setEnabled(false) // Disable Python
                    .build())
                .build())
            .build();
            
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "custom-bundle",
            "Custom Bundle",
            "Bundle with custom config",
            null,
            config
        );
        
        Path bundlePath = BundleUtil.calculateBundlePath(testLake, bundle, basePath.toString());
        
        // Verify bundle.yaml contains custom configuration with nested structure
        assertFileContains(bundlePath.resolve("bundle.yaml"),
            "languages:",
            "java:",
            "enabled: true",
            "group_id: com.mycompany.proto",
            "artifact_id: user-proto-custom",
            "python:",
            "enabled: false"
        );
    }
    
    @Test
    void testInitializeBundleWithExistingDirectory() throws IOException {
        // Create bundle directory first
        Path existingPath = lakePath.resolve("existing-bundle");
        Files.createDirectories(existingPath);
        Files.writeString(existingPath.resolve("bundle.yaml"), "existing content");
        
        // Try to create bundle - should fail
        assertThatThrownBy(() -> bundleInitializer.initializeBundle(
            testLake,
            "existing-bundle",
            "Existing Bundle",
            "Should fail",
            null,
            null
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Bundle already exists");
    }
    
    @Test
    void testInitializeBundleWithNestedPrefix() throws IOException {
        // Create bundle with deeply nested prefix
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "analytics",
            "Analytics Bundle",
            "Analytics proto definitions",
            "com.company.platform.services.data",
            null
        );
        
        // Verify deep directory structure was created
        Path expectedPath = lakePath.resolve("com/company/platform/services/data/analytics");
        assertDirectoryExists(expectedPath);
        
        // Verify proto package is correct
        assertFileContains(expectedPath.resolve("example.proto"),
            "package com.company.platform.services.data.analytics;"
        );
    }
    
    @Test
    void testInitializeBundlePreservesGitHistory() throws IOException {
        // Create a bundle
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "git-test",
            "Git Test Bundle",
            "Testing git integration",
            null,
            null
        );
        
        // Verify files were added to git
        boolean isClean = gitCommand.isClean(lakePath);
        assertThat(isClean).isTrue(); // Should be clean after commit
        
        // Verify we can get the commit history
        String currentCommit = gitCommand.getCurrentCommit(lakePath);
        assertThat(currentCommit).isNotEmpty();
    }
    
    @Test
    void testInitializeBundleWithSpecialCharacters() throws IOException {
        // Create bundle with special characters in display name and description
        Bundle bundle = bundleInitializer.initializeBundle(
            testLake,
            "special-bundle",
            "Special & Bundle \"Test\"",
            "Description with 'quotes' and special chars: <>&",
            null,
            null
        );
        
        Path bundlePath = BundleUtil.calculateBundlePath(testLake, bundle, basePath.toString());
        
        // Verify YAML escaping works correctly
        String bundleYaml = readFile(bundlePath.resolve("bundle.yaml"));
        assertThat(bundleYaml).contains("Special & Bundle \"Test\"");
        assertThat(bundleYaml).contains("Description with 'quotes' and special chars: <>&");
    }
    
    @Test
    void testInitializeMultipleBundlesInSameLake() throws IOException {
        // Create first bundle
        Bundle bundle1 = bundleInitializer.initializeBundle(
            testLake,
            "service-a",
            "Service A",
            "First service",
            "com.company",
            null
        );
        
        // Create second bundle
        Bundle bundle2 = bundleInitializer.initializeBundle(
            testLake,
            "service-b",
            "Service B", 
            "Second service",
            "com.company",
            null
        );
        
        // Verify both exist
        assertDirectoryExists(lakePath.resolve("com/company/service-a"));
        assertDirectoryExists(lakePath.resolve("com/company/service-b"));
        
        // Verify they have different content
        assertFileContains(
            lakePath.resolve("com/company/service-a/example.proto"),
            "package com.company.service_a;"
        );
        assertFileContains(
            lakePath.resolve("com/company/service-b/example.proto"),
            "package com.company.service_b;"
        );
    }
    
    // Helper method to create a test lake
    private Lake createTestLake(String name) {
        return Lake.newBuilder()
            .setName(LakeUtil.toResourceName(name))
            .setDisplayName("Test Lake")
            .setDescription("Lake for testing")
            .setConfig(LakeConfig.newBuilder()
                .setOrganization("test-org")
                .build())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
}
