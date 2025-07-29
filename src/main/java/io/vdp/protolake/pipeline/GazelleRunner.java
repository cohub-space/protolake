package io.vdp.protolake.pipeline;

import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.bazel.BazelCommand;
import io.vdp.protolake.util.gazelle.GazelleCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;

import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs Gazelle to generate and update BUILD.bazel files across the entire lake.
 * 
 * Gazelle automatically generates Bazel BUILD files by analyzing proto imports
 * and dependencies. It operates at the lake (workspace) level to ensure consistent
 * dependency resolution across all bundles.
 */
@ApplicationScoped
public class GazelleRunner {
    private static final Logger LOG = Logger.getLogger(GazelleRunner.class);

    @Inject
    GazelleCommand gazelleCommand;

    @Inject
    BazelCommand bazelCommand;
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;



    /**
     * Runs Gazelle for an entire lake with specified configuration.
     * 
     * This analyzes all proto files in the lake and generates appropriate BUILD files,
     * ensuring proper dependency resolution across bundles.
     * 
     * @return Updated metadata with gazelle phase results
     */
    public BuildOperationMetadata runForLake(protolake.v1.Lake lake, RunGazelleConfig config, BuildOperationMetadata metadata) throws IOException {
        if (!config.getEnabled()) {
            LOG.debugf("Gazelle disabled for lake: %s", lake.getName());
            // Mark phase as skipped
            PhaseStatus skipped = PhaseStatus.newBuilder()
                .setStatus(PhaseStatus.Status.SKIPPED)
                .build();
            return metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setGazelle(skipped)
                    .build())
                .build();
        }

        LOG.infof("Running Gazelle for lake: %s", lake.getName());

        // First ensure gazelle target exists
        Path lakeRoot = LakeUtil.getLocalPath(lake, basePath);
        if (!Files.exists(lakeRoot.resolve("BUILD.bazel"))) {
            throw new IOException("Lake not properly initialized - missing root BUILD.bazel");
        }

        // Initialize gazelle phase status
        PhaseStatus.Builder gazelleStatus = PhaseStatus.newBuilder()
            .setStatus(PhaseStatus.Status.RUNNING)
            .setStartTime(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(java.time.Instant.now().getEpochSecond())
                .build());
        
        List<String> logs = new ArrayList<>();
        
        try {
            // Run the gazelle wrapper which includes import fixing
            LOG.debugf("Running gazelle wrapper for lake: %s", lake.getName());
            gazelleStatus.setSubPhase("Running gazelle wrapper");
            
            // Use runWithOutput to capture logs
            String logOutput = bazelCommand.runWithOutput(lakeRoot, "run", "//tools:gazelle_wrapper");
            logs.add(logOutput);
            
            LOG.infof("Gazelle completed successfully for lake: %s", lake.getName());
            
            // Mark as successful
            gazelleStatus
                .setStatus(PhaseStatus.Status.SUCCEEDED)
                .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(java.time.Instant.now().getEpochSecond())
                    .build())
                .addAllLogLines(logs);
                
        } catch (Exception e) {
            // Fallback to standard gazelle if wrapper fails
            LOG.warnf("Gazelle wrapper failed, trying standard gazelle: %s", e.getMessage());
            logs.add("Gazelle wrapper failed: " + e.getMessage());
            
            try {
                // Run standard gazelle
                gazelleStatus.setSubPhase("Running standard gazelle");
                gazelleCommand.run(lakeRoot);
                
                // Run import fixer separately
                gazelleStatus.setSubPhase("Running import fixer");
                runImportFixer(lakeRoot);
                
                // Run protolake gazelle for bundle detection
                gazelleStatus.setSubPhase("Running protolake-gazelle");
                runProtolakeGazelle(lakeRoot);
                
                gazelleStatus
                    .setStatus(PhaseStatus.Status.SUCCEEDED)
                    .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(java.time.Instant.now().getEpochSecond())
                        .build())
                    .addAllLogLines(logs);
            } catch (Exception fallbackError) {
                // Mark as failed
                gazelleStatus
                    .setStatus(PhaseStatus.Status.FAILED)
                    .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(java.time.Instant.now().getEpochSecond())
                        .build())
                    .setErrorMessage("Gazelle failed: " + fallbackError.getMessage())
                    .addAllLogLines(logs);
                    
                // Update metadata before throwing
                metadata = metadata.toBuilder()
                    .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                        .setGazelle(gazelleStatus.build())
                        .build())
                    .build();
                    
                throw new IOException("Gazelle failed", fallbackError);
            }
        }
        
        // Return updated metadata
        return metadata.toBuilder()
            .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                .setGazelle(gazelleStatus.build())
                .build())
            .build();
    }

    /**
     * Runs the import fixer to update proto imports for Bazel 8.
     */
    private void runImportFixer(Path workspaceRoot) {
        try {
            Path fixerScript = workspaceRoot.resolve("tools/fix_proto_imports.py");
            if (Files.exists(fixerScript)) {
                LOG.debugf("Running import fixer at: %s", workspaceRoot);
                
                ProcessBuilder pb = new ProcessBuilder("python3", fixerScript.toString());
                pb.directory(workspaceRoot.toFile());
                pb.inheritIO();
                
                Process process = pb.start();
                int exitCode = process.waitFor();
                
                if (exitCode != 0) {
                    LOG.warnf("Import fixer exited with code: %d", exitCode);
                }
            } else {
                LOG.debugf("Import fixer script not found, skipping");
            }
        } catch (Exception e) {
            LOG.warnf("Failed to run import fixer: %s", e.getMessage());
        }
    }

    /**
     * Runs protolake-specific gazelle for bundle detection.
     */
    private void runProtolakeGazelle(Path workspaceRoot) {
        try {
            LOG.debugf("Running protolake gazelle for bundle detection");
            bazelCommand.run(workspaceRoot, "run", "//:gazelle-protolake");
        } catch (Exception e) {
            LOG.warnf("Failed to run protolake gazelle: %s", e.getMessage());
        }
    }
}
