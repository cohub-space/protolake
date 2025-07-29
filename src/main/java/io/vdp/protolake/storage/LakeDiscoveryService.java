package io.vdp.protolake.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.PathValidationUtil;
import io.vdp.protolake.validator.LakeValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;

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
 * Service for discovering lakes from the filesystem.
 * 
 * This service scans directories for lake.yaml files and creates
 * Lake proto messages from them. It validates that the lake configuration
 * matches the actual directory structure.
 */
@ApplicationScoped
public class LakeDiscoveryService {
    private static final Logger LOG = Logger.getLogger(LakeDiscoveryService.class);
    private static final String LAKE_YAML = "lake.yaml";
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Inject
    StorageService storageService;

    @Inject
    LakeValidator lakeValidator;
    
    @Inject
    BundleDiscoveryService bundleDiscoveryService;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Discovers all lakes under a given path by scanning for lake.yaml files.
     * 
     * @param searchPath The path to search for lakes
     * @return List of discovered lakes as proto messages
     */
    public List<Lake> discoverLakes(Path searchPath) {
        List<Lake> lakes = new ArrayList<>();
        
        if (!Files.exists(searchPath)) {
            LOG.warnf("Search path does not exist: %s", searchPath);
            return lakes;
        }
        
        try {
            // Walk the directory tree looking for lake.yaml files
            try (Stream<Path> paths = Files.walk(searchPath)) {
                paths.filter(path -> path.getFileName().toString().equals(LAKE_YAML))
                     .forEach(lakeYamlPath -> {
                         try {
                             Lake lake = parseLake(lakeYamlPath, searchPath);
                             if (lake != null) {
                                 lakes.add(lake);
                             }
                         } catch (Exception e) {
                             LOG.errorf(e, "Failed to parse lake at %s", lakeYamlPath);
                         }
                     });
            }
        } catch (IOException e) {
            LOG.errorf(e, "Failed to discover lakes in path %s", searchPath);
        }
        
        LOG.infof("Discovered %d lakes in path %s", lakes.size(), searchPath);
        return lakes;
    }
    
    /**
     * Parses a lake.yaml file and creates a Lake proto message.
     * 
     * @param lakeYamlPath Path to the lake.yaml file
     * @param searchPath The root path we're searching from
     * @return Lake proto or null if parsing fails
     */
    @SuppressWarnings("unchecked")
    private Lake parseLake(Path lakeYamlPath, Path searchPath) throws IOException {
        Map<String, Object> yamlData = yamlMapper.readValue(lakeYamlPath.toFile(), Map.class);
        
        // Get lake name
        String lakeName = (String) yamlData.get("name");
        if (lakeName == null || lakeName.isEmpty()) {
            LOG.warnf("No lake name in %s", lakeYamlPath);
            return null;
        }
        
        // Get lake_prefix from YAML
        String lakePrefixFromYaml = (String) yamlData.get("lake_prefix");
        
        // Calculate expected prefix from directory structure
        Path lakeDir = lakeYamlPath.getParent();
        String expectedPrefix = PathValidationUtil.calculateLakePrefix(lakeDir, lakeName, searchPath);
        
        // Validate prefix consistency
        String lakePrefix;
        if (lakePrefixFromYaml == null || lakePrefixFromYaml.isEmpty()) {
            // No prefix in YAML, use calculated one
            lakePrefix = expectedPrefix;
            LOG.debugf("Lake %s has no prefix in YAML, using calculated prefix: %s", lakeName, lakePrefix);
        } else {
            // Validate that YAML prefix matches directory structure
            if (!lakePrefixFromYaml.equals(expectedPrefix)) {
                LOG.warnf("Lake prefix mismatch in %s. YAML has '%s' but directory structure suggests '%s'",
                    lakeYamlPath, lakePrefixFromYaml, expectedPrefix);
            }
            lakePrefix = lakePrefixFromYaml;
        }
        
        // Build Lake proto
        Lake.Builder lakeBuilder = Lake.newBuilder()
            .setName(LakeUtil.toResourceName(lakeName))
            .setDisplayName(lakeName) // Default to name
            .setDescription("") // Default empty
            .setLakePrefix(lakePrefix);
        
        // Set timestamps based on file modification time
        Instant modTime = Instant.ofEpochMilli(lakeYamlPath.toFile().lastModified());
        lakeBuilder.setCreateTime(LakeUtil.toProtoTimestamp(modTime))
                   .setUpdateTime(LakeUtil.toProtoTimestamp(modTime));
        
        // Parse and set lake configuration
        LakeConfig config = parseLakeConfig(yamlData);
        if (config != null) {
            lakeBuilder.setConfig(config);
        }
        
        Lake lake = lakeBuilder.build();
        
        // Validate discovered lake
        validateDiscoveredLake(lake, lakeYamlPath, searchPath);
        
        return lake;
    }
    
    /**
     * Parses the configuration from lake.yaml into a LakeConfig proto.
     */
    @SuppressWarnings("unchecked")
    private LakeConfig parseLakeConfig(Map<String, Object> yamlData) {
        LakeConfig.Builder configBuilder = LakeConfig.newBuilder();
        
        // Set organization
        String organization = (String) yamlData.get("organization");
        if (organization != null && !organization.isEmpty()) {
            configBuilder.setOrganization(organization);
        }
        
        // Parse versions section
        Map<String, Object> versions = (Map<String, Object>) yamlData.get("versions");
        if (versions != null) {
            // Parse bazel_deps
            Map<String, Object> bazelDeps = (Map<String, Object>) versions.get("bazel_deps");
            if (bazelDeps != null) {
                if (bazelDeps.containsKey("protobuf")) {
                    configBuilder.getModuleBazelBuilder().setProtobufVersion((String) bazelDeps.get("protobuf"));
                }
                if (bazelDeps.containsKey("grpc")) {
                    configBuilder.getModuleBazelBuilder().setGrpcVersion((String) bazelDeps.get("grpc"));
                }
                if (bazelDeps.containsKey("rules_proto_grpc")) {
                    configBuilder.getModuleBazelBuilder().setRulesProtoGrpcVersion((String) bazelDeps.get("rules_proto_grpc"));
                }
            }
        }
        
        return configBuilder.build();
    }
    
    /**
     * Validates a discovered lake's configuration against its actual location.
     * Logs warnings for any mismatches found during discovery.
     */
    private void validateDiscoveredLake(Lake lake, Path lakeYamlPath, Path searchPath) {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        Path actualLakePath = lakeYamlPath.getParent();
        
        // Validate that the lake prefix matches the directory structure
        if (!PathValidationUtil.validateLakePrefix(lake.getLakePrefix(), lakeName, actualLakePath, searchPath)) {
            String expectedPrefix = PathValidationUtil.calculateLakePrefix(actualLakePath, lakeName, searchPath);
            LOG.warnf("Lake prefix validation failed for %s. Configuration has '%s' but path suggests '%s'",
                lakeName, lake.getLakePrefix(), expectedPrefix);
        }
    }
    
    /**
     * Refreshes all lakes from the filesystem.
     * This discovers lakes, validates them, and updates storage.
     *
     * @param searchPath The path to search for lakes
     * @return Number of lakes discovered
     */
    public int refreshLakes(Path searchPath) {
        LOG.infof("Refreshing lakes from path: %s", searchPath);

        try {
            // Discover all lakes
            List<Lake> discoveredLakes = discoverLakes(searchPath);

            // Get current lake names for stale removal
            List<String> currentLakeNames = discoveredLakes.stream()
                    .map(lake -> LakeUtil.extractLakeId(lake.getName()))
                    .collect(Collectors.toList());

            // Remove stale lakes that no longer exist
            removeStaleEntries(currentLakeNames);

            int lakeCount = 0;
            for (Lake lake : discoveredLakes) {
                String lakeName = LakeUtil.extractLakeId(lake.getName());

                try {
                    // Delete existing lake if present (to ensure clean state)
                    Optional<Lake> existing = storageService.getLake(lakeName);
                    if (existing.isPresent()) {
                        LOG.debugf("Removing existing lake for refresh: %s", lakeName);
                        // Don't delete filesystem, just database entry
                        storageService.deleteLake(lakeName);
                    }

                    // Create the lake
                    storageService.createLake(lake);
                    LOG.debugf("Refreshed lake: %s", lakeName);
                    lakeCount++;
                    
                    // Also refresh bundles for this lake
                    bundleDiscoveryService.refreshBundlesForLake(lake);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to refresh lake: %s", lakeName);
                }
            }

            LOG.infof("Refreshed %d lakes from %s", lakeCount, searchPath);
            return lakeCount;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh lakes from: %s", searchPath);
            return 0;
        }
    }
    
    /**
     * Removes lakes from storage that no longer exist on the filesystem.
     */
    private void removeStaleEntries(List<String> currentLakeNames) {
        try {
            List<Lake> existingLakes = storageService.listLakes();
            
            for (Lake existing : existingLakes) {
                String existingId = LakeUtil.extractLakeId(existing.getName());
                if (!currentLakeNames.contains(existingId)) {
                    LOG.infof("Removing stale lake from storage: %s", existingId);
                    storageService.deleteLake(existingId);
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to remove stale lakes");
        }
    }
}
