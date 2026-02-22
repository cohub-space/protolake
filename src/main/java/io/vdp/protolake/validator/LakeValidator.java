package io.vdp.protolake.validator;

import io.vdp.protolake.storage.ValidationException;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.PathValidationUtil;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Lake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates lake creation and update operations.
 * 
 * Ensures that:
 * - Lake paths are relative to workspace root
 * - Lake prefix matches the actual directory structure
 * - No nested lakes exist (no lake.yaml between root and new lake)
 * - Only one lake.yaml per directory
 * - Lake configuration is valid
 */
@ApplicationScoped
public class LakeValidator {
    private static final Logger LOG = Logger.getLogger(LakeValidator.class);
    private static final String LAKE_YAML = "lake.yaml";
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;
    
    /**
     * Validates a lake proto before creation or update.
     * 
     * @param lake The lake proto to validate
     * @throws ValidationException if validation fails
     */
    public void validate(Lake lake) throws ValidationException {
        List<String> errors = new ArrayList<>();
        
        // Validate name
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        if (lakeName.isEmpty()) {
            errors.add("Lake name is required");
        } else if (!lakeName.matches("^[a-zA-Z0-9_]+$")) {
            errors.add("Lake name must contain only alphanumeric characters and underscores");
        }
        
        // Validate local path
        Path localPath = LakeUtil.getLocalPath(lake, basePath);
        validateLocalPath(lakeName, localPath, errors);
        
        // Validate lake prefix consistency
        validateLakePrefixConsistency(lake, errors);
        
        // Validate configuration
        if (!lake.hasConfig()) {
            errors.add("Lake configuration is required");
        } else {
            validateConfig(lake, errors);
        }
        
        if (!errors.isEmpty()) {
            throw new ValidationException("Lake validation failed", errors);
        }
    }
    
    private void validateLocalPath(String lakeName, Path lakePath, List<String> errors) {
        Path workspaceRoot = Paths.get(basePath);
        
        // Ensure path is absolute
        if (!lakePath.isAbsolute()) {
            lakePath = workspaceRoot.resolve(lakePath);
        }
        
        // Ensure lake is within workspace
        if (!lakePath.startsWith(workspaceRoot)) {
            errors.add("Lake path must be within workspace root: " + workspaceRoot);
            return;
        }
        
        // Check for nested lakes
        Path currentPath = lakePath.getParent();
        while (currentPath != null && !currentPath.equals(workspaceRoot)) {
            if (Files.exists(currentPath.resolve(LAKE_YAML))) {
                errors.add("Nested lakes are not allowed. Found parent lake at: " + currentPath);
                return;
            }
            currentPath = currentPath.getParent();
        }
        
        // Check if directory exists (for updates)
        if (Files.exists(lakePath) && !Files.isDirectory(lakePath)) {
            errors.add("Lake path exists but is not a directory: " + lakePath);
        }
    }
    
    private void validateConfig(Lake lake, List<String> errors) {
        // Validate module bazel config
        if (lake.getConfig().hasModuleBazel()) {
            if (lake.getConfig().getModuleBazel().getProtobufVersion().trim().isEmpty()) {
                errors.add("Protobuf version is required when module_bazel is configured");
            }

            if (lake.getConfig().getModuleBazel().getGrpcVersion().trim().isEmpty()) {
                errors.add("gRPC version is required when module_bazel is configured");
            }
        }
    }
    
    /**
     * Validates that the lake prefix matches the actual directory structure.
     * This ensures consistency between configuration and filesystem layout.
     */
    private void validateLakePrefixConsistency(Lake lake, List<String> errors) {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        Path actualPath = LakeUtil.getLocalPath(lake, basePath);
        Path workspaceRoot = Paths.get(basePath);
        
        // Validate that the prefix matches the directory structure
        if (!PathValidationUtil.validateLakePrefix(lake.getLakePrefix(), lakeName, actualPath, workspaceRoot)) {
            String expectedPrefix = PathValidationUtil.calculateLakePrefix(actualPath, lakeName, workspaceRoot);
            errors.add(String.format(
                "Lake prefix mismatch. Configuration has '%s' but actual path suggests '%s'",
                lake.getLakePrefix(), expectedPrefix));
        }
    }
}
