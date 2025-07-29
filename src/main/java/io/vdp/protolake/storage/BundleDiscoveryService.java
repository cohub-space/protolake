package io.vdp.protolake.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.PathValidationUtil;
import io.vdp.protolake.validator.BundleValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.JavaConfig;
import protolake.v1.JavaScriptConfig;
import protolake.v1.Lake;
import protolake.v1.PythonConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for discovering bundles from the filesystem.
 * 
 * This service scans directories for bundle.yaml files and creates
 * Bundle proto messages from them. It follows the same discovery approach
 * as the protolake-gazelle extension.
 */
@ApplicationScoped
public class BundleDiscoveryService {
    private static final Logger LOG = Logger.getLogger(BundleDiscoveryService.class);
    private static final String BUNDLE_YAML = "bundle.yaml";
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Inject
    SqliteStorageService storageService;

    @Inject
    BundleValidator bundleValidator;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    
    /**
     * Discovers all bundles in a lake by scanning for bundle.yaml files.
     * 
     * @param lake The lake proto to scan
     * @return List of discovered bundles as proto messages
     */
    public List<Bundle> discoverBundles(Lake lake) {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        
        // Use the generic discovery method with lake metadata
        return discoverBundles(lakePath, lake);
    }
    
    /**
     * Discovers all bundles under a given path by scanning for bundle.yaml files.
     * This generic method allows discovery from any path in the workspace.
     * 
     * @param searchPath The path to search for bundles
     * @return List of discovered bundles as proto messages
     */
    public List<Bundle> discoverBundles(Path searchPath) {
        return discoverBundles(searchPath, null);
    }
    
    /**
     * Internal method that discovers bundles with optional lake context.
     * 
     * @param searchPath The path to search for bundles
     * @param lake Optional lake context for bundle metadata
     * @return List of discovered bundles as proto messages
     */
    private List<Bundle> discoverBundles(Path searchPath, Lake lake) {
        List<Bundle> bundles = new ArrayList<>();
        
        if (!Files.exists(searchPath)) {
            LOG.warnf("Search path does not exist: %s", searchPath);
            return bundles;
        }
        
        try {
            // Walk the directory tree looking for bundle.yaml files
            try (Stream<Path> paths = Files.walk(searchPath)) {
                paths.filter(path -> path.getFileName().toString().equals(BUNDLE_YAML))
                     .forEach(bundleYamlPath -> {
                         try {
                             Bundle bundle = parseBundle(bundleYamlPath, searchPath, lake);
                             if (bundle != null) {
                                 bundles.add(bundle);
                             }
                         } catch (Exception e) {
                             LOG.errorf(e, "Failed to parse bundle at %s", bundleYamlPath);
                         }
                     });
            }
        } catch (IOException e) {
            LOG.errorf(e, "Failed to discover bundles in path %s", searchPath);
        }
        
        LOG.infof("Discovered %d bundles in path %s", bundles.size(), searchPath);
        return bundles;
    }
    
    /**
     * Parses a bundle.yaml file and creates a Bundle proto message.
     * 
     * @param bundleYamlPath Path to the bundle.yaml file
     * @param searchPath The root path we're searching from
     * @param lake Optional lake context for constructing resource names
     * @return Bundle proto or null if parsing fails
     */
    @SuppressWarnings("unchecked")
    private Bundle parseBundle(Path bundleYamlPath, Path searchPath, Lake lake) throws IOException {
        Map<String, Object> yamlData = yamlMapper.readValue(bundleYamlPath.toFile(), Map.class);
        
        Map<String, Object> bundleData = (Map<String, Object>) yamlData.get("bundle");
        if (bundleData == null) {
            LOG.warnf("No 'bundle' section in %s", bundleYamlPath);
            return null;
        }
        
        String bundleId = (String) bundleData.get("name");
        if (bundleId == null || bundleId.isEmpty()) {
            LOG.warnf("No bundle name in %s", bundleYamlPath);
            return null;
        }
        
        // Get bundle_prefix from YAML
        String bundlePrefixFromYaml = (String) bundleData.get("bundle_prefix");
        if (bundlePrefixFromYaml == null) {
            bundlePrefixFromYaml = (String) bundleData.get("package_prefix"); // Also check alternate name
        }
        
        // Calculate expected prefix from directory structure
        String expectedPrefix = calculateBundlePrefix(bundleYamlPath.getParent(), searchPath, bundleId);
        
        // Validate prefix consistency
        String bundlePrefix;
        if (bundlePrefixFromYaml == null || bundlePrefixFromYaml.isEmpty()) {
            // No prefix in YAML, use calculated one
            bundlePrefix = expectedPrefix;
            LOG.debugf("Bundle %s has no prefix in YAML, using calculated prefix: %s", bundleId, bundlePrefix);
        } else {
            // Validate that YAML prefix matches directory structure
            if (!bundlePrefixFromYaml.equals(expectedPrefix)) {
                LOG.warnf("Bundle prefix mismatch in %s. YAML has '%s' but directory structure suggests '%s'",
                    bundleYamlPath, bundlePrefixFromYaml, expectedPrefix);
            }
            bundlePrefix = bundlePrefixFromYaml;
        }
        
        // Build resource name
        String resourceName;
        if (lake != null) {
            String lakeName = LakeUtil.extractLakeId(lake.getName());
            resourceName = BundleUtil.toResourceName(lakeName, bundleId);
        } else {
            // Without lake context, just use the bundle name
            resourceName = bundleId;
        }
        
        // Build Bundle proto
        Bundle.Builder bundleBuilder = Bundle.newBuilder()
            .setName(resourceName)
            .setDisplayName((String) bundleData.getOrDefault("display_name", bundleId))
            .setDescription((String) bundleData.getOrDefault("description", ""))
            .setBundlePrefix(bundlePrefix)
            .setVersion((String) bundleData.getOrDefault("version", "1.0.0"));
        
        // Set timestamps based on file modification time
        Instant modTime = Instant.ofEpochMilli(bundleYamlPath.toFile().lastModified());
        bundleBuilder.setCreateTime(LakeUtil.toProtoTimestamp(modTime))
                     .setUpdateTime(LakeUtil.toProtoTimestamp(modTime));
        
        // Parse and set bundle configuration
        bundleBuilder.setConfig(parseBundleConfig(yamlData));
        
        Bundle bundle = bundleBuilder.build();
        
        // Additional validation during discovery
        if (lake != null) {
            validateDiscoveredBundle(bundle, lake, bundleYamlPath);
        }
        
        return bundle;
    }
    
    /**
     * Calculates the bundle prefix from the directory structure.
     * The prefix is the path from the search root to the bundle directory,
     * excluding the bundle name itself, converted to dot notation.
     * 
     * @param bundleDir The directory containing bundle.yaml
     * @param searchPath The root search path
     * @param bundleId The bundle name to exclude from the prefix
     * @return The calculated bundle prefix in dot notation (e.g., "com.company.protos")
     */
    private String calculateBundlePrefix(Path bundleDir, Path searchPath, String bundleId) {
        try {
            // Get the relative path from search root to bundle directory
            Path relativePath = searchPath.relativize(bundleDir);
            String pathStr = relativePath.toString().replace('\\', '/');
            
            // If the path ends with the bundle name, remove it
            if (pathStr.endsWith("/" + bundleId)) {
                pathStr = pathStr.substring(0, pathStr.length() - bundleId.length() - 1);
            } else if (pathStr.equals(bundleId)) {
                // Bundle is directly under search path
                return "";
            }
            
            // Convert path separators to dots for proto package notation
            // e.g., "com/company/protos" -> "com.company.protos"
            return pathStr.replace('/', '.');
        } catch (Exception e) {
            LOG.warnf("Failed to calculate bundle prefix for %s: %s", bundleDir, e.getMessage());
            return "";
        }
    }

    /**
     * Parses the config section of bundle.yaml into a BundleConfig proto.
     */
    @SuppressWarnings("unchecked")
    private BundleConfig parseBundleConfig(Map<String, Object> yamlData) {
        BundleConfig.Builder configBuilder = BundleConfig.newBuilder();
        
        Map<String, Object> config = (Map<String, Object>) yamlData.get("config");
        if (config == null) {
            return configBuilder.build();
        }
        
        Map<String, Object> languages = (Map<String, Object>) config.get("languages");
        if (languages != null) {
            // Parse Java config
            Map<String, Object> javaConfig = (Map<String, Object>) languages.get("java");
            if (javaConfig != null) {
                JavaConfig.Builder javaBuilder = JavaConfig.newBuilder();
                if (javaConfig.containsKey("enabled")) {
                    javaBuilder.setEnabled((Boolean) javaConfig.get("enabled"));
                }
                if (javaConfig.containsKey("group_id")) {
                    javaBuilder.setGroupId((String) javaConfig.get("group_id"));
                }
                if (javaConfig.containsKey("artifact_id")) {
                    javaBuilder.setArtifactId((String) javaConfig.get("artifact_id"));
                }
                if (javaConfig.containsKey("source_version")) {
                    javaBuilder.setSourceVersion((String) javaConfig.get("source_version"));
                }
                if (javaConfig.containsKey("target_version")) {
                    javaBuilder.setTargetVersion((String) javaConfig.get("target_version"));
                }
                if (javaConfig.containsKey("protobuf_java_version")) {
                    javaBuilder.setProtobufJavaVersion((String) javaConfig.get("protobuf_java_version"));
                }
                if (javaConfig.containsKey("grpc_java_version")) {
                    javaBuilder.setGrpcJavaVersion((String) javaConfig.get("grpc_java_version"));
                }
                if (javaConfig.containsKey("java_multiple_files")) {
                    javaBuilder.setJavaMultipleFiles((Boolean) javaConfig.get("java_multiple_files"));
                }
                if (javaConfig.containsKey("java_outer_classname_suffix")) {
                    javaBuilder.setJavaOuterClassnameSuffix((String) javaConfig.get("java_outer_classname_suffix"));
                }
                configBuilder.getLanguagesBuilder().setJava(javaBuilder);
            }
            
            // Parse Python config
            Map<String, Object> pythonConfig = (Map<String, Object>) languages.get("python");
            if (pythonConfig != null) {
                PythonConfig.Builder pythonBuilder = PythonConfig.newBuilder();
                if (pythonConfig.containsKey("enabled")) {
                    pythonBuilder.setEnabled((Boolean) pythonConfig.get("enabled"));
                }
                if (pythonConfig.containsKey("package_name")) {
                    pythonBuilder.setPackageName((String) pythonConfig.get("package_name"));
                }
                configBuilder.getLanguagesBuilder().setPython(pythonBuilder);
            }
            
            // Parse JavaScript config
            Map<String, Object> jsConfig = (Map<String, Object>) languages.get("javascript");
            if (jsConfig != null) {
                JavaScriptConfig.Builder jsBuilder = JavaScriptConfig.newBuilder();
                if (jsConfig.containsKey("enabled")) {
                    jsBuilder.setEnabled((Boolean) jsConfig.get("enabled"));
                }
                if (jsConfig.containsKey("package_name")) {
                    jsBuilder.setPackageName((String) jsConfig.get("package_name"));
                }
                configBuilder.getLanguagesBuilder().setJavascript(jsBuilder);
            }
        }
        
        return configBuilder.build();
    }

    /**
     * Refreshes all bundles for a specific lake.
     * This unified method discovers bundles, validates them, and updates storage.
     *
     * @param lake The lake to refresh bundles for
     * @return Number of bundles discovered
     */
    public int refreshBundlesForLake(Lake lake) {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Refreshing bundles for lake: %s", lakeName);

        try {
            // Discover all bundles in the lake
            List<Bundle> discoveredBundles = discoverBundles(lake);

            // Get current bundle names for stale removal
            List<String> currentBundleNames = discoveredBundles.stream()
                    .map(bundle -> BundleUtil.extractBundleId(bundle.getName()))
                    .collect(Collectors.toList());

            // Remove stale bundles that no longer exist
            storageService.removeStaleBundles(lakeName, currentBundleNames);

            int bundleCount = 0;
            for (Bundle bundle : discoveredBundles) {
                String bundleName = BundleUtil.extractBundleId(bundle.getName());

                try {
                    // Delete existing bundle if present (to ensure clean state)
                    Optional<Bundle> existing = storageService.getBundle(lakeName, bundleName);
                    if (existing.isPresent()) {
                        LOG.debugf("Removing existing bundle for refresh: %s/%s", lakeName, bundleName);
                        storageService.deleteBundle(lakeName, bundleName);
                    }

                    // Create the bundle
                    storageService.createBundle(bundle);
                    LOG.debugf("Refreshed bundle: %s/%s", lakeName, bundleName);
                    bundleCount++;

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to refresh bundle: %s/%s", lakeName, bundleName);
                }
            }

            LOG.infof("Refreshed %d bundles for lake %s", bundleCount, lakeName);
            return bundleCount;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh bundles for lake: %s", lakeName);
            return 0;
        }
    }
    
    /**
     * Validates a discovered bundle's configuration against its actual location.
     * Logs warnings for any mismatches found during discovery.
     */
    private void validateDiscoveredBundle(Bundle bundle, Lake lake, Path bundleYamlPath) {
        String bundleName = BundleUtil.extractBundleId(bundle.getName());
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path actualBundlePath = bundleYamlPath.getParent();
        
        // Validate that the bundle prefix matches the directory structure
        if (!PathValidationUtil.validateBundlePrefix(bundle.getBundlePrefix(), bundleName, actualBundlePath, lakePath)) {
            String expectedPrefix = PathValidationUtil.calculateBundlePrefix(actualBundlePath, bundleName, lakePath);
            LOG.warnf("Bundle prefix validation failed for %s. Configuration has '%s' but path suggests '%s'",
                bundleName, bundle.getBundlePrefix(), expectedPrefix);
        }
    }
}
