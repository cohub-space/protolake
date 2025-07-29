package io.vdp.protolake.storage;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.validator.BundleValidator;
import io.vdp.protolake.validator.LakeValidator;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import protolake.v1.Artifact;
import protolake.v1.Bundle;
import protolake.v1.Lake;
import protolake.v1.Language;
import protolake.v1.MavenCoordinates;
import protolake.v1.TargetBuildInfo;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Unit tests for SqliteStorageService.
 * 
 * Tests all CRUD operations, validation, and edge cases for:
 * - Lake management
 * - Bundle management  
 * - Build tracking
 * - Database integrity
 */
@QuarkusTest
public class SqliteStorageServiceTest extends StorageTestBase {
    
    @Inject
    SqliteStorageService storageService;
    
    @Inject
    BundleDiscoveryService bundleDiscoveryService;
    
    @Inject
    LakeValidator lakeValidator;
    
    @Inject
    BundleValidator bundleValidator;
    
    @BeforeEach
    void setup() throws Exception {
        super.setupBase();
        
        // The storage service should already be initialized by Quarkus with injected config
        // We just need to ensure it's using our test database path
        // First, delete any existing database to ensure clean state
        if (Files.exists(testDbPath)) {
            Files.delete(testDbPath);
        }
        
        // IMPORTANT: Set configuration values BEFORE calling init()
        // The test configuration sets max-builds-per-bundle=5
        storageService.maxBuildsPerBundle = 5;
        storageService.dbPath = testDbPath.toString();
        
        // Now initialize with our test configuration
        storageService.init();
    }
    
    // ===== Lake Operations Tests =====
    
    @Test
    void testCreateLake() throws Exception {
        Lake lake = createTestLake("test_lake", "Test Lake", "A test lake");
        
        Lake created = storageService.createLake(lake);
        
        assertThat(created).isEqualTo(lake);
        
        // Verify it was persisted
        Optional<Lake> retrieved = storageService.getLake("test_lake");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(lake);
    }
    
    @Test
    void testCreateLakeWithPrefix() throws Exception {
        Lake lake = createTestLakeWithPrefix("team_alpha", "org/teams");
        
        Lake created = storageService.createLake(lake);
        
        assertThat(created.getLakePrefix()).isEqualTo("org/teams");
        
        Optional<Lake> retrieved = storageService.getLake("team_alpha");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getLakePrefix()).isEqualTo("org/teams");
    }
    
    @Test
    void testCreateDuplicateLake() throws Exception {
        Lake lake = createTestLake("duplicate", "Duplicate Lake", "First lake");
        storageService.createLake(lake);
        
        Lake duplicate = createTestLake("duplicate", "Duplicate Lake", "Second lake");
        
        assertThatThrownBy(() -> storageService.createLake(duplicate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lake already exists");
    }
    
    @Test
    void testUpdateLake() throws Exception {
        Lake lake = createTestLake("update_lake", "Original", "Original description");
        storageService.createLake(lake);
        
        // Update the lake
        Lake updated = lake.toBuilder()
            .setDisplayName("Updated")
            .setDescription("Updated description")
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
        
        Lake result = storageService.updateLake(updated);
        
        assertThat(result.getDisplayName()).isEqualTo("Updated");
        assertThat(result.getDescription()).isEqualTo("Updated description");
        
        // Verify persistence
        Optional<Lake> retrieved = storageService.getLake("update_lake");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getDisplayName()).isEqualTo("Updated");
    }
    
    @Test
    void testUpdateNonExistentLake() {
        Lake lake = createTestLake("missing", "Missing", "Does not exist");
        
        assertThatThrownBy(() -> storageService.updateLake(lake))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Lake not found");
    }
    
    @Test
    void testDeleteLake() throws Exception {
        Lake lake = createTestLake("delete_me", "Delete Me", "To be deleted");
        storageService.createLake(lake);
        
        // Verify it exists
        assertThat(storageService.getLake("delete_me")).isPresent();
        
        // Delete it
        boolean deleted = storageService.deleteLake("delete_me");
        assertThat(deleted).isTrue();
        
        // Verify it's gone
        assertThat(storageService.getLake("delete_me")).isEmpty();
        
        // Delete again should return false
        assertThat(storageService.deleteLake("delete_me")).isFalse();
    }
    
    @Test
    void testListLakes() throws Exception {
        // Create multiple lakes
        storageService.createLake(createTestLake("lake1", "Lake 1", "First"));
        storageService.createLake(createTestLake("lake2", "Lake 2", "Second"));
        storageService.createLake(createTestLakeWithPrefix("lake3", "prefix"));
        
        List<Lake> lakes = storageService.listLakes();
        
        assertThat(lakes).hasSize(3);
        assertThat(lakes).extracting(lake -> LakeUtil.extractLakeId(lake.getName()))
            .containsExactlyInAnyOrder("lake1", "lake2", "lake3");
    }
    
    // ===== Bundle Operations Tests =====
    
    @Test
    void testCreateBundle() throws Exception {
        // First create a lake
        Lake lake = createTestLake("bundle_lake", "Bundle Lake", "Lake for bundles");
        storageService.createLake(lake);
        
        // Create a bundle
        Bundle bundle = createTestBundle("bundle_lake", "user_service", "com.company.protos");
        Bundle created = storageService.createBundle(bundle);
        
        assertThat(created).isEqualTo(bundle);
        
        // Verify it was persisted
        Optional<Bundle> retrieved = storageService.getBundle("bundle_lake", "user_service");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(bundle);
    }
    
    @Test
    void testCreateBundleWithoutLake() {
        Bundle bundle = createTestBundle("missing_lake", "bundle", "com.test");
        
        // Should fail validation because lake doesn't exist
        assertThatThrownBy(() -> storageService.createBundle(bundle))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Parent lake does not exist");
    }
    
    @Test
    void testUpdateBundle() throws Exception {
        // Setup
        Lake lake = createTestLake("update_bundle_lake", "Lake", "Lake");
        storageService.createLake(lake);
        
        Bundle bundle = createTestBundle("update_bundle_lake", "bundle", "com.test");
        storageService.createBundle(bundle);
        
        // Update
        Bundle updated = bundle.toBuilder()
            .setVersion("2.0.0")
            .setDescription("Updated bundle")
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
        
        Bundle result = storageService.updateBundle(updated);
        
        assertThat(result.getVersion()).isEqualTo("2.0.0");
        assertThat(result.getDescription()).isEqualTo("Updated bundle");
    }
    
    @Test
    void testDeleteBundle() throws Exception {
        // Setup
        Lake lake = createTestLake("delete_bundle_lake", "Lake", "Lake");
        storageService.createLake(lake);
        
        Bundle bundle = createTestBundle("delete_bundle_lake", "delete_me", "com.test");
        storageService.createBundle(bundle);
        
        // Delete
        boolean deleted = storageService.deleteBundle("delete_bundle_lake", "delete_me");
        assertThat(deleted).isTrue();
        
        // Verify it's gone
        assertThat(storageService.getBundle("delete_bundle_lake", "delete_me")).isEmpty();
    }
    
    @Test
    void testListBundles() throws Exception {
        // Setup lake
        Lake lake = createTestLake("list_bundles_lake", "Lake", "Lake");
        storageService.createLake(lake);
        
        // Create multiple bundles
        storageService.createBundle(createTestBundle("list_bundles_lake", "bundle1", "com.test"));
        storageService.createBundle(createTestBundle("list_bundles_lake", "bundle2", "com.test.sub"));
        storageService.createBundle(createTestBundle("list_bundles_lake", "bundle3", ""));
        
        List<Bundle> bundles = storageService.listBundles("list_bundles_lake");
        
        assertThat(bundles).hasSize(3);
        assertThat(bundles).extracting(b -> BundleUtil.extractBundleId(b.getName()))
            .containsExactlyInAnyOrder("bundle1", "bundle2", "bundle3");
    }
    
    @Test
    void testCascadeDeleteBundles() throws Exception {
        // Create lake with bundles
        Lake lake = createTestLake("cascade_lake", "Cascade Lake", "Test cascade");
        storageService.createLake(lake);
        
        storageService.createBundle(createTestBundle("cascade_lake", "bundle1", "com.test"));
        storageService.createBundle(createTestBundle("cascade_lake", "bundle2", "com.test"));
        
        // Delete lake should cascade delete bundles
        storageService.deleteLake("cascade_lake");
        
        // Verify bundles are gone
        assertThat(storageService.listBundles("cascade_lake")).isEmpty();
    }
    
    // ===== Build Operations Tests =====
    
    @Test
    void testRecordTargetBuild() throws Exception {
        // Setup
        Lake lake = createTestLake("build_lake", "Build Lake", "Lake for builds");
        storageService.createLake(lake);
        
        Bundle bundle = createTestBundle("build_lake", "build_bundle", "com.test");
        storageService.createBundle(bundle);
        
        // Create a build
        TargetBuildInfo buildInfo = createTestBuildInfo("1.0.0-main", TargetBuildInfo.Status.BUILT);
        
        TargetBuildInfo recorded = storageService.recordTargetBuild(
            "build_lake", "build_bundle", "main", buildInfo);
        
        assertThat(recorded).isEqualTo(buildInfo);
        
        // Verify it was stored
        Optional<TargetBuildInfo> latest = storageService.getLatestTargetBuild(
            "build_lake", "build_bundle", "main", null);
        
        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo("1.0.0-main");
    }
    
    @Test
    void testGetLatestBuildByBranch() throws Exception {
        // Setup
        setupLakeAndBundle("branch_lake", "branch_bundle");
        
        // Record builds on different branches
        storageService.recordTargetBuild("branch_lake", "branch_bundle", "main",
            createTestBuildInfo("1.0.0-main", TargetBuildInfo.Status.BUILT));
        
        storageService.recordTargetBuild("branch_lake", "branch_bundle", "feature",
            createTestBuildInfo("1.0.0-feature", TargetBuildInfo.Status.BUILT));
        
        // Get latest for each branch
        Optional<TargetBuildInfo> mainBuild = storageService.getLatestTargetBuild(
            "branch_lake", "branch_bundle", "main", null);
        Optional<TargetBuildInfo> featureBuild = storageService.getLatestTargetBuild(
            "branch_lake", "branch_bundle", "feature", null);
        
        assertThat(mainBuild).isPresent();
        assertThat(mainBuild.get().getVersion()).isEqualTo("1.0.0-main");
        
        assertThat(featureBuild).isPresent();
        assertThat(featureBuild.get().getVersion()).isEqualTo("1.0.0-feature");
    }
    
    @Test
    void testGetLatestBuildByLanguage() throws Exception {
        // Setup
        setupLakeAndBundle("lang_lake", "lang_bundle");
        
        // Record builds for different languages
        TargetBuildInfo javaBuild = createTestBuildInfo("1.0.0", TargetBuildInfo.Status.BUILT)
            .toBuilder()
            .setTarget("//lang_bundle:java_proto_bundle")
            .putArtifacts("JAVA", createJavaArtifact())
            .build();
            
        TargetBuildInfo pythonBuild = createTestBuildInfo("1.0.0", TargetBuildInfo.Status.BUILT)
            .toBuilder()
            .setTarget("//lang_bundle:python_proto_bundle")
            .putArtifacts("PYTHON", createPythonArtifact())
            .build();
        
        storageService.recordTargetBuild("lang_lake", "lang_bundle", "main", javaBuild);
        storageService.recordTargetBuild("lang_lake", "lang_bundle", "main", pythonBuild);
        
        // Query by language
        Optional<TargetBuildInfo> javaResult = storageService.getLatestTargetBuild(
            "lang_lake", "lang_bundle", "main", Language.JAVA);
        
        assertThat(javaResult).isPresent();
        assertThat(javaResult.get().getTarget()).contains("java");
    }
    
    @Test
    void testBuildPruning() throws Exception {
        // Setup
        setupLakeAndBundle("prune_lake", "prune_bundle");
        
        // Record more builds than the limit (configured as 5 in test application.properties)
        for (int i = 1; i <= 10; i++) {
            TargetBuildInfo build = createTestBuildInfo("1.0." + i, TargetBuildInfo.Status.BUILT);
            storageService.recordTargetBuild("prune_lake", "prune_bundle", "main", build);
            Thread.sleep(10); // Ensure different timestamps
        }
        
        // Check that only the latest 5 are kept
        List<TargetBuildInfo> builds = storageService.listBuilds("prune_lake", "prune_bundle");
        
        assertThat(builds).hasSize(5);
        // Should have versions 6-10 (latest 5)
        assertThat(builds).extracting(TargetBuildInfo::getVersion)
            .containsExactly("1.0.10", "1.0.9", "1.0.8", "1.0.7", "1.0.6");
    }
    
    @Test
    void testListBuildsByBranch() throws Exception {
        // Setup
        setupLakeAndBundle("list_branch_lake", "list_branch_bundle");
        
        // Record builds on different branches
        storageService.recordTargetBuild("list_branch_lake", "list_branch_bundle", "main",
            createTestBuildInfo("1.0.0-main", TargetBuildInfo.Status.BUILT));
        storageService.recordTargetBuild("list_branch_lake", "list_branch_bundle", "main",
            createTestBuildInfo("1.0.1-main", TargetBuildInfo.Status.BUILT));
        storageService.recordTargetBuild("list_branch_lake", "list_branch_bundle", "feature",
            createTestBuildInfo("1.0.0-feature", TargetBuildInfo.Status.BUILT));
        
        // List by branch
        List<TargetBuildInfo> mainBuilds = storageService.listBuildsByBranch(
            "list_branch_lake", "list_branch_bundle", "main");
        List<TargetBuildInfo> featureBuilds = storageService.listBuildsByBranch(
            "list_branch_lake", "list_branch_bundle", "feature");
        
        assertThat(mainBuilds).hasSize(2);
        assertThat(featureBuilds).hasSize(1);
    }
    
    // ===== Validation Tests =====
    
    @Test 
    void testLakeValidationFailure() throws Exception {
        // Create a lake with an actually invalid name (with special characters)
        Lake invalidLake = createTestLake("invalid-name!", "Invalid", "Should fail");
        
        assertThatThrownBy(() -> storageService.createLake(invalidLake))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Lake name must contain only alphanumeric characters and underscores");
    }
    
    @Test
    void testBundleValidationFailure() throws Exception {
        // First create a lake so we can test bundle-specific validation
        Lake lake = createTestLake("test_lake", "Test Lake", "For bundle validation");
        storageService.createLake(lake);
        
        // Create a bundle with invalid name (with special characters)
        Bundle invalidBundle = createTestBundle("test_lake", "invalid-bundle!", "com.test");
        
        assertThatThrownBy(() -> storageService.createBundle(invalidBundle))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle name must contain only alphanumeric characters and underscores");
    }
    
    // ===== Edge Cases and Error Handling =====
    
    @Test
    void testDatabaseInitialization() throws Exception {
        // Delete the database file
        Files.deleteIfExists(testDbPath);
        
        // Re-initialize - should create new database
        storageService.init();
        
        // Verify database works
        Lake lake = createTestLake("init_test", "Init Test", "Testing init");
        Lake created = storageService.createLake(lake);
        
        assertThat(created).isNotNull();
        assertThat(storageService.getLake("init_test")).isPresent();
    }
    
    @Test
    void testRemoveStaleBundles() throws Exception {
        // Setup lake with bundles
        Lake lake = createTestLake("stale_lake", "Stale Lake", "Test stale removal");
        storageService.createLake(lake);
        
        // Create bundles
        storageService.createBundle(createTestBundle("stale_lake", "keep_me", "com.test"));
        storageService.createBundle(createTestBundle("stale_lake", "remove_me", "com.test"));
        storageService.createBundle(createTestBundle("stale_lake", "also_remove", "com.test"));
        
        // Remove stale bundles
        storageService.removeStaleBundles("stale_lake", List.of("keep_me"));
        
        // Verify only "keep_me" remains
        List<Bundle> remaining = storageService.listBundles("stale_lake");
        assertThat(remaining).hasSize(1);
        assertThat(BundleUtil.extractBundleId(remaining.get(0).getName())).isEqualTo("keep_me");
    }
    
    // ===== Helper Methods =====
    
    private void setupLakeAndBundle(String lakeName, String bundleName) throws Exception {
        Lake lake = createTestLake(lakeName, lakeName + " Lake", "Test lake");
        storageService.createLake(lake);
        
        Bundle bundle = createTestBundle(lakeName, bundleName, "com.test");
        storageService.createBundle(bundle);
    }
    
    private TargetBuildInfo createTestBuildInfo(String version, TargetBuildInfo.Status status) {
        return TargetBuildInfo.newBuilder()
            .setTarget("//test:target")
            .setVersion(version)
            .setStatus(status)
            .setStartTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setEndTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
    }
    
    private Artifact createJavaArtifact() {
        return Artifact.newBuilder()
            .setMaven(MavenCoordinates.newBuilder()
                .setGroupId("com.test")
                .setArtifactId("test_proto")
                .setVersion("1.0.0")
                .setPackaging("jar")
                .build())
            .setLocalPath("/path/to/artifact.jar")
            .setSha256("abc123")
            .build();
    }
    
    private Artifact createPythonArtifact() {
        return Artifact.newBuilder()
            .setPython(protolake.v1.PythonCoordinates.newBuilder()
                .setPackageName("test_proto")
                .setVersion("1.0.0")
                .build())
            .setLocalPath("/path/to/artifact.whl")
            .setSha256("def456")
            .build();
    }
    
    @Override
    protected SqliteStorageService getStorageService() {
        return storageService;
    }
}
