package io.vdp.protolake.util;

import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility methods for working with Bundle proto messages.
 * Provides helper methods for path calculation and common operations.
 */
public class BundleUtil {
    
    /**
     * Calculates the local path for a bundle based on lake path and bundle prefix.
     * Bundle prefix uses dots (.) which are converted to path separators.
     * Path = lakePath + bundle_prefix (converted to path) + bundle_name
     * 
     * @param lake The lake containing the bundle
     * @param bundle The bundle proto
     * @param basePath The base path for lakes (used to resolve lake path)
     * @return The calculated bundle path
     */
    public static Path calculateBundlePath(Lake lake, Bundle bundle, String basePath) {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        String bundleId = extractBundleId(bundle.getName());
        
        if (!bundle.getBundlePrefix().isEmpty()) {
            // Convert dots to path separators: com.company.protos -> com/company/protos
            String prefixPath = bundle.getBundlePrefix().replace('.', '/');
            return lakePath.resolve(prefixPath).resolve(bundleId);
        } else {
            // No prefix, bundle is directly under lake
            return lakePath.resolve(bundleId);
        }
    }
    
    /**
     * Calculates the local path for a bundle based on components.
     * 
     * @param lakePath The lake's local path
     * @param bundlePrefix The bundle prefix using dots (e.g., "com.company.protos")
     * @param bundleName The bundle name
     * @return The calculated bundle path
     */
    public static Path calculateBundlePath(Path lakePath, String bundlePrefix, String bundleName) {
        if (bundlePrefix != null && !bundlePrefix.isEmpty()) {
            // Convert dots to path separators
            String prefixPath = bundlePrefix.replace('.', '/');
            return lakePath.resolve(prefixPath).resolve(bundleName);
        } else {
            return lakePath.resolve(bundleName);
        }
    }
    
    /**
     * Gets the path to the bundle.yaml file for a bundle.
     */
    public static Path getBundleYamlPath(Lake lake, Bundle bundle, String basePath) {
        return calculateBundlePath(lake, bundle, basePath).resolve("bundle.yaml");
    }
    
    /**
     * Extracts bundle id from resource name format "lakes/{lake}/bundles/{bundle}".
     */
    public static String extractBundleId(String resourceName) {
        if (resourceName == null) {
            return "";
        }
        
        if (resourceName.contains("/bundles/")) {
            String[] parts = resourceName.split("/bundles/", -1); // -1 to include trailing empty strings
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        
        // If we can't extract properly, return empty string instead of full name
        // This ensures proper validation error messages
        return "";
    }
    
    /**
     * Extracts lake id from bundle resource name "lakes/{lake}/bundles/{bundle}".
     */
    public static String extractLakeIdFromBundle(String bundleResourceName) {
        if (bundleResourceName.startsWith("lakes/")) {
            String[] parts = bundleResourceName.substring("lakes/".length()).split("/bundles/");
            if (parts.length >= 1) {
                return parts[0];
            }
        }
        return "";
    }
    
    /**
     * Converts bundle name to resource name format "lakes/{lake}/bundles/{bundle}".
     */
    public static String toResourceName(String lakeId, String bundleId) {
        return String.format("lakes/%s/bundles/%s", lakeId, bundleId);
    }
    
    /**
     * Gets the relative path of the bundle from the workspace root.
     * This is used for constructing Bazel targets.
     * 
     * @param lake The lake containing the bundle
     * @param bundle The bundle proto
     * @return The relative path (e.g., "z/y/my-lake/com/company/protos/user")
     */
    public static String getWorkspaceRelativePath(Lake lake, Bundle bundle) {
        String lakePath = LakeUtil.getRelativePath(lake);
        String bundleId = extractBundleId(bundle.getName());
        
        // Use Paths to properly build the path
        Path path = Paths.get(lakePath);
        
        if (!bundle.getBundlePrefix().isEmpty()) {
            // Convert bundle prefix dots to path: com.company.protos -> com/company/protos
            String prefixPath = bundle.getBundlePrefix().replace('.', '/');
            path = path.resolve(prefixPath);
        }
        
        path = path.resolve(bundleId);
        
        // Convert to string with forward slashes for Bazel
        return path.toString().replace('\\', '/');
    }
    
    /**
     * Gets the Bazel target for a bundle.
     * This returns the full target path from workspace root.
     * 
     * @param lake The lake containing the bundle
     * @param bundle The bundle proto
     * @return The Bazel target (e.g., "//z/y/my-lake/com/company/protos/user/...")
     */
    public static String getWorkspaceRelativeTarget(Lake lake, Bundle bundle) {
        return "//" + getWorkspaceRelativePath(lake, bundle) + "/...";
    }
    
    /**
     * Gets the full proto package for a bundle.
     * This combines the bundle prefix and bundle name with dots.
     * 
     * @param bundle The bundle proto
     * @return The full proto package (e.g., "com.company.protos.user")
     */
    public static String getFullProtoPackage(Bundle bundle) {
        String bundleId = extractBundleId(bundle.getName());
        
        if (!bundle.getBundlePrefix().isEmpty()) {
            return bundle.getBundlePrefix() + "." + bundleId;
        } else {
            return bundleId;
        }
    }
}
