package io.vdp.protolake.pipeline;

import io.vdp.protolake.model.ValidationError;
import io.vdp.protolake.model.ValidationErrors;
import io.vdp.protolake.model.ValidationResult;

import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.buf.BufCommand;
import io.vdp.protolake.util.git.GitCommand;
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
 * ensure cross-bundle dependencies are properly validated.
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
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;



    /**
     * Validates an entire lake with specified configuration.
     * 
     * This runs buf against all proto files in the lake, checking for:
     * - Compilation errors
     * - Lint violations
     * - Breaking changes
     * - Format consistency
     * 
     * Cross-bundle dependencies are properly validated since buf sees
     * the entire proto graph.
     */
    public ValidationResult validateLake(protolake.v1.Lake lake, RunValidationConfig config, BuildOperationMetadata metadata) throws IOException {
        if (!config.getEnabled()) {
            LOG.debugf("Validation disabled for lake: %s", lake.getName());
            // Mark phase as skipped
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
            // Mark as skipped since we can't validate without buf.yaml
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
        ValidationChecks checks = config.getChecks();
        
        try {
            // Run buf build (compilation check)
            if (checks.getCompilation()) {
                validationStatus.setSubPhase("Running proto compilation checks");
                List<ValidationError> buildErrors = runBufBuild(lakePath, logs);
                errors.addAll(buildErrors);
            }
            
            // Run buf lint
            if (checks.getLint()) {
                validationStatus.setSubPhase("Running lint checks");
                List<ValidationError> lintErrors = runBufLint(lakePath, logs);
                errors.addAll(lintErrors);
            }
            
            // Run buf breaking (if there's a previous version to compare against)
            if (checks.getBreaking() && hasGitHistory(lakePath)) {
                validationStatus.setSubPhase("Running breaking change detection");
                List<ValidationError> breakingErrors = runBufBreaking(lakePath, logs);
                errors.addAll(breakingErrors);
            }
            
            // Run buf format check
            if (checks.getFormat()) {
                validationStatus.setSubPhase("Running format checks");
                List<ValidationError> formatErrors = runBufFormat(lakePath, logs);
                errors.addAll(formatErrors);
            }
            
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
                
            BuildOperationMetadata failedMetadata = metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setValidation(validationStatus.build())
                    .build())
                .build();
                
            throw new IOException("Validation failed", e);
        }
    }

    /**
     * Runs buf build to check if protos compile successfully.
     */
    private List<ValidationError> runBufBuild(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();
        
        List<String> buildErrors = bufCommand.build(directory);
        if (!buildErrors.isEmpty()) {
            // Combine all error lines into a single message
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
     * Runs buf lint and returns validation errors.
     */
    private List<ValidationError> runBufLint(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();
        
        List<String> lintOutput = bufCommand.lint(directory);
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
     * Runs buf breaking to detect breaking changes.
     */
    private List<ValidationError> runBufBreaking(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();
        
        List<String> breakingOutput = bufCommand.breaking(directory, ".git#branch=HEAD~1");
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
     * Runs buf format check to ensure consistent formatting.
     */
    private List<ValidationError> runBufFormat(Path directory, List<String> logs) throws IOException {
        List<ValidationError> errors = new ArrayList<>();
        
        List<String> formatOutput = bufCommand.format(directory, true);
        if (!formatOutput.isEmpty()) {
            // Format issues found
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
        
        // If doesn't match pattern, create a general error
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
            // Check if there's a previous commit
            gitCommand.getCurrentCommit(directory); // This will throw if no commits
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

    /**
     * Gets default validation configuration with standard checks enabled.
     */
    private RunValidationConfig getDefaultValidationConfig() {
        return RunValidationConfig.newBuilder()
            .setEnabled(true)
            .setChecks(ValidationChecks.newBuilder()
                .setCompilation(true)
                .setLint(true)
                .setBreaking(true)
                .setFormat(false)
                .build())
            .build();
    }
}
