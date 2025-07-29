package io.vdp.protolake.pipeline;

import io.vdp.protolake.model.ValidationResult;
import io.vdp.protolake.model.ValidationErrors;
import io.vdp.protolake.operation.CancellationToken;
import io.vdp.protolake.operation.InMemoryOperationManager;
import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.git.BranchVersionManager;
import io.vdp.protolake.util.git.GitCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import com.google.protobuf.Timestamp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the build pipeline for lakes and bundles.
 * 
 * Coordinates the execution of gazelle, validation, bazel build, and publishing
 * based on the pipeline configuration. Each runner is responsible for checking
 * its own enabled state from the configuration.
 * 
 * All builds are executed from the base workspace path, with targets computed
 * relative to that workspace root.
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
    BranchVersionManager branchVersionManager;
    
    @Inject
    InMemoryOperationManager operationManager;

    /**
     * Builds a specific target asynchronously.
     * This is the unified method that handles both lake-wide and bundle-specific builds.
     */
    public void buildTargetAsync(Lake lake, String target, BuildPipelineConfig pipelineConfig,
                                String operationName, CancellationToken cancellationToken) {
        CompletableFuture.runAsync(() -> {
            try {
                // Get initial metadata
                BuildOperationMetadata metadata = operationManager.get(operationName);
                if (metadata == null) {
                    LOG.errorf("Operation %s not found", operationName);
                    return;
                }

                buildTarget(lake, target, pipelineConfig, operationName, cancellationToken);
                // Success will be reported by buildTarget
            } catch (CancellationToken.CancellationException e) {
                LOG.infof("Build cancelled for target %s in lake %s", target, 
                    LakeUtil.extractLakeId(lake.getName()));
                // Cancellation already handled by operation manager
            } catch (Exception e) {
                LOG.errorf(e, "Build failed for target %s in lake %s", target, 
                    LakeUtil.extractLakeId(lake.getName()));
                operationManager.failOperation(operationName, "Build failed: " + e.getMessage());
            }
        }, executorService);
    }
    
    /**
     * Unified method to build any Bazel target.
     * 
     * @param lake The lake containing the target
     * @param target The Bazel target to build (e.g., path relative to workspace root)
     * @param pipelineConfig The pipeline configuration
     * @param operationId The operation ID for tracking
     * @param cancellationToken Token for cancellation
     */
    public void buildTarget(Lake lake, String target, BuildPipelineConfig pipelineConfig, 
                           String operationId, CancellationToken cancellationToken) throws Exception {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Starting build for target %s in lake %s", target, lakeName);

        // Get current metadata
        BuildOperationMetadata metadata = operationManager.get(operationId);
        
        // Run gazelle - runner will check if enabled and manage its own phase status
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.RUNNING_GAZELLE)
            .build();
        operationManager.updateMetadata(operationId, metadata);

        // check cancellation before running gazelle
        cancellationToken.throwIfCancelled();
        
        metadata = gazelleRunner.runForLake(lake, pipelineConfig.getGazelle(), metadata);
        operationManager.updateMetadata(operationId, metadata);

        // Run validation - runner will check if enabled and manage its own phase status
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.VALIDATING)
            .build();
        operationManager.updateMetadata(operationId, metadata);

        // check cancellation before running validation
        cancellationToken.throwIfCancelled();
        
        ValidationResult validationResult = validationRunner.validateLake(lake, pipelineConfig.getValidation(), metadata);
        metadata = validationResult.getMetadata();
        operationManager.updateMetadata(operationId, metadata);
        
        if (!validationResult.isSuccess()) {
            throw new BuildException("Validation failed", validationResult.getErrors());
        }

        // Run build (and publish) - runner will check if enabled and manage its own phase status
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.BUILDING)
            .build();
        operationManager.updateMetadata(operationId, metadata);

        // check cancellation before running bazel build
        cancellationToken.throwIfCancelled();

        LOG.infof("Building target: %s", target);

        // Get the workspace root - this is always the base path
        Path workspaceRoot = Paths.get(basePath);
        
        // Run the build with the target path (not a full Bazel target)
        metadata = bazelBuildRunner.buildTarget(workspaceRoot, target, pipelineConfig.getBuild(), metadata);
        operationManager.updateMetadata(operationId, metadata);

        // Mark as complete
        metadata = metadata.toBuilder()
            .setCurrentPhase(OperationPhase.COMPLETED)
            .build();
        operationManager.updateMetadata(operationId, metadata);
        
        // Create final response
        BuildResponse response = createBuildResponse(metadata);
        operationManager.completeOperation(operationId, response);

        LOG.infof("Build completed for target %s in lake %s", target, lakeName);
    }
    
    /**
     * Creates a BuildResponse from the completed operation metadata.
     * This method encapsulates the build-specific logic for creating responses.
     * 
     * @param metadata The completed build operation metadata
     * @return The final BuildResponse
     */
    private BuildResponse createBuildResponse(BuildOperationMetadata metadata) {
        // Calculate summary
        BuildSummary summary = createBuildSummary(metadata);
        
        // Determine overall status
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
    
    /**
     * Creates a build summary from the operation metadata.
     */
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
                    // Collect published artifacts
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
    
    /**
     * Determines the overall build status based on phase statuses and target results.
     */
    private BuildResponse.OverallStatus determineOverallStatus(BuildOperationMetadata metadata, BuildSummary summary) {
        if (metadata.getCurrentPhase() == OperationPhase.CANCELLED) {
            return BuildResponse.OverallStatus.CANCELLED;
        }
        
        // Check if any critical phase failed
        PhaseStatuses phases = metadata.getPhaseStatuses();
        if ((phases.hasGazelle() && phases.getGazelle().getStatus() == PhaseStatus.Status.FAILED) ||
            (phases.hasValidation() && phases.getValidation().getStatus() == PhaseStatus.Status.FAILED)) {
            return BuildResponse.OverallStatus.FAILED;
        }
        
        // Check target results
        if (summary.getFailedTargets() > 0) {
            if (summary.getSuccessfulTargets() > 0) {
                return BuildResponse.OverallStatus.PARTIAL_SUCCESS;
            } else {
                return BuildResponse.OverallStatus.FAILED;
            }
        }
        
        return BuildResponse.OverallStatus.SUCCEEDED;
    }
    
    /**
     * Formats an artifact into a string representation.
     */
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

    /**
     * Gets the current git branch for a lake.
     */
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

    /**
     * Gets the default pipeline configuration with all steps enabled.
     */
    public static final BuildPipelineConfig getDefaultPipelineConfig() {
        return BuildPipelineConfig.newBuilder()
            .setGazelle(RunGazelleConfig.newBuilder()
                .setEnabled(true)
                .build())
            .setValidation(RunValidationConfig.newBuilder()
                .setEnabled(true)
                .setChecks(ValidationChecks.newBuilder()
                    .setCompilation(true)
                    .setLint(true)
                    .setBreaking(true)
                    .setFormat(false)
                    .build())
                .build())
            .setBuild(RunBuildConfig.newBuilder()
                .setEnabled(true)
                .setClean(false)
                .build())
            .setPublish(RunPublishConfig.newBuilder()
                .setEnabled(true)
                .build())
            .build();
    }

    /**
     * Custom exception for build failures.
     */
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
