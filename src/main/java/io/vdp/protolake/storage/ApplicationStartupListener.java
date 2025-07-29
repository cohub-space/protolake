package io.vdp.protolake.storage;

import io.quarkus.runtime.StartupEvent;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles application startup tasks for ProtoLake.
 * 
 * This listener:
 * - Initializes the SQLite database
 * - Triggers refresh of lakes and bundles from filesystem
 * - Ensures database is in sync with filesystem state
 */
@ApplicationScoped
public class ApplicationStartupListener {
    private static final Logger LOG = Logger.getLogger(ApplicationStartupListener.class);
    private static final String LAKE_YAML = "lake.yaml";
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;
    
    @Inject
    SqliteStorageService storageService;
    
    @Inject
    LakeDiscoveryService lakeDiscoveryService;

    @Inject
    BundleDiscoveryService bundleDiscoveryService;
    
    void onStart(@Observes StartupEvent ev) {
        LOG.info("ProtoLake starting up - initializing storage and refreshing lakes");
        
        try {
            // Database is initialized in SqliteStorageService @PostConstruct
            
            // Refresh lakes from filesystem
            refreshLakes();
            
            LOG.info("ProtoLake startup complete");
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to initialize ProtoLake");
            throw new RuntimeException("ProtoLake startup failed", e);
        }
    }
    
    /**
     * Refreshes all lakes and their bundles from the filesystem.
     * This ensures the database is in sync with the current filesystem state.
     */
    private void refreshLakes() {
        Path workspaceRoot = Paths.get(basePath);
        
        // Use the unified refresh method from LakeDiscoveryService
        int lakeCount = lakeDiscoveryService.refreshLakes(workspaceRoot);
        
        LOG.infof("ProtoLake refresh complete: discovered %d lakes", lakeCount);
    }
}
