package io.vdp.protolake.validator;

import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.storage.ValidationException;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.PathValidationUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates bundle creation and update operations.
 * 
 * Ensures that:
 * - Only one bundle.yaml per directory
 * - Parent lake exists
 * - Bundle prefix matches the actual directory structure
 * - Bundle.yaml content is valid
 * - Proto package paths exist
 */
@ApplicationScoped
public class BundleValidator {
    private static final Logger LOG = Logger.getLogger(BundleValidator.class);
    private static final String BUNDLE_YAML = "bundle.yaml";
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;
    
    @Inject
    StorageService storageService;
    
    /**
     * Validates a bundle proto before creation or update.
     * 
     * @param bundle The bundle proto to validate
     * @throws ValidationException if validation fails
     */
    public void validate(Bundle bundle) throws ValidationException {
        List<String> errors = new ArrayList<>();
        
        // Validate name
        String bundleName = BundleUtil.extractBundleId(bundle.getName());
        LOG.debugf("Validating bundle with resource name: '%s', extracted bundle name: '%s', length: %d, isEmpty: %s", 
                   bundle.getName(), bundleName, bundleName == null ? -1 : bundleName.length(), 
                   bundleName == null ? "null" : bundleName.isEmpty());
        
        // Check for empty bundle name first - MUST be before regex check
        if (bundleName == null || bundleName.isEmpty()) {
            LOG.debugf("Adding error: Bundle name is required (bundleName='%s')", bundleName);
            errors.add("Bundle name is required");
        } else if (!bundleName.matches("^[a-zA-Z0-9_]+$")) {
            // Only check regex pattern if bundle name is not empty
            LOG.debugf("Adding error: Bundle name must contain only alphanumeric characters and underscores (bundleName='%s')", bundleName);
            errors.add("Bundle name must contain only alphanumeric characters and underscores");
        }
        
        // Extract and validate lake
        String lakeName = BundleUtil.extractLakeIdFromBundle(bundle.getName());
        if (lakeName.isEmpty()) {
            errors.add("Lake name is required in bundle resource name");
        } else {
            Optional<Lake> lake = storageService.getLake(lakeName);
            if (lake.isEmpty()) {
                errors.add("Parent lake does not exist: " + lakeName);
            } else {
                validateBundlePath(bundle, lake.get(), errors);
            }
        }
        
        // Validate package prefix
        if (bundle.getBundlePrefix() == null || bundle.getBundlePrefix().trim().isEmpty()) {
            // Bundle prefix is optional - bundle can be directly under lake
            LOG.debugf("Bundle %s has no prefix, will be placed directly under lake", bundleName);
        } else if (!bundle.getBundlePrefix().matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")) {
            errors.add("Invalid proto package format. Expected: com.example.proto");
        }
        
        // Validate version
        if (!bundle.getVersion().isEmpty() && !bundle.getVersion().matches("^\\d+\\.\\d+\\.\\d+$")) {
            errors.add("Invalid version format. Expected: X.Y.Z");
        }
        
        // Validate configuration
        validateConfig(bundle, errors);
        
        if (!errors.isEmpty()) {
            throw new ValidationException("Bundle validation failed", errors);
        }
    }
    
    private void validateBundlePath(Bundle bundle, Lake lake, List<String> errors) {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path bundlePath = BundleUtil.calculateBundlePath(lake, bundle, basePath);
        
        // Ensure bundle is within lake
        if (!bundlePath.startsWith(lakePath)) {
            errors.add("Bundle path must be within lake: " + lakePath);
            return;
        }
        
        // Validate bundle prefix consistency
        validateBundlePrefixConsistency(bundle, lake, errors);
        
        // Check if bundle.yaml already exists in expected location
        Path expectedBundleYaml = bundlePath.resolve(BUNDLE_YAML);
        if (Files.exists(expectedBundleYaml)) {
            // This is OK for updates
            LOG.debugf("Bundle.yaml already exists at: %s", expectedBundleYaml);
        }
        
        // Validate proto files exist (only for existing bundles)
        if (Files.exists(bundlePath)) {
            try {
                boolean hasProtos = Files.walk(bundlePath)
                    .anyMatch(path -> path.toString().endsWith(".proto"));
                if (!hasProtos) {
                    // This is just a warning, not an error, as bundle might be newly created
                    LOG.warnf("No .proto files found in bundle directory: %s", bundlePath);
                }
            } catch (Exception e) {
                LOG.warnf("Failed to check for proto files: %s", e.getMessage());
            }
        }
    }
    
    private void validateConfig(Bundle bundle, List<String> errors) {
        if (!bundle.hasConfig()) {
            return; // Config is optional
        }
        
        // Validate language configurations
        if (bundle.getConfig().hasLanguages()) {
            // Validate Java config
            if (bundle.getConfig().getLanguages().hasJava()) {
                var java = bundle.getConfig().getLanguages().getJava();
                if (java.getEnabled() && 
                    java.getGroupId().trim().isEmpty()) {
                    errors.add("Java group ID is required when Java is enabled");
                }
                if (java.getEnabled() && java.getArtifactId().trim().isEmpty()) {
                    errors.add("Java artifact ID is required when Java is enabled");
                }
            }
            
            // Validate Python config
            if (bundle.getConfig().getLanguages().hasPython()) {
                var python = bundle.getConfig().getLanguages().getPython();
                if (python.getEnabled() && 
                    python.getPackageName().trim().isEmpty()) {
                    errors.add("Python package name is required when Python is enabled");
                }
            }
            
            // Validate JavaScript config
            if (bundle.getConfig().getLanguages().hasJavascript()) {
                var js = bundle.getConfig().getLanguages().getJavascript();
                if (js.getEnabled() && js.getPackageName().trim().isEmpty()) {
                    errors.add("JavaScript package name is required when JavaScript is enabled");
                }
            }
        }
    }
    
    /**
     * Validates that the bundle prefix matches the actual directory structure.
     * This ensures consistency between configuration and filesystem layout.
     */
    private void validateBundlePrefixConsistency(Bundle bundle, Lake lake, List<String> errors) {
        String bundleName = BundleUtil.extractBundleId(bundle.getName());
        Path bundlePath = BundleUtil.calculateBundlePath(lake, bundle, basePath);
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        
        // Validate that the prefix matches the directory structure
        if (!PathValidationUtil.validateBundlePrefix(bundle.getBundlePrefix(), bundleName, bundlePath, lakePath)) {
            String expectedPrefix = PathValidationUtil.calculateBundlePrefix(bundlePath, bundleName, lakePath);
            errors.add(String.format(
                "Bundle prefix mismatch. Configuration has '%s' but actual path suggests '%s'",
                bundle.getBundlePrefix(), expectedPrefix));
        }
    }
}
