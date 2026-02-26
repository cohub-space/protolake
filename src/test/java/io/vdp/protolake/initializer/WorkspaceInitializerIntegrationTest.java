package io.vdp.protolake.initializer;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.template.TemplateEngine;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.ModuleBazelConfig;
import protolake.v1.RemoteCacheConfig;
import protolake.v1.JavaDefaults;
import protolake.v1.PythonDefaults;
import protolake.v1.LanguageDefaults;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WorkspaceInitializer.
 * 
 * Tests the Bazel workspace initialization including:
 * - MODULE.bazel generation with correct versions
 * - Tool script generation and permissions
 * - BUILD file creation
 * - Buf configuration
 */
@QuarkusTest
public class WorkspaceInitializerIntegrationTest extends InitializerTestBase {
    
    @Inject
    WorkspaceInitializer workspaceInitializer;
    
    @Inject
    TemplateEngine templateEngine;
    
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
    void testInitializeWorkspaceWithDefaults() throws IOException {
        // Create a lake with default configuration
        Lake lake = createTestLake("default-lake");
        Path lakePath = basePath.resolve("default-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Verify MODULE.bazel was created
        assertFileExists(lakePath, "MODULE.bazel");
        assertFileContains(lakePath.resolve("MODULE.bazel"),
            "module(",
            "name = \"proto_lake_default-lake\"",
            "bazel_dep(name = \"protobuf\"",
            "bazel_dep(name = \"rules_proto_grpc\"",
            "io.grpc:grpc-api"
        );
        
        // Verify root BUILD.bazel
        assertFileExists(lakePath, "BUILD.bazel");
        assertFileContains(lakePath.resolve("BUILD.bazel"),
            "load(\"@bazel_gazelle//:def.bzl\", \"gazelle\", \"gazelle_binary\")",
            "gazelle(",
            "name = \"gazelle\""
        );
        
        // Verify .bazelrc
        assertFileExists(lakePath, ".bazelrc");
        assertFileContains(lakePath.resolve(".bazelrc"),
            "common --enable_bzlmod"
        );
    }
    
    @Test
    void testInitializeWorkspaceWithCustomVersions() throws IOException {
        // Create lake with custom versions
        Lake lake = createTestLake("versioned-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setModuleBazel(ModuleBazelConfig.newBuilder()
                    .setProtobufVersion("28.3")
                    .setGrpcVersion("1.65.0")
                    .setRulesProtoGrpcVersion("5.1.0")
                    .build())
                .build())
            .build();
            
        Path lakePath = basePath.resolve("versioned-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Verify MODULE.bazel contains custom versions
        String moduleBazel = readFile(lakePath.resolve("MODULE.bazel"));
        
        // Check for version strings in the content
        assertThat(moduleBazel).contains("28.3");  // protobuf version
        assertThat(moduleBazel).contains("1.65.0"); // grpc version  
        assertThat(moduleBazel).contains("5.1.0");  // rules_proto_grpc version
    }
    
    @Test
    void testInitializeWorkspaceCreatesTools() throws IOException {
        // Create a lake
        Lake lake = createTestLake("tools-lake");
        Path lakePath = basePath.resolve("tools-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        Path toolsPath = lakePath.resolve("tools");
        
        // Verify tools directory structure
        assertDirectoryStructure(toolsPath,
            "bundler",
            "publish"
        );
        
        // Verify bundler tools
        assertExecutableFile(toolsPath.resolve("bundler/jar_bundler_generated.py"));
        assertExecutableFile(toolsPath.resolve("bundler/wheel_builder_generated.py"));
        assertExecutableFile(toolsPath.resolve("bundler/npm_bundler_generated.py"));

        // Verify each bundler has expected content
        assertFileContains(toolsPath.resolve("bundler/jar_bundler_generated.py"),
            "def main():",
            "def create_manifest(",
            "#!/usr/bin/env python3"
        );

        assertFileContains(toolsPath.resolve("bundler/wheel_builder_generated.py"),
            "def main():",
            "def create_setup_py(",
            "#!/usr/bin/env python3"
        );

        // Verify publishing tools
        assertExecutableFile(toolsPath.resolve("publish/maven_publisher_generated.py"));
        assertExecutableFile(toolsPath.resolve("publish/pypi_publisher_generated.py"));
        assertExecutableFile(toolsPath.resolve("publish/npm_publisher_generated.py"));
        assertFileExists(toolsPath, "publish/publisher_utils_generated.py");

        // Verify publisher content
        assertFileContains(toolsPath.resolve("publish/maven_publisher_generated.py"),
            "#!/usr/bin/env python3",
            "Maven publisher for Proto Lake bundles",
            "def main():"
        );
    }
    
    @Test
    void testInitializeWorkspaceCreatesProtoBundleRules() throws IOException {
        // Create a lake
        Lake lake = createTestLake("rules-lake");
        Path lakePath = basePath.resolve("rules-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Verify proto_bundle.bzl was created
        assertFileExists(lakePath.resolve("tools"), "proto_bundle.bzl");
        assertFileContains(lakePath.resolve("tools/proto_bundle.bzl"),
            "java_proto_bundle = rule(",
            "py_proto_bundle = rule(",
            "js_proto_bundle = rule("
        );
        
        // Verify tools BUILD.bazel exports the rules
        assertFileExists(lakePath.resolve("tools"), "BUILD.bazel");
    }
    
    @Test
    void testInitializeWorkspaceCreatesBufConfiguration() throws IOException {
        // Create a lake  
        Lake lake = createTestLake("buf-lake");
        Path lakePath = basePath.resolve("buf-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Verify buf.yaml
        assertFileExists(lakePath, "buf.yaml");
        assertFileContains(lakePath.resolve("buf.yaml"),
            "version: v1",
            "breaking:",
            "use:",
            "- FILE",
            "lint:",
            "use:",
            "- STANDARD",
            "except:",
            "- PACKAGE_VERSION_SUFFIX"
        );
        
        // Verify buf.gen.yaml
        assertFileExists(lakePath, "buf.gen.yaml");
        assertFileContains(lakePath.resolve("buf.gen.yaml"),
            "version: v1",
            "plugins:"
        );
    }
    
    @Test
    void testInitializeWorkspaceCreatesUtilityScripts() throws IOException {
        // Create a lake
        Lake lake = createTestLake("utils-lake");
        Path lakePath = basePath.resolve("utils-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        Path toolsPath = lakePath.resolve("tools");
        
        // Verify gazelle wrapper
        assertExecutableFile(toolsPath.resolve("gazelle_wrapper_generated.py"));
        assertFileContains(toolsPath.resolve("gazelle_wrapper_generated.py"),
            "#!/usr/bin/env python3",
            "Wrapper script that runs Gazelle in two passes"
        );
        
    }
    
    @Test
    void testInitializeWorkspaceWithLanguageDefaults() throws IOException {
        // Create lake with language defaults
        Lake lake = createTestLake("lang-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJava(JavaDefaults.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.mycompany.proto")
                        .setSourceVersion("17")
                        .setTargetVersion("11")
                        .build())
                    .setPython(PythonDefaults.newBuilder()
                        .setEnabled(true)
                        .setPackagePrefix("mycompany_proto")
                        .setPythonVersion(">=3.9,<4.0")
                        .build())
                    .build())
                .build())
            .build();
            
        Path lakePath = basePath.resolve("lang-lake");
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Language defaults don't directly affect workspace files,
        // but we verify the workspace was created successfully
        assertFileExists(lakePath, "MODULE.bazel");
        assertDirectoryExists(lakePath.resolve("tools"));
    }
    
    @Test
    void testTemplateContextPropagation() throws IOException {
        // Create lake with specific configuration
        Lake lake = createTestLake("context-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setModuleBazel(ModuleBazelConfig.newBuilder()
                    .setProtobufVersion("27.0")
                    .setGrpcVersion("1.64.0")
                    .build())
                .build())
            .build();
            
        Path lakePath = basePath.resolve("context-lake");  
        Files.createDirectories(lakePath);
        
        // Initialize workspace
        workspaceInitializer.initializeWorkspace(lake);
        
        // Verify organization propagated (if used in templates)
        String moduleBazel = readFile(lakePath.resolve("MODULE.bazel"));
        
        // MODULE.bazel should have the lake name
        assertThat(moduleBazel).contains("context-lake");
    }
    
    @Test
    void testInitializeWorkspaceWithGcsRemoteCache() throws IOException {
        Lake lake = createTestLake("gcs-cache-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setRemoteCache(RemoteCacheConfig.newBuilder()
                    .setProvider("gcs")
                    .setBucket("my-project-bazel-cache")
                    .setCompression(true)
                    .build())
                .build())
            .build();

        Path lakePath = basePath.resolve("gcs-cache-lake");
        Files.createDirectories(lakePath);

        workspaceInitializer.initializeWorkspace(lake);

        // Verify .bazelrc has try-import
        assertFileContains(lakePath.resolve(".bazelrc"),
            "try-import %workspace%/.bazelrc.remote-cache");

        // Verify .bazelrc.remote-cache was created with GCS URL and ADC
        assertFileExists(lakePath, ".bazelrc.remote-cache");
        assertFileContains(lakePath.resolve(".bazelrc.remote-cache"),
            "build:ci --remote_cache=https://storage.googleapis.com/my-project-bazel-cache",
            "build:ci --google_default_credentials",
            "build:ci --remote_cache_compression",
            "build:ci --remote_upload_local_results=true"
        );
    }

    @Test
    void testInitializeWorkspaceWithHttpRemoteCache() throws IOException {
        Lake lake = createTestLake("http-cache-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setRemoteCache(RemoteCacheConfig.newBuilder()
                    .setProvider("http")
                    .setBucket("https://cache.example.com")
                    .build())
                .build())
            .build();

        Path lakePath = basePath.resolve("http-cache-lake");
        Files.createDirectories(lakePath);

        workspaceInitializer.initializeWorkspace(lake);

        assertFileExists(lakePath, ".bazelrc.remote-cache");
        assertFileContains(lakePath.resolve(".bazelrc.remote-cache"),
            "build:ci --remote_cache=https://cache.example.com"
        );
        // Compression not set — should not appear; no GCS credentials either
        assertFileDoesNotContain(lakePath.resolve(".bazelrc.remote-cache"),
            "remote_cache_compression",
            "google_default_credentials"
        );
    }

    @Test
    void testInitializeWorkspaceWithNoRemoteCache() throws IOException {
        Lake lake = createTestLake("no-cache-lake");
        Path lakePath = basePath.resolve("no-cache-lake");
        Files.createDirectories(lakePath);

        workspaceInitializer.initializeWorkspace(lake);

        // File should still be generated (disabled template)
        assertFileExists(lakePath, ".bazelrc.remote-cache");
        assertFileDoesNotContain(lakePath.resolve(".bazelrc.remote-cache"),
            "--remote_cache="
        );
        assertFileContains(lakePath.resolve(".bazelrc.remote-cache"),
            "Remote cache is not configured"
        );
    }

    @Test
    void testInitializeWorkspaceWithEmptyBucketRemoteCache() throws IOException {
        Lake lake = createTestLake("empty-bucket-lake")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setRemoteCache(RemoteCacheConfig.newBuilder()
                    .setProvider("gcs")
                    // bucket left empty
                    .build())
                .build())
            .build();

        Path lakePath = basePath.resolve("empty-bucket-lake");
        Files.createDirectories(lakePath);

        workspaceInitializer.initializeWorkspace(lake);

        // Empty bucket should disable cache
        assertFileExists(lakePath, ".bazelrc.remote-cache");
        assertFileDoesNotContain(lakePath.resolve(".bazelrc.remote-cache"),
            "--remote_cache="
        );
    }

    // Helper method to create a test lake
    private Lake createTestLake(String name) {
        return Lake.newBuilder()
            .setName(LakeUtil.toResourceName(name))
            .setDisplayName("Test Lake")
            .setDescription("Lake for testing workspace initialization")
            .setConfig(LakeConfig.getDefaultInstance())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
}
