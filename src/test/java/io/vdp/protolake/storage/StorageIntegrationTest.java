package io.vdp.protolake.storage;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Artifact;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.JavaConfig;
import protolake.v1.JavaScriptConfig;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.Language;
import protolake.v1.LanguageConfig;
import protolake.v1.MavenCoordinates;
import protolake.v1.ModuleBazelConfig;
import protolake.v1.TargetBuildInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the complete storage workflow.
 * 
 * Tests end-to-end scenarios including:
 * - Create lake → Discover bundles → Record builds
 * - Filesystem changes → Refresh → Database sync
 * - Multi-lake scenarios
 * - Complex workflows
 */
@QuarkusTest
public class StorageIntegrationTest extends StorageTestBase {
    
    @Inject
    SqliteStorageService storageService;
    
    @Inject
    LakeDiscoveryService lakeDiscoveryService;
    
    @Inject
    BundleDiscoveryService bundleDiscoveryService;
    
    @BeforeEach
    void setup() throws Exception {
        super.setupBase();
        
        // Configure services to use test database path
        // Note: basePath is already configured via CDI injection from test application.properties
        storageService.dbPath = testDbPath.toString();
        lakeDiscoveryService.basePath = testBasePath.toString();
        bundleDiscoveryService.basePath = testBasePath.toString();
        
        // Initialize database
        storageService.init();
    }
    
    @Test
    void testCompleteWorkflow() throws Exception {
        // Step 1: Create lake structure on filesystem
        createTestLakeStructure("workflow_lake", "", Map.of(
            "user_service", "com.company.protos",
            "auth_service", "com.company.protos"
        ));
        
        // Step 2: Refresh lakes (simulating startup)
        int lakeCount = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(lakeCount).isEqualTo(1);
        
        // Step 3: Verify lake and bundles are in storage
        List<Lake> lakes = storageService.listLakes();
        assertThat(lakes).hasSize(1);
        
        Lake lake = lakes.get(0);
        assertThat(LakeUtil.extractLakeId(lake.getName())).isEqualTo("workflow_lake");
        
        List<Bundle> bundles = storageService.listBundles("workflow_lake");
        assertThat(bundles).hasSize(2);
        
        // Step 4: Record builds for bundles
        for (Bundle bundle : bundles) {
            String bundleId = BundleUtil.extractBundleId(bundle.getName());
            
            TargetBuildInfo buildInfo = TargetBuildInfo.newBuilder()
                .setTarget("//" + bundleId + ":java_proto_bundle")
                .setVersion("1.0.0-main")
                .setStatus(TargetBuildInfo.Status.BUILT)
                .setStartTime(LakeUtil.toProtoTimestamp(java.time.Instant.now()))
                .setEndTime(LakeUtil.toProtoTimestamp(java.time.Instant.now()))
                .putArtifacts("JAVA", Artifact.newBuilder()
                    .setMaven(MavenCoordinates.newBuilder()
                        .setGroupId("com.company.proto")
                        .setArtifactId(bundleId + "_proto")
                        .setVersion("1.0.0-main")
                        .setPackaging("jar")
                        .build())
                    .setLocalPath("/path/to/" + bundleId + ".jar")
                    .setSha256("abc123")
                    .build())
                .build();
            
            storageService.recordTargetBuild("workflow_lake", bundleId, "main", buildInfo);
        }
        
        // Step 5: Query latest builds
        Optional<TargetBuildInfo> userBuild = storageService.getLatestTargetBuild(
            "workflow_lake", "user_service", "main", Language.JAVA);
        Optional<TargetBuildInfo> authBuild = storageService.getLatestTargetBuild(
            "workflow_lake", "auth_service", "main", Language.JAVA);
        
        assertThat(userBuild).isPresent();
        assertThat(authBuild).isPresent();
        assertThat(userBuild.get().getVersion()).isEqualTo("1.0.0-main");
        assertThat(authBuild.get().getVersion()).isEqualTo("1.0.0-main");
    }
    
    @Test
    void testMultiLakeScenario() throws Exception {
        // Create multiple lakes with different structures
        createTestLakeStructure("core_lake", "", Map.of(
            "common", "com.company.core",
            "types", "com.company.core"
        ));
        
        createTestLakeStructure("team_alpha", "teams", Map.of(
            "user_service", "com.company.alpha",
            "profile_service", "com.company.alpha"
        ));
        
        createTestLakeStructure("team_beta", "teams", Map.of(
            "analytics_service", "com.company.beta"
        ));
        
        // Refresh all lakes
        int lakeCount = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(lakeCount).isEqualTo(3);
        
        // Verify all lakes
        List<Lake> lakes = storageService.listLakes();
        assertThat(lakes).hasSize(3);
        assertThat(lakes).extracting(l -> LakeUtil.extractLakeId(l.getName()))
            .containsExactlyInAnyOrder("core_lake", "team_alpha", "team_beta");
        
        // Verify bundles per lake
        assertThat(storageService.listBundles("core_lake")).hasSize(2);
        assertThat(storageService.listBundles("team_alpha")).hasSize(2);
        assertThat(storageService.listBundles("team_beta")).hasSize(1);
        
        // Verify lake prefixes
        Lake teamAlpha = lakes.stream()
            .filter(l -> LakeUtil.extractLakeId(l.getName()).equals("team_alpha"))
            .findFirst().orElseThrow();
        assertThat(teamAlpha.getLakePrefix()).isEqualTo("teams");
    }
    
    @Test
    void testFilesystemSync() throws Exception {
        // Initial state: one lake with one bundle
        createTestLakeStructure("sync_lake", "", Map.of(
            "initial_bundle", "com.test"
        ));
        
        // Initial refresh
        lakeDiscoveryService.refreshLakes(testBasePath);
        
        assertThat(storageService.listLakes()).hasSize(1);
        assertThat(storageService.listBundles("sync_lake")).hasSize(1);
        
        // Add a new bundle to filesystem
        Path lakePath = testBasePath.resolve("sync_lake");
        createBundleYaml(
            lakePath.resolve("com/test/new_bundle"), 
            "new_bundle", 
            "com.test"
        );
        
        // Refresh bundles only
        Lake lake = storageService.getLake("sync_lake").orElseThrow();
        bundleDiscoveryService.refreshBundlesForLake(lake);
        
        // Verify new bundle is discovered
        List<Bundle> bundles = storageService.listBundles("sync_lake");
        assertThat(bundles).hasSize(2);
        assertThat(bundles).extracting(b -> BundleUtil.extractBundleId(b.getName()))
            .containsExactlyInAnyOrder("initial_bundle", "new_bundle");
        
        // Remove initial bundle from filesystem
        deleteRecursively(lakePath.resolve("com/test/initial_bundle"));
        
        // Refresh again
        bundleDiscoveryService.refreshBundlesForLake(lake);
        
        // Verify only new bundle remains
        bundles = storageService.listBundles("sync_lake");
        assertThat(bundles).hasSize(1);
        assertThat(BundleUtil.extractBundleId(bundles.get(0).getName())).isEqualTo("new_bundle");
    }
    
    @Test
    void testBranchWorkflow() throws Exception {
        // Setup lake and bundle
        createTestLakeStructure("branch_lake", "", Map.of(
            "service", "com.test"
        ));
        lakeDiscoveryService.refreshLakes(testBasePath);
        
        // Simulate builds on different branches
        String[] branches = {"main", "feature_x", "bugfix_123"};
        
        for (String branch : branches) {
            for (int i = 1; i <= 3; i++) {
                TargetBuildInfo buildInfo = TargetBuildInfo.newBuilder()
                    .setTarget("//service:java_proto_bundle")
                    .setVersion("1.0." + i + "-" + branch)
                    .setStatus(TargetBuildInfo.Status.BUILT)
                    .setStartTime(LakeUtil.toProtoTimestamp(java.time.Instant.now()))
                    .setEndTime(LakeUtil.toProtoTimestamp(java.time.Instant.now()))
                    .build();
                
                storageService.recordTargetBuild("branch_lake", "service", branch, buildInfo);
                Thread.sleep(10); // Ensure different timestamps
            }
        }
        
        // Verify latest build per branch
        for (String branch : branches) {
            Optional<TargetBuildInfo> latest = storageService.getLatestTargetBuild(
                "branch_lake", "service", branch, null);
            
            assertThat(latest).isPresent();
            assertThat(latest.get().getVersion()).isEqualTo("1.0.3-" + branch);
        }
        
        // Verify build history per branch
        List<TargetBuildInfo> mainBuilds = storageService.listBuildsByBranch(
            "branch_lake", "service", "main");
        assertThat(mainBuilds).hasSize(3);
        assertThat(mainBuilds.get(0).getVersion()).isEqualTo("1.0.3-main"); // Latest first
    }
    
    @Test
    void testConfigurationInheritance() throws Exception {
        // Create lake with specific configuration
        Path lakePath = testBasePath.resolve("config_lake");
        Files.createDirectories(lakePath);
        
        String lakeYaml = """
            name: "config_lake"
            organization: "com.company"
            
            versions:
              bazel_deps:
                protobuf: "28.0"
                grpc: "1.65.0"
            
            language_defaults:
              java:
                source_version: "17"
                target_version: "11"
            """;
        Files.writeString(lakePath.resolve("lake.yaml"), lakeYaml);
        
        // Create bundle with override
        Path bundlePath = lakePath.resolve("special_bundle");
        Files.createDirectories(bundlePath);  // Create the directory first!
        
        // Add a proto file to make it a valid bundle
        createProtoFile(bundlePath.resolve("v1"), "special.proto", "special_bundle.v1");
        
        String bundleYaml = """
            bundle:
              name: "special_bundle"
              bundle_prefix: ""  # Bundle is directly under lake root
              version: "2.0.0"
              display_name: "Special Bundle"
              description: "Test bundle with config override"
            
            config:
              languages:
                java:
                  enabled: true
                  group_id: "com.special"
                  artifact_id: "special_proto"
                  source_version: "21"  # Override lake's Java 17
            """;
        Files.writeString(bundlePath.resolve("bundle.yaml"), bundleYaml);
        
        // Refresh and verify
        int lakeCount = lakeDiscoveryService.refreshLakes(testBasePath);
        assertThat(lakeCount).isEqualTo(1);
        
        Lake lake = storageService.getLake("config_lake").orElseThrow();
        assertThat(lake.getConfig().getOrganization()).isEqualTo("com.company");
        assertThat(lake.getConfig().getModuleBazel().getProtobufVersion()).isEqualTo("28.0");
        
        List<Bundle> bundles = storageService.listBundles("config_lake");
        assertThat(bundles).hasSize(1);
        
        Bundle bundle = bundles.get(0);
        assertThat(bundle.getConfig().getLanguages().getJava().getSourceVersion()).isEqualTo("21");
        assertThat(bundle.getConfig().getLanguages().getJava().getGroupId()).isEqualTo("com.special");
        assertThat(bundle.getConfig().getLanguages().getJava().getArtifactId()).isEqualTo("special_proto");
        assertThat(bundle.getBundlePrefix()).isEqualTo("");  // Verify empty prefix
    }
    
    @Test
    void testLargeScaleScenario() throws Exception {
        // Create a larger structure to test performance
        int numLakes = 5;
        int bundlesPerLake = 10;
        
        for (int i = 1; i <= numLakes; i++) {
            Map<String, String> bundles = new java.util.HashMap<>();
            for (int j = 1; j <= bundlesPerLake; j++) {
                bundles.put("bundle_" + j, "com.lake" + i);
            }
            createTestLakeStructure("lake_" + i, "", bundles);
        }
        
        // Time the refresh operation
        long startTime = System.currentTimeMillis();
        int lakeCount = lakeDiscoveryService.refreshLakes(testBasePath);
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertThat(lakeCount).isEqualTo(numLakes);
        
        // Verify all data
        assertThat(storageService.listLakes()).hasSize(numLakes);
        
        for (int i = 1; i <= numLakes; i++) {
            List<Bundle> bundles = storageService.listBundles("lake_" + i);
            assertThat(bundles).hasSize(bundlesPerLake);
        }
        
        // Log performance
        System.out.printf("Refreshed %d lakes with %d bundles each in %d ms%n", 
            numLakes, bundlesPerLake, elapsed);
    }
    
    @Test
    void testErrorRecovery() throws Exception {
        // Create initial valid state
        createTestLakeStructure("recovery_lake", "", Map.of(
            "bundle1", "com.test"
        ));
        lakeDiscoveryService.refreshLakes(testBasePath);
        
        // Corrupt a bundle.yaml
        Path bundlePath = testBasePath.resolve("recovery_lake/com/test/bundle1");
        Files.writeString(bundlePath.resolve("bundle.yaml"), "invalid yaml content");
        
        // Add a new valid bundle
        createBundleYaml(
            testBasePath.resolve("recovery_lake/com/test/bundle2"),
            "bundle2",
            "com.test"
        );
        
        // Refresh should skip corrupted bundle but process valid ones
        Lake lake = storageService.getLake("recovery_lake").orElseThrow();
        int count = bundleDiscoveryService.refreshBundlesForLake(lake);
        
        // Should only have the new valid bundle
        assertThat(count).isEqualTo(1);
        
        List<Bundle> bundles = storageService.listBundles("recovery_lake");
        assertThat(bundles).hasSize(1);
        assertThat(BundleUtil.extractBundleId(bundles.get(0).getName())).isEqualTo("bundle2");
    }
    
    @Override
    protected SqliteStorageService getStorageService() {
        return storageService;
    }
}
