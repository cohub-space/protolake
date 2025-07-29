package io.vdp.protolake.storage;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.BundleUtil;
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
 * Tests for BundleDiscoveryService.
 * 
 * Tests filesystem discovery of bundles including:
 * - bundle.yaml parsing
 * - Package prefix conversion
 * - Configuration parsing
 * - Bundle refresh and sync
 */
@QuarkusTest
public class BundleDiscoveryServiceTest extends StorageTestBase {
    
    @Inject
    BundleDiscoveryService bundleDiscoveryService;
    
    @Inject
    SqliteStorageService storageService;
    
    @BeforeEach
    void setup() throws Exception {
        super.setupBase();
        
        // Configure services to use test database path
        // Note: basePath is already configured via CDI injection from test application.properties
        storageService.dbPath = testDbPath.toString();
        bundleDiscoveryService.basePath = testBasePath.toString();
        
        // Initialize database
        storageService.init();
    }
    
    @Test
    void testDiscoverSimpleBundle() throws IOException {
        // Create lake structure
        Lake lake = createTestLake("test_lake", "Test Lake", "Lake for bundles");
        Path lakePath = testBasePath.resolve("test_lake");
        createLakeYaml(lakePath, "test_lake", "");
        
        // Create bundle
        Path bundlePath = lakePath.resolve("simple_bundle");
        createBundleYaml(bundlePath, "simple_bundle", "");
        
        // Discover bundles
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        assertThat(BundleUtil.extractBundleId(bundle.getName())).isEqualTo("simple_bundle");
        assertThat(bundle.getBundlePrefix()).isEmpty();
        assertThat(bundle.getVersion()).isEqualTo("1.0.0");
    }
    
    @Test
    void testDiscoverBundleWithPrefix() throws IOException {
        // Create lake
        Lake lake = createTestLake("test_lake", "Test Lake", "Lake for bundles");
        Path lakePath = testBasePath.resolve("test_lake");
        createLakeYaml(lakePath, "test_lake", "");
        
        // Create bundle with package structure
        Path bundlePath = lakePath.resolve("com/company/protos/user_service");
        createBundleYaml(bundlePath, "user_service", "com.company.protos");
        
        // Discover bundles
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        assertThat(BundleUtil.extractBundleId(bundle.getName())).isEqualTo("user_service");
        assertThat(bundle.getBundlePrefix()).isEqualTo("com.company.protos");
    }
    
    @Test
    void testDiscoverMultipleBundles() throws IOException {
        // Create lake with multiple bundles
        Lake lake = createTestLake("multi_lake", "Multi Lake", "Lake with multiple bundles");
        createTestLakeStructure("multi_lake", "", Map.of(
            "bundle1", "com.test",
            "bundle2", "com.test.sub",
            "bundle3", ""
        ));
        
        // Discover bundles
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(3);
        assertThat(bundles).extracting(b -> BundleUtil.extractBundleId(b.getName()))
            .containsExactlyInAnyOrder("bundle1", "bundle2", "bundle3");
    }
    
    @Test
    void testBundlePrefixCalculation() throws IOException {
        // Create bundle where prefix is missing in YAML
        Lake lake = createTestLake("calc_lake", "Calc Lake", "Test prefix calculation");
        Path lakePath = testBasePath.resolve("calc_lake");
        createLakeYaml(lakePath, "calc_lake", "");
        
        Path bundlePath = lakePath.resolve("com/example/proto/my_bundle");
        Files.createDirectories(bundlePath);
        
        String yamlContent = """
            bundle:
              name: "my_bundle"
              version: "1.0.0"
            """;
        Files.writeString(bundlePath.resolve("bundle.yaml"), yamlContent);
        
        // Discover bundle
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        // Should calculate prefix from directory structure
        assertThat(bundle.getBundlePrefix()).isEqualTo("com.example.proto");
    }
    
    @Test
    void testBundleConfigurationParsing() throws IOException {
        // Create bundle with full configuration
        Lake lake = createTestLake("config_lake", "Config Lake", "Test configuration");
        Path lakePath = testBasePath.resolve("config_lake");
        createLakeYaml(lakePath, "config_lake", "");
        
        Path bundlePath = lakePath.resolve("configured_bundle");
        Files.createDirectories(bundlePath);
        
        String yamlContent = """
            bundle:
              name: "configured_bundle"
              bundle_prefix: "com.test"
              version: "2.0.0"
              display_name: "Configured Bundle"
              description: "A fully configured bundle"
            
            config:
              languages:
                java:
                  enabled: true
                  group_id: "com.company"
                  artifact_id: "configured_proto"
                python:
                  enabled: true
                  package_name: "company_configured_proto"
                javascript:
                  enabled: false
                  package_name: "@company/configured_proto"
            """;
        Files.writeString(bundlePath.resolve("bundle.yaml"), yamlContent);
        
        // Discover and verify
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        assertThat(bundle.getVersion()).isEqualTo("2.0.0");
        assertThat(bundle.getDisplayName()).isEqualTo("Configured Bundle");
        assertThat(bundle.getDescription()).isEqualTo("A fully configured bundle");
        
        // Check language configs
        assertThat(bundle.getConfig().getLanguages().getJava().getEnabled()).isTrue();
        assertThat(bundle.getConfig().getLanguages().getJava().getGroupId()).isEqualTo("com.company");
        assertThat(bundle.getConfig().getLanguages().getJava().getArtifactId()).isEqualTo("configured_proto");
        
        assertThat(bundle.getConfig().getLanguages().getPython().getEnabled()).isTrue();
        assertThat(bundle.getConfig().getLanguages().getPython().getPackageName()).isEqualTo("company_configured_proto");
        
        assertThat(bundle.getConfig().getLanguages().getJavascript().getEnabled()).isFalse();
    }
    
    @Test
    void testDiscoverWithoutLakeContext() throws IOException {
        // Create bundle structure without lake
        Path bundlePath = testBasePath.resolve("standalone/bundle");
        createBundleYaml(bundlePath, "standalone_bundle", "com.standalone");
        
        // Discover from path
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(testBasePath);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        // Without lake context, resource name is just the bundle name
        assertThat(bundle.getName()).isEqualTo("standalone_bundle");
    }
    
    @Test
    void testInvalidBundleYaml() throws IOException {
        Lake lake = createTestLake("invalid_lake", "Invalid Lake", "Test invalid bundle");
        Path lakePath = testBasePath.resolve("invalid_lake");
        createLakeYaml(lakePath, "invalid_lake", "");
        
        // Create invalid YAML
        Path bundlePath = lakePath.resolve("invalid_bundle");
        Files.createDirectories(bundlePath);
        Files.writeString(bundlePath.resolve("bundle.yaml"), "invalid: yaml: content:");
        
        // Discovery should skip invalid bundles
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).isEmpty();
    }
    
    @Test
    void testMissingBundleSection() throws IOException {
        Lake lake = createTestLake("missing_lake", "Missing Lake", "Test missing bundle section");
        Path lakePath = testBasePath.resolve("missing_lake");
        createLakeYaml(lakePath, "missing_lake", "");
        
        // Create YAML without bundle section
        Path bundlePath = lakePath.resolve("no_bundle");
        Files.createDirectories(bundlePath);
        
        String yamlContent = """
            config:
              languages:
                java:
                  enabled: true
            """;
        Files.writeString(bundlePath.resolve("bundle.yaml"), yamlContent);
        
        // Discovery should skip bundles without bundle section
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).isEmpty();
    }
    
    @Test
    void testAlternatePackagePrefixKey() throws IOException {
        // Test that both "bundle_prefix" and "package_prefix" work
        Lake lake = createTestLake("alt_lake", "Alt Lake", "Test alternate keys");
        Path lakePath = testBasePath.resolve("alt_lake");
        createLakeYaml(lakePath, "alt_lake", "");
        
        Path bundlePath = lakePath.resolve("alt_bundle");
        Files.createDirectories(bundlePath);
        
        String yamlContent = """
            bundle:
              name: "alt_bundle"
              package_prefix: "com.alternate"  # Using alternate key
              version: "1.0.0"
            """;
        Files.writeString(bundlePath.resolve("bundle.yaml"), yamlContent);
        
        // Discover bundle
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).getBundlePrefix()).isEqualTo("com.alternate");
    }
    
    @Test
    void testRefreshBundlesForLake() throws Exception {
        // Create lake in storage first
        Lake lake = createTestLake("refresh_lake", "Refresh Lake", "Test bundle refresh");
        storageService.createLake(lake);
        
        // Create initial bundles
        createTestLakeStructure("refresh_lake", "", Map.of(
            "bundle1", "com.test",
            "bundle2", "com.test"
        ));
        
        // Initial refresh
        int count = bundleDiscoveryService.refreshBundlesForLake(lake);
        assertThat(count).isEqualTo(2);
        
        // Verify bundles are in storage
        assertThat(storageService.listBundles("refresh_lake")).hasSize(2);
        
        // Modify filesystem - remove bundle2, add bundle3
        Path lakePath = testBasePath.resolve("refresh_lake");
        deleteRecursively(lakePath.resolve("com/test/bundle2"));
        createBundleYaml(lakePath.resolve("com/test/bundle3"), "bundle3", "com.test");
        
        // Refresh again
        count = bundleDiscoveryService.refreshBundlesForLake(lake);
        assertThat(count).isEqualTo(2); // bundle1 and bundle3
        
        // Verify storage is updated
        List<Bundle> bundles = storageService.listBundles("refresh_lake");
        assertThat(bundles).hasSize(2);
        assertThat(bundles).extracting(b -> BundleUtil.extractBundleId(b.getName()))
            .containsExactlyInAnyOrder("bundle1", "bundle3");
    }
    
    @Test
    void testDeepBundleStructure() throws IOException {
        // Create bundle in deep package structure
        Lake lake = createTestLake("deep_lake", "Deep Lake", "Test deep structure");
        Path lakePath = testBasePath.resolve("deep_lake");
        createLakeYaml(lakePath, "deep_lake", "");
        
        Path bundlePath = lakePath.resolve("com/company/division/team/project/proto/service");
        createBundleYaml(bundlePath, "service", "com.company.division.team.project.proto");
        
        // Discover bundle
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        assertThat(bundle.getBundlePrefix()).isEqualTo("com.company.division.team.project.proto");
        assertThat(BundleUtil.getFullProtoPackage(bundle))
            .isEqualTo("com.company.division.team.project.proto.service");
    }
    
    @Test
    void testTimestampsFromFileSystem() throws IOException {
        Lake lake = createTestLake("timestamp_lake", "Timestamp Lake", "Test timestamps");
        Path lakePath = testBasePath.resolve("timestamp_lake");
        createLakeYaml(lakePath, "timestamp_lake", "");
        
        Path bundlePath = lakePath.resolve("timestamp_bundle");
        createBundleYaml(bundlePath, "timestamp_bundle", "");
        
        // Get file modification time
        Path bundleYamlPath = bundlePath.resolve("bundle.yaml");
        long modTime = Files.getLastModifiedTime(bundleYamlPath).toMillis();
        
        // Discover bundle
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        // Timestamps should be based on file modification time
        assertThat(LakeUtil.fromProtoTimestamp(bundle.getCreateTime()).toEpochMilli())
            .isEqualTo(modTime);
        assertThat(LakeUtil.fromProtoTimestamp(bundle.getUpdateTime()).toEpochMilli())
            .isEqualTo(modTime);
    }
    
    @Test
    void testBundleResourceNameConstruction() throws Exception {
        // Test that bundle resource names are correctly constructed
        Lake lake = createTestLakeWithPrefix("prefixed_lake", "org/division");
        Path lakePath = testBasePath.resolve("org/division/prefixed_lake");
        createLakeYaml(lakePath, "prefixed_lake", "org/division");
        
        Path bundlePath = lakePath.resolve("com/test/my_bundle");
        createBundleYaml(bundlePath, "my_bundle", "com.test");
        
        // Discover bundle
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(lake);
        
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        // Resource name should include lake name
        assertThat(bundle.getName()).isEqualTo("lakes/prefixed_lake/bundles/my_bundle");
    }
    
    @Override
    protected SqliteStorageService getStorageService() {
        return storageService;
    }
}
