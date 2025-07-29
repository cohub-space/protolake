package io.vdp.protolake.util;

import com.google.protobuf.Timestamp;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Utility methods for working with Lake proto messages.
 * Provides helper methods for common operations since proto messages are immutable.
 */
public class LakeUtil {
    
    /**
     * Gets the local path for a Lake based on lake_prefix and name.
     * Path = basePath + lake_prefix + name
     * 
     * @param lake The lake proto
     * @param basePath The base workspace path
     * @return The calculated lake path
     */
    public static Path getLocalPath(Lake lake, String basePath) {
        Path base = Paths.get(basePath);
        String lakeId = extractLakeId(lake.getName());
        
        if (!lake.getLakePrefix().isEmpty()) {
            // If lake has a prefix, use proper path resolution
            Path prefixPath = Paths.get(lake.getLakePrefix());
            return base.resolve(prefixPath).resolve(lakeId);
        } else {
            // No prefix, lake is directly under base
            return base.resolve(lakeId);
        }
    }
    
    /**
     * Gets the path of the lake relative to the base workspace.
     * This is used for constructing Bazel targets.
     * 
     * @param lake The lake proto
     * @return The relative path (e.g., "z/y/my-lake")
     */
    public static String getRelativePath(Lake lake) {
        String lakeId = extractLakeId(lake.getName());
        
        if (!lake.getLakePrefix().isEmpty()) {
            // Use Paths to properly build the path
            Path path = Paths.get(lake.getLakePrefix()).resolve(lakeId);
            // Convert to string with forward slashes for Bazel
            return path.toString().replace('\\', '/');
        } else {
            return lakeId;
        }
    }
    
    /**
     * Creates a Lake builder with lake_prefix set.
     */
    public static Lake.Builder withLakePrefix(Lake.Builder builder, String lakePrefix) {
        if (lakePrefix != null && !lakePrefix.isEmpty()) {
            builder.setLakePrefix(lakePrefix);
        }
        return builder;
    }
    
    /**
     * Extracts lake id from resource name format "lakes/{lake}".
     */
    public static String extractLakeId(String resourceName) {
        if (resourceName.startsWith("lakes/")) {
            return resourceName.substring("lakes/".length());
        }
        return resourceName;
    }
    
    /**
     * Converts Lake id to resource name format "lakes/{lake}".
     */
    public static String toResourceName(String lakeId) {
        return "lakes/" + lakeId;
    }
    
    /**
     * Converts java.time.Instant to protobuf Timestamp.
     */
    public static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
            .setSeconds(instant.getEpochSecond())
            .setNanos(instant.getNano())
            .build();
    }
    
    /**
     * Converts protobuf Timestamp to java.time.Instant.
     */
    public static Instant fromProtoTimestamp(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
    
    /**
     * Creates a default LakeConfig if none exists.
     */
    public static LakeConfig getDefaultConfig() {
        return LakeConfig.newBuilder()
            .setOrganization("default")
            .build();
    }
    
    /**
     * Gets the Bazel workspace root for a lake.
     * For ProtoLake, the workspace root is always the base path.
     * 
     * @param lake The lake (unused but kept for API compatibility)
     * @param basePath The base workspace path
     * @return The workspace root path
     */
    public static Path getWorkspaceRoot(Lake lake, String basePath) {
        // The workspace root is always the base path, not the lake path
        return Paths.get(basePath);
    }
}
