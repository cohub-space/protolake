package space.cohub.vdp.protolake.pipeline;

import space.cohub.vdp.protolake.cli.BuildProgressListener;
import space.cohub.vdp.protolake.initializer.WorkspaceInitializer;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.model.ValidationResult;
import space.cohub.vdp.protolake.model.ValidationErrors;
import space.cohub.vdp.protolake.operation.CancellationToken;
import space.cohub.vdp.protolake.operation.InMemoryOperationManager;
import space.cohub.vdp.protolake.storage.StorageService;
import space.cohub.vdp.protolake.util.LakeUtil;
import space.cohub.vdp.protolake.util.git.GitCommand;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import java.util.HashMap;
import java.util.Map;
import com.google.protobuf.Timestamp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the build pipeline for lakes and bundles.
 *
 * Coordinates the execution of gazelle, validation, bazel build, the API spec
 * step, and publishing based on simplified boolean flags (skipValidation,
 * installLocal).
 *
 * All builds are executed from the lake root directory (where MODULE.bazel exists),
 * with targets computed relative to that lake root.
 */
@ApplicationScoped
public class BuildOrchestrator {
    private static final Logger LOG = Logger.getLogger(BuildOrchestrator.class);

    static final String API_SPECS_PHASE = "Writing API specs";

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
    ApiSpecRunner apiSpecRunner;

    @Inject
    ProvidedImportCheck providedImportCheck;

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
    public void buildTargetAsync(Lake lake, String target, boolean skipValidation, InstallLocalConfig installLocal,
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
    public void buildTarget(Lake lake, String target, boolean skipValidation, InstallLocalConfig installLocal,
                           String operationId, CancellationToken cancellationToken) throws Exception {
        // Delegate to sync method with an adapter listener that updates operationManager
        buildTargetSync(lake, target, skipValidation, installLocal, cancellationToken,
                new space.cohub.vdp.protolake.cli.BuildProgressListener() {
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
                                                   boolean skipValidation, InstallLocalConfig installLocal,
                                                   CancellationToken cancellationToken,
                                                   space.cohub.vdp.protolake.cli.BuildProgressListener listener,
                                                   BuildOperationMetadata metadata) throws Exception {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Starting build for target %s in lake %s (skipValidation=%s, installLocal=%s)",
            target, lakeName, skipValidation, installLocal.getShouldInstall());

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

        // Before Bazel: such a bundle would fail to compile, or publish packages that fail to load.
        List<ValidationError> refused = providedImportCheck.check(lakeRoot, lakeRelativeTarget);
        if (!refused.isEmpty()) {
            metadata = recordOnBuildPhase(metadata,
                refused.stream().map(error -> "ERROR " + describe(error)).toList(), refused);
            listener.onMetadataUpdate(metadata);
            listener.onPhaseComplete("Building", false, refused.size() + " API exposure error(s)");
            throw new BuildException("API exposure checks failed",
                ValidationErrors.newBuilder().addAllErrors(refused).build());
        }

        metadata = bazelBuildRunner.buildTarget(lakeRoot, lakeRelativeTarget, metadata);
        listener.onMetadataUpdate(metadata);
        listener.onPhaseComplete("Building", true, null);

        metadata = writeApiSpecs(lakeRoot, lakeRelativeTarget, metadata, listener, cancellationToken);

        // Run publishing (if installLocal is requested)
        if (installLocal.getShouldInstall()) {
            listener.onPhaseStart("Publishing");
            metadata = metadata.toBuilder()
                .setCurrentPhase(OperationPhase.PUBLISHING)
                .build();
            listener.onMetadataUpdate(metadata);

            if (cancellationToken != null) cancellationToken.throwIfCancelled();

            metadata = bazelBuildRunner.publishLocal(lakeRoot, lakeRelativeTarget, metadata, installLocal);
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

    /**
     * Checks the exposure annotations of the bundles in scope and writes their
     * API specs (ApiSpecRunner). An ERROR finding fails the build here, before
     * anything is published; WARNINGs go to the listener. Both, and the specs
     * written, land in the build phase's log lines for gRPC callers.
     */
    private BuildOperationMetadata writeApiSpecs(Path lakeRoot, String lakeRelativeTarget,
                                                 BuildOperationMetadata metadata,
                                                 BuildProgressListener listener,
                                                 CancellationToken cancellationToken) throws Exception {
        listener.onPhaseStart(API_SPECS_PHASE);
        if (cancellationToken != null) cancellationToken.throwIfCancelled();

        ApiSpecRunner.Result result = apiSpecRunner.run(lakeRoot, lakeRelativeTarget, metadata);
        List<String> logs = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (ApiSpecRunner.BundleSpecs bundle : result.bundles()) {
            if (bundle.specs().isEmpty()) {
                continue;
            }
            String names = bundle.specs().stream()
                .map(spec -> spec.getFileName().toString())
                .collect(Collectors.joining(", "));
            written.add(bundle.bundle().name() + " (" + names + ")");
            logs.add(String.format("API specs for %s: %s in bazel-bin/%s/%s_api_specs%s",
                bundle.bundle().packagePath(), names, bundle.bundle().packagePath(),
                bundle.bundle().name(),
                bundle.asyncApiGenerator().isEmpty() ? "" : "; AsyncAPI by " + bundle.asyncApiGenerator()));
        }
        for (ValidationError warning : result.findings(ValidationError.Severity.WARNING)) {
            listener.onWarning(describe(warning));
            logs.add("WARNING " + describe(warning));
        }
        List<ValidationError> errors = result.findings(ValidationError.Severity.ERROR);
        errors.forEach(error -> logs.add("ERROR " + describe(error)));

        metadata = recordOnBuildPhase(metadata, logs, errors);
        listener.onMetadataUpdate(metadata);

        if (!errors.isEmpty()) {
            listener.onPhaseComplete(API_SPECS_PHASE, false,
                errors.size() + " API exposure error(s)");
            throw new BuildException("API exposure checks failed",
                ValidationErrors.newBuilder().addAllErrors(errors).build());
        }
        listener.onPhaseComplete(API_SPECS_PHASE, true,
            written.isEmpty() ? "no bundle has REST bindings or WebSocket RPCs" : String.join(", ", written));
        return metadata;
    }

    /**
     * Adds log lines to the build phase's status and, when there are errors,
     * marks the phase failed with each one described: gRPC callers read the
     * phase status, while the exception that ends the build reaches them only
     * as its message.
     */
    private static BuildOperationMetadata recordOnBuildPhase(BuildOperationMetadata metadata, List<String> logs,
                                                             List<ValidationError> errors) {
        PhaseStatus.Builder build = metadata.getPhaseStatuses().getBuild().toBuilder().addAllLogLines(logs);
        if (!errors.isEmpty()) {
            build.setStatus(PhaseStatus.Status.FAILED)
                .setErrorMessage("API exposure checks failed:\n" + errors.stream()
                    .map(BuildOrchestrator::describe)
                    .collect(Collectors.joining("\n")));
        }
        return metadata.toBuilder()
            .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder().setBuild(build.build()).build())
            .build();
    }

    /** {@code file:line:column: message}, without the position when it is unknown. */
    static String describe(ValidationError finding) {
        String position = finding.getLine() > 0
            ? String.format(":%d:%d", finding.getLine(), finding.getColumn())
            : "";
        return finding.getFile() + position + ": " + finding.getMessage();
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
