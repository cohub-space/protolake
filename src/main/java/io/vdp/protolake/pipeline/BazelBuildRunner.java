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
import java.util.Optional;

/**
 * Executes Bazel build commands.
 * 
 * Handles incremental builds and caching through Bazel's build system.
 * Supports both targeted and isolated bundle builds based on configuration.
 * 
 * IMPORTANT: Architecture Notes
 * - Bazel is always run from the lake root directory (where MODULE.bazel exists)
 * - Each lake has its own Bazel workspace with isolated cache (in bazel-* directories)
 * - All target paths passed to this class should be relative to the lake root
 * - Example: "." means build everything in the lake
 * - Example: "company_a/platform" means build only that subdirectory
 * 
 * The isolation ensures that:
 * - Each lake maintains its own build cache
 * - No conflicts between different lakes
 * - Bazel runs in the correct workspace context (no batch mode)
 * 
 * Path Coordinate Systems:
 * - Service layer uses base-relative paths (e.g., "z/y/lake/bundle")
 * - Build layer uses lake-relative paths (e.g., "bundle" or "company/bundle")
 * - BuildOrchestrator handles the conversion between these coordinate systems
 * - This class expects all paths to already be lake-relative
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
    Optional<String> remoteCacheUrl;
    

    @Inject
    BundleDiscoveryService bundleDiscoveryService;

    /**
     * Cleans bazel cache for a lake.
     */
    public void clean(Path lakeRoot) throws IOException {
        LOG.infof("Cleaning bazel cache at: %s", lakeRoot);
        
        try {
            bazelCommand.run(lakeRoot, "clean", "--expunge");
            LOG.infof("Bazel cache cleaned");
        } catch (Exception e) {
            throw new IOException("Failed to clean bazel cache: " + e.getMessage(), e);
        }
    }

    /**
     * Queries bazel for information about targets.
     */
    public List<String> query(Path lakeRoot, String query) throws IOException {
        LOG.debugf("Running bazel query: %s", query);
        
        try {
            String output = bazelCommand.runWithOutput(lakeRoot, "query", query);
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
     * @param lakeRoot The lake root directory (where MODULE.bazel exists)
     * @param target The target path relative to lake root (e.g., "." for entire lake, "company_a/platform" for subdirectory)
     * @param config Build configuration including isolate_bundle_builds flag
     * @param metadata The operation metadata to update
     * @return Updated metadata with build results
     * 
     * Example flow:
     * 1. LakeServiceImpl calls with target="z/y/test_lake"
     * 2. BuildOrchestrator converts to lakeRelativeTarget="."
     * 3. This method receives lakeRoot="/var/proto-lake/z/y/test_lake" and target="."
     * 4. Bazel runs: cd /var/proto-lake/z/y/test_lake && bazel build //...
     */
    public BuildOperationMetadata buildTarget(Path lakeRoot, String target, RunBuildConfig config, BuildOperationMetadata metadata) throws IOException {
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
                clean(lakeRoot);
                logs.add("Cleaned bazel cache");
            }
            
            // Check if we should isolate bundle builds
            if (config.getIsolateBundleBuilds()) {
                LOG.infof("Building bundles individually for target: %s", target);
                metadata = buildBundlesIndividually(lakeRoot, target, config, metadata, buildStatus, logs);
            } else {
                // Direct build of the specified target
                LOG.infof("Building target directly: %s", target);
                metadata = buildTargetDirectly(lakeRoot, target, config, metadata, buildStatus, logs);
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
     * @param lakeRoot The lake root directory (where MODULE.bazel exists)
     * @param targetPath The relative path from lake root
     * @param config Build configuration
     * @param metadata Operation metadata
     * @param buildStatus Build status builder
     * @param logs Log accumulator
     * @return Updated metadata
     */
    private BuildOperationMetadata buildBundlesIndividually(Path lakeRoot, String targetPath, 
                                                           RunBuildConfig config, BuildOperationMetadata metadata, 
                                                           PhaseStatus.Builder buildStatus, List<String> logs) throws IOException {
        LOG.infof("Discovering bundles under path: %s", targetPath);
        buildStatus.setSubPhase("Discovering bundle targets");
        logs.add("Discovering bundle targets...");

        List<String> bundleTargets = getBundleTargetPathsForTargetPath(lakeRoot, targetPath, metadata);

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
                String targetLog = buildTargetDirectlyImpl(lakeRoot, bazelTarget, config);
                
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
        // Special case: "." means build everything in the current directory
        if (".".equals(bundlePath)) {
            // Build all targets in the repository
            // Note: We'll handle exclusions in buildBazelArgs method
            return "//...";
        }
        // Normal case: convert path to Bazel target
        return "//" + bundlePath + "/...";
    }

    private List<String> getBundleTargetPathsForTargetPath(Path lakeRoot, String targetPath, BuildOperationMetadata metadata) throws IOException {
        // Convert target path to absolute path for discovery
        Path searchPath = lakeRoot.resolve(targetPath);

        // Discover bundles under the target path
        List<Bundle> bundles = bundleDiscoveryService.discoverBundles(searchPath);

        // Get the lake from metadata
        if (!metadata.hasLake()) {
            throw new IOException("Lake not set in build metadata");
        }
        Lake lake = metadata.getLake();

        // Get bundle paths relative to the lake root
        // Since we're running Bazel from the lake directory, we use lake-relative paths
        List<String> bundleTargets = new ArrayList<>();
        for (Bundle bundle : bundles) {
            String lakeRelativePath = BundleUtil.getLakeRootRelativePath(bundle);
            bundleTargets.add(lakeRelativePath);
        }
        return bundleTargets;
    }

    /**
     * Builds a specific target directly.
     * 
     * @param lakeRoot The lake root directory (where MODULE.bazel exists)
     * @param targetPath The relative path from lake root
     * @param config Build configuration
     * @param metadata Operation metadata
     * @param buildStatus Build status builder
     * @param logs Log accumulator
     * @return Updated metadata
     */
    private BuildOperationMetadata buildTargetDirectly(Path lakeRoot, String targetPath, RunBuildConfig config,
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
            String targetLog = buildTargetDirectlyImpl(lakeRoot, bazelTarget, config);
            
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
     * @param lakeRoot The lake root directory to run bazel from
     * @param bazelTarget The full Bazel target (e.g., "//...")
     * @param config Build configuration
     * @return Build output logs
     */
    private String buildTargetDirectlyImpl(Path lakeRoot, String bazelTarget, RunBuildConfig config) throws IOException {
        List<String> args = buildBazelArgs("build", bazelTarget, config);
        
        try {
            // Run bazel build and capture output
            String logOutput = bazelCommand.runWithOutput(lakeRoot, args.toArray(new String[0]));
            
            LOG.infof("Successfully built target: %s", bazelTarget);

            return logOutput;
        } catch (Exception e) {
            throw new IOException("Bazel build failed for target " + bazelTarget + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds bazel command arguments with configuration-specific options.
     */
    private List<String> buildBazelArgs(String command, String target, RunBuildConfig config) {
        List<String> args = new ArrayList<>();
        args.add(command);
        args.add(target);
        
        // If building all targets, exclude .aspect_rules_js
        // In Bazel 8, we need to pass exclusions as separate arguments
        if ("//...".equals(target)) {
            args.add("-//.aspect_rules_js/...");
        }
        
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
        if (enableRemoteCache && remoteCacheUrl.isPresent() && !remoteCacheUrl.get().isEmpty()) {
            args.add("--remote_cache=" + remoteCacheUrl.get());
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
