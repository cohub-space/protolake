package io.vdp.protolake.pipeline;

import io.vdp.protolake.storage.BundleDiscoveryService;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.bazel.BazelCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import com.google.protobuf.Timestamp;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes Bazel build commands.
 * 
 * Handles incremental builds and caching through Bazel's build system.
 * Supports both targeted and isolated bundle builds based on configuration.
 * 
 * Bazel is always run from the workspace root (base path), and targets
 * are computed relative to that root.
 */
@ApplicationScoped
public class BazelBuildRunner {
    private static final Logger LOG = Logger.getLogger(BazelBuildRunner.class);

    @Inject
    BazelCommand bazelCommand;

    @ConfigProperty(name = "protolake.build.bazel-options", defaultValue = "--jobs=4")
    String defaultBazelOptions;

    @ConfigProperty(name = "protolake.build.enable-remote-cache", defaultValue = "false")
    boolean enableRemoteCache;

    @ConfigProperty(name = "protolake.build.remote-cache-url")
    String remoteCacheUrl;
    

    @Inject
    BundleDiscoveryService bundleDiscoveryService;

    /**
     * Cleans bazel cache for a workspace.
     */
    public void clean(Path workspaceRoot) throws IOException {
        LOG.infof("Cleaning bazel cache at: %s", workspaceRoot);
        
        try {
            bazelCommand.run(workspaceRoot, "clean", "--expunge");
            LOG.infof("Bazel cache cleaned");
        } catch (Exception e) {
            throw new IOException("Failed to clean bazel cache: " + e.getMessage(), e);
        }
    }

    /**
     * Queries bazel for information about targets.
     */
    public List<String> query(Path workspaceRoot, String query) throws IOException {
        LOG.debugf("Running bazel query: %s", query);
        
        try {
            String output = bazelCommand.runWithOutput(workspaceRoot, "query", query);
            List<String> results = new ArrayList<>();
            
            for (String line : output.split("\n")) {
                if (!line.trim().isEmpty()) {
                    results.add(line.trim());
                }
            }
            
            return results;
        } catch (Exception e) {
            throw new IOException("Bazel query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a target with support for isolated bundle builds.
     * This is the main entry point for the unified build approach.
     * 
     * @param workspaceRoot The Bazel workspace root (should be base path)
     * @param target The target path relative to workspace root (e.g., "z/y" for a lake)
     * @param config Build configuration including isolate_bundle_builds flag
     * @param metadata The operation metadata to update
     * @return Updated metadata with build results
     */
    public BuildOperationMetadata buildTarget(Path workspaceRoot, String target, RunBuildConfig config, BuildOperationMetadata metadata) throws IOException {
        if (!config.getEnabled()) {
            LOG.debugf("Build disabled");
            return metadata;
        }
        
        // Initialize build phase status
        PhaseStatus.Builder buildStatus = PhaseStatus.newBuilder()
            .setStatus(PhaseStatus.Status.RUNNING)
            .setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());
        
        List<String> logs = new ArrayList<>();
        
        try {
            // Clean if requested
            if (config.getClean()) {
                LOG.infof("Cleaning bazel cache before build");
                buildStatus.setSubPhase("Cleaning bazel cache");
                clean(workspaceRoot);
                logs.add("Cleaned bazel cache");
            }
            
            // Check if we should isolate bundle builds
            if (config.getIsolateBundleBuilds()) {
                LOG.infof("Building bundles individually for target: %s", target);
                metadata = buildBundlesIndividually(workspaceRoot, target, config, metadata, buildStatus, logs);
            } else {
                // Direct build of the specified target
                LOG.infof("Building target directly: %s", target);
                metadata = buildTargetDirectly(workspaceRoot, target, config, metadata, buildStatus, logs);
            }
            
            // Mark build phase as successful
            buildStatus
                .setStatus(PhaseStatus.Status.SUCCEEDED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .addAllLogLines(logs);
            
            return metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setBuild(buildStatus.build())
                    .build())
                .build();
                
        } catch (Exception e) {
            // Mark build as failed
            buildStatus
                .setStatus(PhaseStatus.Status.FAILED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .setErrorMessage("Build failed: " + e.getMessage())
                .addAllLogLines(logs);
                
            BuildOperationMetadata failedMetadata = metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setBuild(buildStatus.build())
                    .build())
                .build();
                
            throw new IOException("Build failed", e);
        }
    }
    
    /**
     * Builds bundles individually by discovering bundles under the target path.
     * 
     * @param workspaceRoot The workspace root (base path)
     * @param targetPath The relative path from workspace root (e.g., "z/y" for a lake)
     * @param config Build configuration
     * @param metadata Operation metadata
     * @param buildStatus Build status builder
     * @param logs Log accumulator
     * @return Updated metadata
     */
    private BuildOperationMetadata buildBundlesIndividually(Path workspaceRoot, String targetPath, 
                                                           RunBuildConfig config, BuildOperationMetadata metadata, 
                                                           PhaseStatus.Builder buildStatus, List<String> logs) throws IOException {
        LOG.infof("Discovering bundles under path: %s", targetPath);
        buildStatus.setSubPhase("Discovering bundle targets");
        logs.add("Discovering bundle targets...");

        List<String> bundleTargets = getBundleTargetPathsForTargetPath(workspaceRoot, targetPath, metadata);

        // Sort by depth (deeper first) to ensure proper build order
        bundleTargets.sort((a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            return Integer.compare(depthB, depthA);
        });
        
        LOG.infof("Found %d bundle targets to build", bundleTargets.size());
        logs.add(String.format("Found %d bundle targets", bundleTargets.size()));
        
        // Initialize target builds in metadata
        Map<String, TargetBuildInfo> targetBuilds = new HashMap<>(metadata.getTargetBuildsMap());
        for (String bundlePath : bundleTargets) {
            String targetKey = getBazelTargetFromBundlePath(bundlePath);
            targetBuilds.put(targetKey, createTargetBuildInfo(targetKey, metadata.getBranch(), TargetBuildInfo.Status.PENDING));
        }
        
        metadata = metadata.toBuilder()
            .putAllTargetBuilds(targetBuilds)
            .build();

        // Build each bundle target individually
        for (String bundlePath : bundleTargets) {
            String bazelTarget = getBazelTargetFromBundlePath(bundlePath);

            LOG.infof("Building bundle: %s", bazelTarget);
            buildStatus.setSubPhase(String.format("Building %s", bazelTarget));
            logs.add(String.format("Building: %s", bazelTarget));
            
            // Update target status to BUILDING
            TargetBuildInfo targetInfo = updateTargetBuildInfo(targetBuilds.get(bazelTarget), TargetBuildInfo.Status.BUILDING);
            targetBuilds.put(bazelTarget, targetInfo);
            
            metadata = metadata.toBuilder()
                .putAllTargetBuilds(targetBuilds)
                .build();

            try {
                String targetLog = buildTargetDirectlyImpl(workspaceRoot, bazelTarget, config);
                
                // Mark target as PUBLISHED (build + publish complete)
                targetInfo = updateTargetBuildInfo(targetInfo, TargetBuildInfo.Status.PUBLISHED)
                    .toBuilder()
                    .addAllBuildLogs(ImmutableList.of(targetLog))
                    .build();
                targetBuilds.put(bazelTarget, targetInfo);
                
                logs.add(String.format("Successfully built: %s", bazelTarget));
                
            } catch (IOException e) {
                // Mark target as FAILED
                targetInfo = updateTargetBuildInfo(targetInfo, TargetBuildInfo.Status.FAILED)
                    .toBuilder()
                    .setErrorMessage(e.getMessage())
                    .build();
                targetBuilds.put(bazelTarget, targetInfo);
                
                if (config.getKeepGoing()) {
                    LOG.errorf(e, "Failed to build %s, continuing with next target", bazelTarget);
                    logs.add(String.format("Failed to build %s: %s (continuing)", bazelTarget, e.getMessage()));
                } else {
                    // Update metadata before throwing
                    metadata = metadata.toBuilder()
                        .putAllTargetBuilds(targetBuilds)
                        .build();
                    throw e;
                }
            }
            
            // Update metadata with latest target builds
            metadata = metadata.toBuilder()
                .putAllTargetBuilds(targetBuilds)
                .build();
        }
        
        return metadata;
    }

    private static String getBazelTargetFromBundlePath(String bundlePath) {
        String targetKey = "//" + bundlePath + "/...";
        return targetKey;
    }

    private List<String> getBundleTargetPathsForTargetPath(Path workspaceRoot, String targetPath, BuildOperationMetadata metadata) throws IOException {
        // Convert target path to absolute path for discovery
        Path searchPath = workspaceRoot.resolve(targetPath);

        // Discover bundles under the target path
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(searchPath);

        // Get the lake from metadata to compute full paths
        if (!metadata.hasLake()) {
            throw new IOException("Lake not set in build metadata");
        }
        Lake lake = metadata.getLake();

        // Compute workspace-relative paths for each bundle
        List<String> bundleTargets = new ArrayList<>();
        for (Bundle bundle : bundles) {
            String bundlePath = BundleUtil.getWorkspaceRelativePath(lake, bundle);
            bundleTargets.add(bundlePath);
        }
        return bundleTargets;
    }

    /**
     * Builds a specific target directly.
     * 
     * @param workspaceRoot The workspace root (base path)
     * @param targetPath The relative path from workspace root
     * @param config Build configuration
     * @param metadata Operation metadata
     * @param buildStatus Build status builder
     * @param logs Log accumulator
     * @return Updated metadata
     */
    private BuildOperationMetadata buildTargetDirectly(Path workspaceRoot, String targetPath, RunBuildConfig config,
                                                      BuildOperationMetadata metadata, PhaseStatus.Builder buildStatus, 
                                                      List<String> logs) throws IOException {
        // Convert path to Bazel target
        String bazelTarget = getBazelTargetFromBundlePath(targetPath);

        // For direct target builds, create a single target entry
        Map<String, TargetBuildInfo> targetBuilds = new HashMap<>(metadata.getTargetBuildsMap());
        
        TargetBuildInfo targetInfo = createTargetBuildInfo(bazelTarget, metadata.getBranch(), TargetBuildInfo.Status.BUILDING);
        targetBuilds.put(bazelTarget, targetInfo);
        
        metadata = metadata.toBuilder()
            .putAllTargetBuilds(targetBuilds)
            .build();
        
        try {
            String targetLog = buildTargetDirectlyImpl(workspaceRoot, bazelTarget, config);
            
            // Mark as published (build + publish complete)
            targetInfo = updateTargetBuildInfo(targetInfo, TargetBuildInfo.Status.PUBLISHED)
                .toBuilder()
                .addAllBuildLogs(ImmutableList.of(targetLog))
                .build();
            targetBuilds.put(bazelTarget, targetInfo);
            
            logs.add(String.format("Successfully built: %s", bazelTarget));
            
        } catch (IOException e) {
            // Mark as failed
            targetInfo = updateTargetBuildInfo(targetInfo, TargetBuildInfo.Status.FAILED)
                .toBuilder()
                .setErrorMessage(e.getMessage())
                .build();
            targetBuilds.put(bazelTarget, targetInfo);
            throw e;
        }
        
        return metadata.toBuilder()
            .putAllTargetBuilds(targetBuilds)
            .build();
    }
    
    /**
     * Implementation of building a specific target directly.
     * 
     * @param workspaceRoot The workspace root to run bazel from
     * @param bazelTarget The full Bazel target (e.g., "//z/y/...")
     * @param config Build configuration
     * @return List of log lines from the build
     */
    private String buildTargetDirectlyImpl(Path workspaceRoot, String bazelTarget, RunBuildConfig config) throws IOException {
        List<String> args = buildBazelArgs("build", bazelTarget, config);
        
        try {
            // Run bazel build and capture output
            String logOutput = bazelCommand.runWithOutput(workspaceRoot, args.toArray(new String[0]));
            
            LOG.infof("Successfully built target: %s", bazelTarget);

            return logOutput;
        } catch (Exception e) {
            throw new IOException("Bazel build failed for target " + bazelTarget + ": " + e.getMessage(), e);
        }
    }

    /**
     * Gets default build configuration with build enabled.
     */
    private RunBuildConfig getDefaultBuildConfig() {
        return RunBuildConfig.newBuilder()
            .setEnabled(true)
            .setClean(false)
            .build();
    }

    /**
     * Builds bazel command arguments with common options.
     */
    private List<String> buildBazelArgs(String command, String target) {
        return buildBazelArgs(command, target, getDefaultBuildConfig());
    }
    
    /**
     * Builds bazel command arguments with configuration-specific options.
     */
    private List<String> buildBazelArgs(String command, String target, RunBuildConfig config) {
        List<String> args = new ArrayList<>();
        args.add(command);
        args.add(target);
        
        // Add default options
        if (defaultBazelOptions != null && !defaultBazelOptions.isEmpty()) {
            for (String option : defaultBazelOptions.split(" ")) {
                if (!option.trim().isEmpty()) {
                    args.add(option.trim());
                }
            }
        }
        
        // Add configuration-specific options
        if (config.getKeepGoing()) {
            args.add("--keep_going");
        }
        
        // Add remote cache if enabled
        if (enableRemoteCache && remoteCacheUrl != null && !remoteCacheUrl.isEmpty()) {
            args.add("--remote_cache=" + remoteCacheUrl);
            args.add("--remote_upload_local_results=true");
        }
        
        // Always show progress
        args.add("--show_progress");
        args.add("--progress_report_interval=10");
        
        return args;
    }
    
    /**
     * Creates a new TargetBuildInfo with the specified parameters.
     * 
     * @param target The Bazel target
     * @param version The version string
     * @param status The initial status
     * @return A new TargetBuildInfo instance
     */
    private TargetBuildInfo createTargetBuildInfo(String target, String version, TargetBuildInfo.Status status) {
        TargetBuildInfo.Builder builder = TargetBuildInfo.newBuilder()
            .setTarget(target)
            .setVersion(version)
            .setStatus(status);
        
        // Set start time if status is BUILDING
        if (status == TargetBuildInfo.Status.BUILDING) {
            builder.setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());
        }
        
        return builder.build();
    }
    
    /**
     * Updates an existing TargetBuildInfo with a new status and appropriate timestamps.
     * 
     * @param currentInfo The current TargetBuildInfo
     * @param newStatus The new status to set
     * @return Updated TargetBuildInfo
     */
    private TargetBuildInfo updateTargetBuildInfo(TargetBuildInfo currentInfo, TargetBuildInfo.Status newStatus) {
        TargetBuildInfo.Builder builder = currentInfo.toBuilder()
            .setStatus(newStatus);
        
        // Set start time if transitioning to BUILDING
        if (newStatus == TargetBuildInfo.Status.BUILDING && !currentInfo.hasStartTime()) {
            builder.setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());
        }
        
        // Set end time if transitioning to a terminal state
        if ((newStatus == TargetBuildInfo.Status.PUBLISHED || 
             newStatus == TargetBuildInfo.Status.FAILED || 
             newStatus == TargetBuildInfo.Status.SKIPPED) && !currentInfo.hasEndTime()) {
            builder.setEndTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());
        }
        
        return builder.build();
    }
}
