package io.vdp.protolake.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for validating path and prefix consistency.
 * 
 * Provides methods to convert between dot notation (com.example.proto) and 
 * path notation (com/example/proto), and validate that prefixes match actual
 * directory structures.
 */
public class PathValidationUtil {
    
    /**
     * Converts a dot-separated package prefix to a path.
     * Example: "com.example.proto" -> "com/example/proto"
     * 
     * @param packagePrefix The package prefix in dot notation
     * @return The corresponding path string, or empty string if prefix is null/empty
     */
    public static String packagePrefixToPath(String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isEmpty()) {
            return "";
        }
        return packagePrefix.replace('.', '/');
    }
    
    /**
     * Converts a path to a dot-separated package prefix.
     * Example: "com/example/proto" -> "com.example.proto"
     * 
     * @param path The path string
     * @return The corresponding package prefix in dot notation
     */
    public static String pathToPackagePrefix(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        // Normalize path separators and convert to dots
        return path.replace('\\', '/').replace('/', '.');
    }
    
    /**
     * Validates that a lake prefix matches its actual directory location.
     * 
     * @param lakePrefix The lake prefix from configuration
     * @param lakeName The lake name
     * @param actualPath The actual path where the lake is located
     * @param basePath The base workspace path
     * @return true if the prefix matches the actual location
     */
    public static boolean validateLakePrefix(String lakePrefix, String lakeName, 
                                           Path actualPath, Path basePath) {
        // Calculate expected path: basePath + lakePrefix + lakeName
        Path expectedPath = basePath;
        if (lakePrefix != null && !lakePrefix.isEmpty()) {
            expectedPath = expectedPath.resolve(lakePrefix);
        }
        expectedPath = expectedPath.resolve(lakeName);
        
        // Normalize both paths for comparison
        return expectedPath.normalize().equals(actualPath.normalize());
    }
    
    /**
     * Validates that a bundle prefix matches its actual directory location.
     * 
     * @param bundlePrefix The bundle prefix from configuration (in dot notation)
     * @param bundleName The bundle name
     * @param actualPath The actual path where the bundle is located
     * @param lakePath The parent lake path
     * @return true if the prefix matches the actual location
     */
    public static boolean validateBundlePrefix(String bundlePrefix, String bundleName,
                                             Path actualPath, Path lakePath) {
        // Calculate expected path: lakePath + bundlePrefix (as path) + bundleName
        Path expectedPath = lakePath;
        if (bundlePrefix != null && !bundlePrefix.isEmpty()) {
            String prefixPath = packagePrefixToPath(bundlePrefix);
            expectedPath = expectedPath.resolve(prefixPath);
        }
        expectedPath = expectedPath.resolve(bundleName);
        
        // Normalize both paths for comparison
        return expectedPath.normalize().equals(actualPath.normalize());
    }
    
    /**
     * Calculates the expected lake prefix from a lake's actual path.
     * 
     * @param lakePath The actual lake path
     * @param lakeName The lake name
     * @param basePath The base workspace path
     * @return The calculated lake prefix, or empty string if lake is directly under base
     */
    public static String calculateLakePrefix(Path lakePath, String lakeName, Path basePath) {
        try {
            // Get relative path from base to lake
            Path relativePath = basePath.relativize(lakePath);
            String relativeStr = relativePath.toString().replace('\\', '/');
            
            // Remove the lake name from the end
            if (relativeStr.endsWith("/" + lakeName)) {
                String prefix = relativeStr.substring(0, relativeStr.length() - lakeName.length() - 1);
                return prefix.isEmpty() ? "" : prefix;
            } else if (relativeStr.equals(lakeName)) {
                return "";
            }
            
            // Unexpected case - return the full relative path
            return relativeStr;
        } catch (IllegalArgumentException e) {
            // Paths are on different roots or other issue
            return "";
        }
    }
    
    /**
     * Calculates the expected bundle prefix from a bundle's actual path.
     * 
     * @param bundlePath The actual bundle path
     * @param bundleName The bundle name
     * @param lakePath The parent lake path
     * @return The calculated bundle prefix in dot notation, or empty string if bundle is directly under lake
     */
    public static String calculateBundlePrefix(Path bundlePath, String bundleName, Path lakePath) {
        try {
            // Get relative path from lake to bundle
            Path relativePath = lakePath.relativize(bundlePath);
            String relativeStr = relativePath.toString().replace('\\', '/');
            
            // Remove the bundle name from the end
            if (relativeStr.endsWith("/" + bundleName)) {
                String prefixPath = relativeStr.substring(0, relativeStr.length() - bundleName.length() - 1);
                return pathToPackagePrefix(prefixPath);
            } else if (relativeStr.equals(bundleName)) {
                return "";
            }
            
            // Unexpected case - return the full relative path as package
            return pathToPackagePrefix(relativeStr);
        } catch (IllegalArgumentException e) {
            // Paths are on different roots or other issue
            return "";
        }
    }
}
