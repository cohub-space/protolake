package io.vdp.protolake.pipeline;

import io.vdp.protolake.initializer.WorkspaceInitializer;
import io.vdp.protolake.model.ValidationResult;
import io.vdp.protolake.model.ValidationErrors;
import io.vdp.protolake.operation.CancellationToken;
import io.vdp.protolake.operation.InMemoryOperationManager;
import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.git.GitCommand;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import com.google.protobuf.Timestamp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the build pipeline for lakes and bundles.
 *
 * Coordinates the execution of gazelle, validation, bazel build, and publishing
 * based on simplified boolean flags (skipValidation, installLocal).
 *
 * All builds are executed from the lake root directory (where MODULE.bazel exists),
 * with targets computed relative to that lake root.
 */
@ApplicationScoped
public class BuildOrchestrator {
    private static final Logger LOG = Logger.getLogger(BuildOrchestrator.class);

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Inject
    GazelleRunner gazelleRunner;

    @Inject
    ValidationRunner validationRunner;

    @Inject
    BazelBuildRunner bazelBuildRunner;

    @Inject
    StorageService storageService;

    @Inject
    GitCommand gitCommand;

    @Inject
    InMemoryOperationManager operationManager;

    @Inject
    WorkspaceInitializer workspaceInitializer;

    /**
     * Builds a specific target asynchronously.
     */
    public void buildTargetAsync(Lake lake, String target, boolean skipValidation, boolean installLocal,
                                String operationName, CancellationToken cancellationToken) {
        CompletableFuture.runAsync(() -> {
            try {
                BuildOperationMetadata metadata = operationManager.get(operationName);
                if (metadata == null) {
                    LOG.errorf("Operation %s not found", operationName);
                    return;
                }

                buildTarget(lake, target, skipValidation, installLocal, operationName, cancellationToken);
            } catch (CancellationToken.CancellationException e) {
                LOG.infof("Build cancelled for target %s in lake %s", target,
                    LakeUtil.extractLakeId(lake.getName()));
            } catch (Exception e) {
                LOG.errorf(e, "Build failed for target %s in lake %s", target,
                    LakeUtil.extractLakeId(lake.getName()));
                operationManager.failOperation(operationName, "Build failed: " + e.getMessage());
            }
        }, executorService);
    }

    /**
     * Unified method to build any Bazel target (gRPC mode with operation tracking).
     */
    public void buildTarget(Lake lake, String target, boolean skipValidation, boolean installLocal,
                           String operationId, CancellationToken cancellationToken) throws Exception {
        // Delegate to sync method with an adapter listener that updates operationManager
        buildTargetSync(lake, target, skipValidation, installLocal, cancellationToken,
                new io.vdp.protolake.cli.BuildProgressListener() {
                    @Override
                    public void onPhaseStart(String phase) {
                        // Phase transitions are handled inline below
                    }

                    @Override
                    public void onPhaseComplete(String phase, boolean success, String message) {
                    }

                    @Override
                    public void onMetadataUpdate(BuildOperationMetadata metadata) {
                        operationManager.updateMetadata(operationId, metadata);
                    }

                    @Override
                    public void onBuildComplete(BuildOperationMetadata metadata) {
                        BuildResponse response = createBuildResponse(metadata);
                        operationManager.completeOperation(operationId, response);
                    }

                    @Override
                    public void onBuildFailed(String error) {
                        operationManager.failOperation(operationId, error);
                    }
                }, operationManager.get(operationId));
    }

    /**
     * Synchronous build pipeline usable from both gRPC and CLI modes.
     * Takes a BuildProgressListener for progress reporting instead of an operation ID.
     *
     * @return final BuildOperationMetadata
     */
    public BuildOperationMetadata buildTargetSync(Lake lake, String target,
                                                   boolean skipValidation, boolean installLocal,
                                                   CancellationToken cancellationToken,
                                                   io.vdp.protolake.cli.BuildProgressListener listener,
                                                   BuildOperationMetadata metadata) throws Exception {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Starting build for target %s in lake %s (skipValidation=%s, installLocal=%s)",
            target, lakeName, skipValidation, installLocal);

        // Regenerate workspace files (MODULE.bazel, etc.) to pick up current env vars
        // This ensures the gazelle source path matches the current container environment
        workspaceInitializer.initializeWorkspace(lake);

        // Run gazelle
        listener.onPhaseStart("Running Gazelle");
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.RUNNING_GAZELLE)
            .build();
        listener.onMetadataUpdate(metadata);

        if (cancellationToken != null) cancellationToken.throwIfCancelled();

        metadata = gazelleRunner.runForLake(lake, metadata);
        listener.onMetadataUpdate(metadata);
        listener.onPhaseComplete("Running Gazelle", true, null);

        // Run validation (unless skipped)
        listener.onPhaseStart("Validating");
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.VALIDATING)
            .build();
        listener.onMetadataUpdate(metadata);

        if (cancellationToken != null) cancellationToken.throwIfCancelled();

        ValidationResult validationResult = validationRunner.validateLake(lake, skipValidation, metadata);
        metadata = validationResult.getMetadata();
        listener.onMetadataUpdate(metadata);

        if (!validationResult.isSuccess()) {
            listener.onPhaseComplete("Validating", false, "Validation failed");
            throw new BuildException("Validation failed", validationResult.getErrors());
        }
        listener.onPhaseComplete("Validating", true, null);

        // Run build
        listener.onPhaseStart("Building");
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.BUILDING)
            .build();
        listener.onMetadataUpdate(metadata);

        if (cancellationToken != null) cancellationToken.throwIfCancelled();

        LOG.infof("Building target: %s", target);

        Path lakeRoot = LakeUtil.getLocalPath(lake, basePath);
        String lakeRelativeTarget = LakeUtil.convertToLakeRelativePath(target, lake);

        LOG.debugf("Converting target from base-relative '%s' to lake-relative '%s'", target, lakeRelativeTarget);

        metadata = bazelBuildRunner.buildTarget(lakeRoot, lakeRelativeTarget, metadata);
        listener.onMetadataUpdate(metadata);
        listener.onPhaseComplete("Building", true, null);

        // Run publishing (if installLocal is requested)
        if (installLocal) {
            listener.onPhaseStart("Publishing");
            metadata = metadata.toBuilder()
                .setCurrentPhase(OperationPhase.PUBLISHING)
                .build();
            listener.onMetadataUpdate(metadata);

            if (cancellationToken != null) cancellationToken.throwIfCancelled();

            metadata = bazelBuildRunner.publishLocal(lakeRoot, metadata);
            listener.onMetadataUpdate(metadata);
            listener.onPhaseComplete("Publishing", true, null);
        }

        // Mark as complete
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.COMPLETED)
            .build();
        listener.onMetadataUpdate(metadata);
        listener.onBuildComplete(metadata);

        LOG.infof("Build completed for target %s in lake %s", target, lakeName);
        return metadata;
    }

    private BuildResponse createBuildResponse(BuildOperationMetadata metadata) {
        BuildSummary summary = createBuildSummary(metadata);
        BuildResponse.OverallStatus overallStatus = determineOverallStatus(metadata, summary);

        return BuildResponse.newBuilder()
            .setMetadata(metadata)
            .setStatus(overallStatus)
            .setCompletionTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build())
            .setSummary(summary)
            .build();
    }

    private BuildSummary createBuildSummary(BuildOperationMetadata metadata) {
        int totalTargets = metadata.getTargetBuildsCount();
        int successfulTargets = 0;
        int failedTargets = 0;
        int skippedTargets = 0;
        List<String> publishedArtifacts = new ArrayList<>();
        String firstError = "";

        for (TargetBuildInfo target : metadata.getTargetBuildsMap().values()) {
            switch (target.getStatus()) {
                case PUBLISHED:
                case BUILT:
                    successfulTargets++;
                    for (Artifact artifact : target.getArtifactsMap().values()) {
                        publishedArtifacts.add(formatArtifactString(artifact));
                    }
                    break;
                case FAILED:
                    failedTargets++;
                    if (firstError.isEmpty() && !target.getErrorMessage().isEmpty()) {
                        firstError = target.getErrorMessage();
                    }
                    break;
                case SKIPPED:
                    skippedTargets++;
                    break;
            }
        }

        return BuildSummary.newBuilder()
            .setTotalTargets(totalTargets)
            .setSuccessfulTargets(successfulTargets)
            .setFailedTargets(failedTargets)
            .setSkippedTargets(skippedTargets)
            .addAllPublishedArtifacts(publishedArtifacts)
            .setFirstError(firstError)
            .build();
    }

    private BuildResponse.OverallStatus determineOverallStatus(BuildOperationMetadata metadata, BuildSummary summary) {
        if (metadata.getCurrentPhase() == OperationPhase.CANCELLED) {
            return BuildResponse.OverallStatus.CANCELLED;
        }

        PhaseStatuses phases = metadata.getPhaseStatuses();
        if ((phases.hasGazelle() && phases.getGazelle().getStatus() == PhaseStatus.Status.FAILED) ||
            (phases.hasValidation() && phases.getValidation().getStatus() == PhaseStatus.Status.FAILED) ||
            (phases.hasPublish() && phases.getPublish().getStatus() == PhaseStatus.Status.FAILED)) {
            return BuildResponse.OverallStatus.FAILED;
        }

        if (summary.getFailedTargets() > 0) {
            if (summary.getSuccessfulTargets() > 0) {
                return BuildResponse.OverallStatus.PARTIAL_SUCCESS;
            } else {
                return BuildResponse.OverallStatus.FAILED;
            }
        }

        return BuildResponse.OverallStatus.SUCCEEDED;
    }

    private String formatArtifactString(Artifact artifact) {
        if (artifact.hasMaven()) {
            MavenCoordinates maven = artifact.getMaven();
            return String.format("%s:%s:%s", maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
        } else if (artifact.hasPython()) {
            PythonCoordinates python = artifact.getPython();
            return String.format("%s==%s", python.getPackageName(), python.getVersion());
        } else if (artifact.hasNpm()) {
            NpmCoordinates npm = artifact.getNpm();
            return String.format("%s@%s", npm.getPackageName(), npm.getVersion());
        }
        return "unknown";
    }

    public String getCurrentBranch(String lakeName) {
        try {
            Lake lake = storageService.getLake(lakeName)
                .orElseThrow(() -> new IllegalArgumentException("Lake not found: " + lakeName));
            Path lakePath = LakeUtil.getLocalPath(lake, basePath);
            return gitCommand.getCurrentBranch(lakePath);
        } catch (Exception e) {
            LOG.warnf("Failed to detect git branch for lake %s, defaulting to 'main': %s",
                lakeName, e.getMessage());
            return "main";
        }
    }

    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down build orchestrator");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class BuildException extends Exception {
        private final ValidationErrors errors;

        public BuildException(String message, ValidationErrors errors) {
            super(message);
            this.errors = errors;
        }

        public ValidationErrors getErrors() {
            return errors;
        }
    }
}
