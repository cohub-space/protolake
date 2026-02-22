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
     * Runs Gazelle for an entire lake.
     *
     * This analyzes all proto files in the lake and generates appropriate BUILD files,
     * ensuring proper dependency resolution across bundles.
     *
     * @return Updated metadata with gazelle phase results
     */
    public BuildOperationMetadata runForLake(protolake.v1.Lake lake, BuildOperationMetadata metadata) throws IOException {
        LOG.infof("Running Gazelle for lake: %s", lake.getName());

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
            // Run the gazelle wrapper (standard + protolake passes)
            LOG.debugf("Running gazelle wrapper for lake: %s", lake.getName());
            gazelleStatus.setSubPhase("Running gazelle wrapper");

            String logOutput = bazelCommand.runWithOutput(lakeRoot, "run", "//tools:gazelle_wrapper");
            logs.add(logOutput);

            LOG.infof("Gazelle completed successfully for lake: %s", lake.getName());

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
                gazelleStatus.setSubPhase("Running standard gazelle");
                gazelleCommand.run(lakeRoot);

                gazelleStatus.setSubPhase("Running protolake-gazelle");
                runProtolakeGazelle(lakeRoot);

                gazelleStatus
                    .setStatus(PhaseStatus.Status.SUCCEEDED)
                    .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(java.time.Instant.now().getEpochSecond())
                        .build())
                    .addAllLogLines(logs);
            } catch (Exception fallbackError) {
                gazelleStatus
                    .setStatus(PhaseStatus.Status.FAILED)
                    .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(java.time.Instant.now().getEpochSecond())
                        .build())
                    .setErrorMessage("Gazelle failed: " + fallbackError.getMessage())
                    .addAllLogLines(logs);

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
     * Runs protolake-specific gazelle for bundle detection.
     */
    private void runProtolakeGazelle(Path lakeRoot) {
        try {
            LOG.debugf("Running protolake gazelle for bundle detection");
            bazelCommand.run(lakeRoot, "run", "//:gazelle-protolake");
        } catch (Exception e) {
            LOG.warnf("Failed to run protolake gazelle: %s", e.getMessage());
        }
    }
}
