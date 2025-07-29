package io.vdp.protolake.storage;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.validator.BundleValidator;
import io.vdp.protolake.validator.LakeValidator;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.BuildDefaults;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.JavaConfig;
import protolake.v1.JavaScriptConfig;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.LanguageConfig;
import protolake.v1.ModuleBazelConfig;
import protolake.v1.PythonConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Lake and Bundle validators.
 * 
 * Tests validation rules including:
 * - Name validation
 * - Path validation
 * - Prefix consistency
 * - Configuration validation
 * - Nested lake detection
 */
@QuarkusTest
public class ValidationTest extends StorageTestBase {
    
    @Inject
    LakeValidator lakeValidator;
    
    @Inject
    BundleValidator bundleValidator;
    
    @Inject
    SqliteStorageService storageService;
    
    @BeforeEach
    void setup() throws Exception {
        super.setupBase();
        
        // Configure storage to use test database path
        // Note: basePath is already configured via CDI injection from test application.properties
        storageService.dbPath = testDbPath.toString();
        
        // Initialize storage
        storageService.init();
        
        // Note: Validators are already configured via CDI injection from test application.properties
        // which sets protolake.storage.base-path=${java.io.tmpdir}/proto-lake-test
    }
    
    // ===== Lake Validation Tests =====
    
    @Test
    void testValidLake() throws Exception {
        Lake lake = createTestLake("valid_lake", "Valid Lake", "A valid lake");
        
        // Should not throw
        lakeValidator.validate(lake);
    }
    
    @Test
    void testLakeNameValidation() {
        // Empty name
        Lake emptyName = Lake.newBuilder()
            .setName("")
            .setConfig(LakeConfig.getDefaultInstance())
            .build();
        
        assertThatThrownBy(() -> lakeValidator.validate(emptyName))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Lake name is required");
        
        // Invalid characters
        Lake invalidName = createTestLake("invalid_name!", "Invalid", "Has special chars");
        
        assertThatThrownBy(() -> lakeValidator.validate(invalidName))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("alphanumeric characters and underscores");
    }
    
    @Test
    void testLakePathValidation() throws Exception {
        // Create a file instead of directory
        Path filePath = testBasePath.resolve("not_a_directory");
        Files.createFile(filePath);
        
        Lake lake = createTestLake("not_a_directory", "Bad Path", "Points to file");
        
        assertThatThrownBy(() -> lakeValidator.validate(lake))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("not a directory");
    }
    
    @Test
    void testNestedLakeDetection() throws Exception {
        // Create parent lake
        Path parentPath = testBasePath.resolve("parent_lake");
        createLakeYaml(parentPath, "parent_lake", "");
        
        // Try to create nested lake
        Lake nestedLake = createTestLake("nested", "Nested Lake", "Should fail")
            .toBuilder()
            .setLakePrefix("parent_lake/sub")
            .build();
        
        assertThatThrownBy(() -> lakeValidator.validate(nestedLake))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Nested lakes are not allowed");
    }
    
    @Test
    void testLakePrefixConsistency() {
        // Lake with prefix should be valid if the prefix is consistent with the expected path
        Lake lake = createTestLake("my_lake", "My Lake", "Test prefix")
            .toBuilder()
            .setLakePrefix("org/division")
            .build();
        
        // The validator will check that the lake path (basePath/org/division/my_lake) is internally consistent
        // This should validate successfully because the configuration is internally consistent
        assertThatCode(() -> lakeValidator.validate(lake))
            .doesNotThrowAnyException();
    }
    
    @Test
    void testLakeConfigValidation() {
        // Missing config
        Lake noConfig = Lake.newBuilder()
            .setName(LakeUtil.toResourceName("no_config"))
            .build();
        
        assertThatThrownBy(() -> lakeValidator.validate(noConfig))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Lake configuration is required");
        
        // Invalid version format
        Lake badVersion = createTestLake("bad_version", "Bad Version", "Invalid version")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setBuildDefaults(BuildDefaults.newBuilder()
                    .setBaseVersion("not-a-version")
                    .build())
                .build())
            .build();
        
        assertThatThrownBy(() -> lakeValidator.validate(badVersion))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid base version format");
        
        // Missing required protobuf version
        Lake noProtobuf = createTestLake("no_protobuf", "No Protobuf", "Missing version")
            .toBuilder()
            .setConfig(LakeConfig.newBuilder()
                .setModuleBazel(ModuleBazelConfig.newBuilder()
                    .setGrpcVersion("1.64.0")
                    .build())
                .build())
            .build();
        
        assertThatThrownBy(() -> lakeValidator.validate(noProtobuf))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Protobuf version is required");
    }
    
    // ===== Bundle Validation Tests =====
    
    @Test
    void testValidBundle() throws Exception {
        // Create parent lake first
        Lake lake = createTestLake("bundle_lake", "Bundle Lake", "Parent lake");
        storageService.createLake(lake);
        
        Bundle bundle = createTestBundle("bundle_lake", "valid_bundle", "com.test");
        
        // Should not throw
        bundleValidator.validate(bundle);
    }
    
    @Test
    void testBundleNameValidation() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("name_lake", "Name Lake", "Parent");
        storageService.createLake(lake);
        
        // Empty name - trailing slash
        Bundle emptyName = Bundle.newBuilder()
            .setName("lakes/name_lake/bundles/")
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(emptyName))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle name is required");
        
        // Empty name - no trailing slash
        Bundle emptyName2 = Bundle.newBuilder()
            .setName("lakes/name_lake/bundles")
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(emptyName2))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle name is required");
        
        // Malformed resource name
        Bundle malformed = Bundle.newBuilder()
            .setName("invalid_format")
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(malformed))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle name is required");
        
        // Invalid characters
        Bundle invalidName = createTestBundle("name_lake", "invalid_bundle!", "com.test");
        
        assertThatThrownBy(() -> bundleValidator.validate(invalidName))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("alphanumeric characters and underscores");
    }
    
    @Test
    void testBundleParentLakeValidation() {
        // Bundle without parent lake
        Bundle orphanBundle = createTestBundle("missing_lake", "orphan", "com.test");
        
        assertThatThrownBy(() -> bundleValidator.validate(orphanBundle))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Parent lake does not exist");
    }
    
    @Test
    void testBundlePackagePrefixValidation() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("prefix_lake", "Prefix Lake", "Parent");
        storageService.createLake(lake);
        
        // Invalid package format
        Bundle badPackage = createTestBundle("prefix_lake", "bad_pkg", "com.123.invalid");
        
        assertThatThrownBy(() -> bundleValidator.validate(badPackage))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid proto package format");
        
        // Package starting with number
        Bundle numPackage = createTestBundle("prefix_lake", "num_pkg", "123.com.test");
        
        assertThatThrownBy(() -> bundleValidator.validate(numPackage))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid proto package format");
    }
    
    @Test
    void testBundleVersionValidation() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("version_lake", "Version Lake", "Parent");
        storageService.createLake(lake);
        
        // Invalid version format
        Bundle badVersion = createTestBundle("version_lake", "bad_ver", "com.test")
            .toBuilder()
            .setVersion("v1.0")
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(badVersion))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid version format");
    }
    
    @Test
    void testBundlePrefixConsistency() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("consist_lake", "Consistency Lake", "Parent");
        storageService.createLake(lake);
        
        // Bundle with prefix should be valid if the prefix is consistent with the expected path
        Bundle bundle = createTestBundle("consist_lake", "my_bundle", "com.test.bundle");
        
        // The validator will check that the bundle path is internally consistent
        // This should validate successfully because the configuration is internally consistent
        assertThatCode(() -> bundleValidator.validate(bundle))
            .doesNotThrowAnyException();
    }
    
    @Test
    void testBundleLanguageConfigValidation() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("lang_lake", "Language Lake", "Parent");
        storageService.createLake(lake);
        
        // Java enabled but missing required fields
        Bundle badJava = createTestBundle("lang_lake", "bad_java", "com.test")
            .toBuilder()
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJava(JavaConfig.newBuilder()
                        .setEnabled(true)
                        .setGroupId("")  // Empty group ID
                        .build())
                    .build())
                .build())
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(badJava))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Java group ID is required");
        
        // Python enabled but missing package name
        Bundle badPython = createTestBundle("lang_lake", "bad_python", "com.test")
            .toBuilder()
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setPython(PythonConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("")  // Empty package name
                        .build())
                    .build())
                .build())
            .build();
        
        assertThatThrownBy(() -> bundleValidator.validate(badPython))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Python package name is required");
    }
    
    @Test
    void testBundlePathWithinLake() throws Exception {
        // Setup parent lake
        Lake lake = createTestLake("path_lake", "Path Lake", "Parent");
        storageService.createLake(lake);
        
        // Bundle with prefix that would place it outside the lake
        Bundle outsideBundle = createTestBundle("path_lake", "outside", "../../../evil");
        
        assertThatThrownBy(() -> bundleValidator.validate(outsideBundle))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle path must be within lake");
    }
    
    @Test
    void testOptionalBundlePrefix() throws Exception {
        // Bundle prefix is optional - bundle can be directly under lake
        Lake lake = createTestLake("opt_lake", "Optional Lake", "Parent");
        storageService.createLake(lake);
        
        Bundle noPrefix = createTestBundle("opt_lake", "no_prefix", "");
        
        // Should not throw
        bundleValidator.validate(noPrefix);
    }
    
    @Test
    void testComplexValidationScenario() throws Exception {
        // Create a lake with specific configuration
        Lake lake = Lake.newBuilder()
            .setName(LakeUtil.toResourceName("complex_lake"))
            .setDisplayName("Complex Lake")
            .setDescription("Lake for complex validation")
            .setLakePrefix("org/division")
            .setConfig(LakeConfig.newBuilder()
                .setOrganization("com.company")
                .setModuleBazel(ModuleBazelConfig.newBuilder()
                    .setProtobufVersion("27.0")
                    .setGrpcVersion("1.64.0")
                    .setRulesProtoGrpcVersion("5.0.0")
                    .build())
                .setBuildDefaults(BuildDefaults.newBuilder()
                    .setAutoPublishLocal(true)
                    .setBaseVersion("1.0.0")
                    .build())
                .build())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
        
        // Create the expected directory structure
        Path lakePath = testBasePath.resolve("org/division/complex_lake");
        Files.createDirectories(lakePath);
        
        // Should validate successfully
        lakeValidator.validate(lake);
        
        // Create and validate a bundle in this lake
        storageService.createLake(lake);
        
        Bundle bundle = Bundle.newBuilder()
            .setName(BundleUtil.toResourceName("complex_lake", "service"))
            .setDisplayName("Complex Service")
            .setDescription("A complex service bundle")
            .setBundlePrefix("com.company.complex")
            .setVersion("2.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJava(JavaConfig.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.company.complex")
                        .setArtifactId("service_proto")
                        .setSourceVersion("17")
                        .setTargetVersion("11")
                        .build())
                    .setPython(PythonConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("company_complex_service_proto")
                        .setPythonVersion(">=3.8,<4.0")
                        .build())
                    .setJavascript(JavaScriptConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("@company/complex_service_proto")
                        .setUseTypescript(true)
                        .build())
                    .build())
                .build())
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .build();
        
        // Create expected bundle directory
        Path bundlePath = lakePath.resolve("com/company/complex/service");
        Files.createDirectories(bundlePath);
        
        // Should validate successfully
        bundleValidator.validate(bundle);
    }
    
    @Override
    protected SqliteStorageService getStorageService() {
        return storageService;
    }
}
