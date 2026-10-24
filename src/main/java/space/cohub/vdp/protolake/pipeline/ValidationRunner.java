package space.cohub.vdp.protolake.pipeline;

import space.cohub.vdp.protolake.initializer.CohubApiModule;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.model.ValidationErrors;
import space.cohub.vdp.protolake.model.ValidationResult;

import space.cohub.vdp.protolake.util.LakeUtil;
import space.cohub.vdp.protolake.util.buf.BufCommand;
import space.cohub.vdp.protolake.util.git.GitCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import com.google.protobuf.Timestamp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs buf validation on proto files across the entire lake.
 *
 * Buf provides compilation checking, linting, breaking change detection,
 * and format checking for proto files. It operates at the lake level to
 * ensure cross-bundle dependencies are properly validated. A lake that
 * imports a proto the protolake image provides (CohubApiModule) is checked
 * on a copy that holds that proto too (ProvidedProtoOverlay), since buf
 * resolves imports only from the lake and its buf.yaml deps.
 */
@ApplicationScoped
public class ValidationRunner {
    private static final Logger LOG = Logger.getLogger(ValidationRunner.class);

    // Pattern to parse buf lint output
    private static final Pattern BUF_LINT_PATTERN = Pattern.compile(
        "^(.+?):(\\d+):(\\d+):(.+)$"
    );

    @Inject
    BufCommand bufCommand;

    @Inject
    GitCommand gitCommand;

    @Inject
    CohubApiModule cohubApiModule;

    /** The branch a lake's breaking-change check measures from. */
    static final String BREAKING_BASE_REF = "origin/main";

    static final String NO_BASELINE_MESSAGE = "Breaking-change check skipped: this checkout has neither "
        + BREAKING_BASE_REF + " nor a previous commit to compare against (a shallow clone) — fetch main "
        + "to get the check";

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    /**
     * Validates an entire lake.
     *
     * When skipValidation is true, all checks are skipped and the phase is marked as SKIPPED.
     * Otherwise, runs buf against all proto files in the lake, checking for:
     * - Compilation errors
     * - Lint violations
     * - Breaking changes
     * - Format consistency (warnings only)
     */
    public ValidationResult validateLake(protolake.v1.Lake lake, boolean skipValidation, BuildOperationMetadata metadata) throws IOException {
        if (skipValidation) {
            LOG.debugf("Validation skipped for lake: %s", lake.getName());
            PhaseStatus skipped = PhaseStatus.newBuilder()
                .setStatus(PhaseStatus.Status.SKIPPED)
                .build();
            BuildOperationMetadata updatedMetadata = metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setValidation(skipped)
                    .build())
                .build();
            return ValidationResult.newBuilder()
                .setSuccess(true)
                .setMetadata(updatedMetadata)
                .build();
        }

        LOG.infof("Running validation for lake: %s", lake.getName());

        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        if (!Files.exists(lakePath.resolve("buf.yaml"))) {
            LOG.warnf("No buf.yaml found for lake: %s, skipping validation", lake.getName());
            PhaseStatus skipped = PhaseStatus.newBuilder()
                .setStatus(PhaseStatus.Status.SKIPPED)
                .build();
            BuildOperationMetadata updatedMetadata = metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setValidation(skipped)
                    .build())
                .build();
            return ValidationResult.newBuilder()
                .setSuccess(true)
                .setMetadata(updatedMetadata)
                .build();
        }

        // Initialize validation phase status
        PhaseStatus.Builder validationStatus = PhaseStatus.newBuilder()
            .setStatus(PhaseStatus.Status.RUNNING)
            .setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());

        List<String> logs = new ArrayList<>();
        List<ValidationError> errors = new ArrayList<>();
        ProvidedProtoOverlay overlay = null;

        try {
            List<String> provided = ProvidedProtoOverlay.neededImports(lakePath, CohubApiModule.IMPORT_PATHS);
            if (!provided.isEmpty()) {
                overlay = ProvidedProtoOverlay.create(lakePath, cohubApiModule.path(), provided);
                logs.add("buf checks a copy of the lake's protos that also holds " + String.join(", ", provided)
                    + ", which the protolake image provides");
            }
            Path bufDir = overlay == null ? lakePath : overlay.root();
            List<String> excluded = overlay == null ? List.of() : overlay.providedPaths();

            // Resolve buf module dependencies (googleapis, protovalidate, etc.)
            validationStatus.setSubPhase("Resolving proto dependencies");
            bufCommand.modUpdate(bufDir);
            if (overlay != null) {
                overlay.copyLockBack();
            }

            // Run buf build (compilation check)
            validationStatus.setSubPhase("Running proto compilation checks");
            List<ValidationError> buildErrors = runBufBuild(bufDir, logs);
            errors.addAll(buildErrors);

            // Run buf lint
            validationStatus.setSubPhase("Running lint checks");
            List<ValidationError> lintErrors = runBufLint(bufDir, excluded, logs);
            errors.addAll(lintErrors);

            // Run buf breaking (if there's a previous version to compare against)
            if (hasGitHistory(lakePath)) {
                validationStatus.setSubPhase("Running breaking change detection");
                List<ValidationError> breakingErrors = runBufBreaking(lakePath, logs);
                errors.addAll(breakingErrors);
            }

            // Run buf format check (warnings only)
            validationStatus.setSubPhase("Running format checks");
            List<ValidationError> formatErrors = runBufFormat(lakePath, logs);
            errors.addAll(formatErrors);

            // Update status based on results
            boolean hasErrors = errors.stream()
                .anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR);

            if (!hasErrors) {
                validationStatus
                    .setStatus(PhaseStatus.Status.SUCCEEDED)
                    .setEndTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build());
            } else {
                validationStatus
                    .setStatus(PhaseStatus.Status.FAILED)
                    .setEndTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build());
                String errorSummary = String.format("Validation failed with %d errors",
                    errors.stream().filter(e -> e.getSeverity() == ValidationError.Severity.ERROR).count());
                validationStatus.setErrorMessage(errorSummary);
            }

            validationStatus.addAllLogLines(logs);

            // Create updated metadata
            BuildOperationMetadata updatedMetadata = metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setValidation(validationStatus.build())
                    .build())
                .build();

            return buildValidationResult(errors, updatedMetadata);

        } catch (Exception e) {
            // Mark validation as failed
            validationStatus
                .setStatus(PhaseStatus.Status.FAILED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .setErrorMessage("Validation failed: " + e.getMessage())
                .addAllLogLines(logs);

            throw new IOException("Validation failed", e);
        } finally {
            if (overlay != null) {
                closeQuietly(overlay);
            }
        }
    }

    /**
     * Runs buf build to check if protos compile successfully.
     */
    private List<ValidationError> runBufBuild(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();

        List<String> buildErrors = bufCommand.build(directory);
        if (!buildErrors.isEmpty()) {
            String errorMessage = String.join("\n", buildErrors);
            logs.add("Compilation errors found:\n" + errorMessage);
            errors.add(ValidationError.newBuilder()
                .setType(ValidationError.Type.SYNTAX)
                .setMessage(errorMessage)
                .setSeverity(ValidationError.Severity.ERROR)
                .build());
        }

        LOG.debugf("Buf build found %d compilation issues", errors.size());
        return errors;
    }

    /**
     * Runs buf lint and returns validation errors; excluded files are the
     * provided protos, which are not the lake's to lint.
     */
    private List<ValidationError> runBufLint(Path directory, List<String> excluded, List<String> logs)
            throws IOException {
        List<ValidationError> errors = new ArrayList<>();

        List<String> lintOutput = bufCommand.lint(directory, excluded);
        for (String line : lintOutput) {
            ValidationError error = parseBufLintLine(line);
            if (error != null) {
                errors.add(error);
            }
        }

        LOG.debugf("Buf lint found %d issues", errors.size());
        if (!errors.isEmpty()) {
            logs.add(String.format("Lint validation found %d issues", errors.size()));
        }
        return errors;
    }

    /**
     * Runs buf breaking to detect breaking changes. When the lake or the
     * commit it is measured from imports a provided proto it does not hold,
     * buf compares directories that both hold it, the baseline the image's
     * copy (ProvidedProtoOverlay#breakingInputs): the commit's git input alone
     * could not resolve the import. buf's {@code --exclude-path} cannot leave
     * the provided protos out instead, since buf rejects the path for an
     * {@code --against} directory outside the input.
     */
    private List<ValidationError> runBufBreaking(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();

        java.util.Optional<String> baseline = breakingBaseSha(directory);
        if (baseline.isEmpty()) {
            LOG.warn(NO_BASELINE_MESSAGE);
            logs.add(NO_BASELINE_MESSAGE);
            errors.add(ValidationError.newBuilder()
                .setType(ValidationError.Type.BREAKING_CHANGE)
                .setMessage(NO_BASELINE_MESSAGE)
                .setSeverity(ValidationError.Severity.WARNING)
                .build());
            return errors;
        }
        List<String> breakingOutput;
        java.util.Optional<ProvidedProtoOverlay.BreakingInputs> provided = ProvidedProtoOverlay.breakingInputs(
            directory, gitCommand, baseline.get(), cohubApiModule.path(), CohubApiModule.IMPORT_PATHS);
        if (provided.isEmpty()) {
            breakingOutput = bufCommand.breaking(directory, gitRefInput(baseline.get()));
        } else {
            ProvidedProtoOverlay.BreakingInputs inputs = provided.get();
            try {
                logs.add("buf breaking compares against " + baseline.get() + ", both sides holding "
                    + String.join(", ", inputs.providedPaths()) + " (the baseline the protolake image's copy)");
                breakingOutput = bufCommand.breaking(inputs.input(), inputs.against().toString());
            } finally {
                closeQuietly(inputs);
            }
        }
        for (String line : breakingOutput) {
            if (!line.trim().isEmpty()) {
                errors.add(ValidationError.newBuilder()
                    .setType(ValidationError.Type.BREAKING_CHANGE)
                    .setMessage(line)
                    .setSeverity(ValidationError.Severity.ERROR)
                    .build());
            }
        }

        if (!errors.isEmpty()) {
            LOG.warnf("Buf found %d breaking changes", errors.size());
            logs.add(String.format("Breaking change detection found %d issues", errors.size()));
        }

        return errors;
    }

    /**
     * The commit a branch's protos are compared against. A branch is
     * measured from where it left {@code origin/main} — the merge base —
     * so a breaking change two commits back is still a breaking change;
     * comparing against {@code HEAD~1} alone saw only the last commit and
     * let one through until CI. A checkout without {@code origin/main}
     * (no remote) compares against the previous commit; one with neither
     * (a depth-1 clone) has no baseline, and the caller reports that as a
     * WARNING rather than a clean check.
     */
    java.util.Optional<String> breakingBaseSha(Path directory) {
        java.util.Optional<String> base = gitCommand.mergeBase(directory, BREAKING_BASE_REF);
        if (base.isEmpty()) {
            base = gitCommand.revParse(directory, "HEAD~1");
        }
        return base;
    }

    /** Deletes a temporary copy of the lake; a failure is logged, never fails the validation. */
    private static void closeQuietly(AutoCloseable copy) {
        try {
            copy.close();
        } catch (Exception e) {
            LOG.warnf("Could not delete buf's temporary copy of the lake: %s", e.getMessage());
        }
    }

    /**
     * buf's git input at a commit. It is a {@code ref=} input: buf's
     * {@code branch=} takes a branch name, and {@code branch=HEAD~1} — the
     * previous form — was rejected with "invalid refspec" on every run,
     * which the runner read as a clean result.
     */
    static String gitRefInput(String sha) {
        return ".git#ref=" + sha;
    }

    /**
     * Runs buf format check to ensure consistent formatting.
     */
    private List<ValidationError> runBufFormat(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();

        List<String> formatOutput = bufCommand.format(directory, true);
        if (!formatOutput.isEmpty()) {
            errors.add(ValidationError.newBuilder()
                .setType(ValidationError.Type.LINT)
                .setMessage("Format issues found. Run 'buf format' to fix.")
                .setSeverity(ValidationError.Severity.WARNING)
                .build());
        }

        LOG.debugf("Buf format check found %d issues", errors.size());
        if (!errors.isEmpty()) {
            logs.add("Format check found issues. Run 'buf format' to fix.");
        }
        return errors;
    }

    /**
     * Parses a buf lint output line into a ValidationError.
     */
    private ValidationError parseBufLintLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        Matcher matcher = BUF_LINT_PATTERN.matcher(line);
        if (matcher.matches()) {
            return ValidationError.newBuilder()
                .setFile(matcher.group(1))
                .setLine(Integer.parseInt(matcher.group(2)))
                .setColumn(Integer.parseInt(matcher.group(3)))
                .setMessage(matcher.group(4).trim())
                .setType(ValidationError.Type.LINT)
                .setSeverity(ValidationError.Severity.WARNING)
                .build();
        }

        return ValidationError.newBuilder()
            .setMessage(line)
            .setType(ValidationError.Type.LINT)
            .setSeverity(ValidationError.Severity.WARNING)
            .build();
    }

    /**
     * Checks if directory has git history for breaking change detection.
     */
    private boolean hasGitHistory(Path directory) {
        try {
            gitCommand.getCurrentCommit(directory);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds a ValidationResult from a list of errors and updated metadata.
     */
    private ValidationResult buildValidationResult(List<ValidationError> errors, BuildOperationMetadata metadata) {
        boolean hasErrors = errors.stream()
            .anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR);

        ValidationErrors validationErrors = ValidationErrors.newBuilder()
            .addAllErrors(errors)
            .build();

        return ValidationResult.newBuilder()
            .setSuccess(!hasErrors)
            .setErrors(validationErrors)
            .setMetadata(metadata)
            .build();
    }
}
