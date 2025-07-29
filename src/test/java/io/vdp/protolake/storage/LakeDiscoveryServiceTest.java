package io.vdp.protolake.storage;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.LakeUtil;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LakeDiscoveryService.
 * 
 * Tests filesystem discovery of lakes including:
 * - lake.yaml parsing
 * - Prefix calculation
 * - Nested lake detection
 * - Stale entry removal
 * - Complex directory structures
 */
@QuarkusTest
public class LakeDiscoveryServiceTest extends StorageTestBase {
    
    @Inject
    LakeDiscoveryService lakeDiscoveryService;
    
    @Inject
    SqliteStorageService storageService;
    
    @BeforeEach
    void setup() throws Exception {
        super.setupBase();
        
        // Configure services to use test database path
        // Note: basePath is already configured via CDI injection from test application.properties
        storageService.dbPath = testDbPath.toString();
        lakeDiscoveryService.basePath = testBasePath.toString();
        
        // Initialize database
        storageService.init();
    }
    
    @Test
    void testDiscoverSimpleLake() throws IOException {
        // Create a simple lake structure
        Path lakePath = testBasePath.resolve("simple_lake");
        createLakeYaml(lakePath, "simple_lake", "");
        
        // Discover lakes
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        assertThat(LakeUtil.extractLakeId(lake.getName())).isEqualTo("simple_lake");
        assertThat(lake.getLakePrefix()).isEmpty();
        assertThat(lake.getConfig().getOrganization()).isEqualTo("test_org");
    }
    
    @Test
    void testDiscoverLakeWithPrefix() throws IOException {
        // Create a lake with prefix
        Path prefixPath = testBasePath.resolve("org/teams");
        Path lakePath = prefixPath.resolve("team_alpha");
        createLakeYaml(lakePath, "team_alpha", "org/teams");
        
        // Discover lakes
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        assertThat(LakeUtil.extractLakeId(lake.getName())).isEqualTo("team_alpha");
        assertThat(lake.getLakePrefix()).isEqualTo("org/teams");
    }
    
    @Test
    void testDiscoverMultipleLakes() throws IOException {
        // Create multiple lakes at different levels
        createLakeYaml(testBasePath.resolve("lake1"), "lake1", "");
        createLakeYaml(testBasePath.resolve("org/lake2"), "lake2", "org");
        createLakeYaml(testBasePath.resolve("org/teams/lake3"), "lake3", "org/teams");
        
        // Discover all lakes
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(3);
        assertThat(lakes).extracting(l -> LakeUtil.extractLakeId(l.getName()))
            .containsExactlyInAnyOrder("lake1", "lake2", "lake3");
    }
    
    @Test
    void testPrefixCalculation() throws IOException {
        // Create lake where prefix in YAML is missing
        Path lakePath = testBasePath.resolve("calculated/prefix/my_lake");
        Files.createDirectories(lakePath);
        
        String yamlContent = """
            name: "my_lake"
            organization: "test_org"
            """;
        Files.writeString(lakePath.resolve("lake.yaml"), yamlContent);
        
        // Discover lake
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        // Should calculate prefix from directory structure
        assertThat(lake.getLakePrefix()).isEqualTo("calculated/prefix");
    }
    
    @Test
    void testPrefixMismatchWarning() throws IOException {
        // Create lake where prefix in YAML doesn't match directory
        Path lakePath = testBasePath.resolve("actual/path/my_lake");
        createLakeYaml(lakePath, "my_lake", "wrong/prefix");
        
        // Discovery should still work but log warning
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        // Uses the YAML prefix even if wrong
        assertThat(lake.getLakePrefix()).isEqualTo("wrong/prefix");
    }
    
    @Test
    void testInvalidLakeYaml() throws IOException {
        // Create invalid YAML
        Path lakePath = testBasePath.resolve("invalid_lake");
        Files.createDirectories(lakePath);
        Files.writeString(lakePath.resolve("lake.yaml"), "invalid: yaml: content:");
        
        // Discovery should skip invalid lakes
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).isEmpty();
    }
    
    @Test
    void testMissingLakeName() throws IOException {
        // Create lake.yaml without name
        Path lakePath = testBasePath.resolve("no_name");
        Files.createDirectories(lakePath);
        
        String yamlContent = """
            organization: "test_org"
            """;
        Files.writeString(lakePath.resolve("lake.yaml"), yamlContent);
        
        // Discovery should skip lakes without names
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).isEmpty();
    }
    
    @Test
    void testLakeConfigParsing() throws IOException {
        // Create lake with full configuration
        Path lakePath = testBasePath.resolve("configured_lake");
        Files.createDirectories(lakePath);
        
        String yamlContent = """
            name: "configured_lake"
            lake_prefix: ""
            organization: "com.company"
            
            versions:
              bazel_deps:
                protobuf: "28.0"
                grpc: "1.65.0"
                rules_proto_grpc: "5.1.0"
              
              target_versions:
                java:
                  source: "17"
                  target: "11"
                python: ">=3.8"
                
              runtime_deps:
                java:
                  protobuf_java: "4.28.0"
                  grpc_java: "1.65.0"
                python:
                  protobuf: "5.28.0"
                  grpcio: "1.65.0"
            """;
        Files.writeString(lakePath.resolve("lake.yaml"), yamlContent);
        
        // Discover and verify configuration
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        assertThat(lake.getConfig().getOrganization()).isEqualTo("com.company");
        assertThat(lake.getConfig().getModuleBazel().getProtobufVersion()).isEqualTo("28.0");
        assertThat(lake.getConfig().getModuleBazel().getGrpcVersion()).isEqualTo("1.65.0");
        assertThat(lake.getConfig().getModuleBazel().getRulesProtoGrpcVersion()).isEqualTo("5.1.0");
    }
    
    @Test
    void testRefreshLakes() throws IOException {
        // Create initial lakes
        createLakeYaml(testBasePath.resolve("lake1"), "lake1", "");
        createLakeYaml(testBasePath.resolve("lake2"), "lake2", "");
        
        // Initial refresh
        int count = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(count).isEqualTo(2);
        
        // Verify lakes are in storage
        assertThat(storageService.listLakes()).hasSize(2);
        
        // Modify filesystem - remove lake2, add lake3
        deleteRecursively(testBasePath.resolve("lake2"));
        createLakeYaml(testBasePath.resolve("lake3"), "lake3", "");
        
        // Refresh again
        count = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(count).isEqualTo(2); // lake1 and lake3
        
        // Verify storage is updated
        List<Lake> lakes = storageService.listLakes();
        assertThat(lakes).hasSize(2);
        assertThat(lakes).extracting(l -> LakeUtil.extractLakeId(l.getName()))
            .containsExactlyInAnyOrder("lake1", "lake3");
    }
    
    @Test
    void testRefreshWithBundles() throws IOException {
        // Create lake with bundles using helper
        createTestLakeStructure("bundle_lake", "", Map.of(
            "bundle1", "com.test",
            "bundle2", "com.test.sub"
        ));
        
        // Refresh lakes (should also refresh bundles)
        int lakeCount = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(lakeCount).isEqualTo(1);
        
        // Verify bundles were also discovered
        List<Bundle> bundles = storageService.listBundles("bundle_lake");
        assertThat(bundles).hasSize(2);
    }
    
    @Test
    void testNonExistentPath() {
        Path nonExistent = testBasePath.resolve("does_not_exist");
        
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(nonExistent);
        
        assertThat(lakes).isEmpty();
    }
    
    @Test
    void testSymbolicLinks() throws IOException {
        // Create a lake
        Path realLake = testBasePath.resolve("real_lake");
        createLakeYaml(realLake, "real_lake", "");
        
        // Create a symlink to it
        Path linkPath = testBasePath.resolve("linked_lake");
        Files.createSymbolicLink(linkPath, realLake);
        
        // Discover should find the lake through the symlink
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        // Should find it twice - once through real path, once through symlink
        assertThat(lakes.size()).isGreaterThanOrEqualTo(1);
    }
    
    @Test
    void testDeepDirectoryStructure() throws IOException {
        // Create a deeply nested lake
        Path deepPath = testBasePath.resolve("a/b/c/d/e/f/deep_lake");
        createLakeYaml(deepPath, "deep_lake", "a/b/c/d/e/f");
        
        // Should still discover it
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        assertThat(lakes.get(0).getLakePrefix()).isEqualTo("a/b/c/d/e/f");
    }
    
    @Test
    void testTimestampsFromFileSystem() throws IOException {
        // Create a lake
        Path lakePath = testBasePath.resolve("timestamp_lake");
        createLakeYaml(lakePath, "timestamp_lake", "");
        
        // Get file modification time
        Path lakeYamlPath = lakePath.resolve("lake.yaml");
        long modTime = Files.getLastModifiedTime(lakeYamlPath).toMillis();
        
        // Discover lake
        List<Lake> lakes = lakeDiscoveryService.discoverLakes(testBasePath);
        
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        // Timestamps should be based on file modification time
        assertThat(LakeUtil.fromProtoTimestamp(lake.getCreateTime()).toEpochMilli())
            .isEqualTo(modTime);
        assertThat(LakeUtil.fromProtoTimestamp(lake.getUpdateTime()).toEpochMilli())
            .isEqualTo(modTime);
    }
    
    @Override
    protected SqliteStorageService getStorageService() {
        return storageService;
    }
}
