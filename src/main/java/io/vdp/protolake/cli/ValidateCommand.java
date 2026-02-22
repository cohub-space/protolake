package io.vdp.protolake.cli;

import io.vdp.protolake.model.ValidationResult;
import io.vdp.protolake.pipeline.ValidationRunner;
import io.vdp.protolake.util.LakeUtil;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;
import protolake.v1.BuildOperationMetadata;
import protolake.v1.Lake;

import java.nio.file.Path;

@Dependent
@CommandLine.Command(name = "validate", description = "Run buf lint/breaking checks on the lake")
public class ValidateCommand implements Runnable {

    @CommandLine.Option(names = "--lake-path", description = "Path to the lake directory (default: auto-detect)")
    String lakePath;

    @Inject
    LakeResolver lakeResolver;

    @Inject
    ValidationRunner validationRunner;

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Override
    public void run() {
        try {
            Path resolvedPath = resolveLakePath();
            Lake lake = lakeResolver.resolveLake(resolvedPath);

            System.out.println("[protolake] Running validation...");

            BuildOperationMetadata emptyMetadata = BuildOperationMetadata.getDefaultInstance();
            ValidationResult result = validationRunner.validateLake(lake, false, emptyMetadata);

            if (result.isSuccess()) {
                System.out.println("[protolake] Validation passed.");
            } else {
                System.err.println("[protolake] Validation failed:");
                for (var error : result.getErrors().getErrors()) {
                    System.err.printf("  %s:%d:%d: %s%n",
                            error.getFile(), error.getLine(), error.getColumn(), error.getMessage());
                }
                throw new CommandLine.ExecutionException(
                        new CommandLine(this), "Validation failed");
            }
        } catch (CommandLine.ExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Validation failed: " + e.getMessage(), e);
        }
    }

    private Path resolveLakePath() {
        if (lakePath != null) {
            return Path.of(lakePath);
        }
        // In Docker: PROTO_LAKE_BASE_PATH is set, scan for lake.yaml
        // Locally: use current directory
        return findLakeRoot();
    }

    private Path findLakeRoot() {
        // Scan basePath for directories containing lake.yaml
        Path base = Path.of(basePath);
        try {
            var paths = java.nio.file.Files.walk(base, 3)
                    .filter(p -> p.getFileName().toString().equals("lake.yaml"))
                    .map(Path::getParent)
                    .toList();
            if (paths.size() == 1) {
                return paths.get(0);
            } else if (paths.isEmpty()) {
                throw new IllegalStateException("No lake.yaml found under " + basePath);
            } else {
                throw new IllegalStateException(
                        "Multiple lakes found under " + basePath + ". Use --lake-path to specify.");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to scan for lake: " + e.getMessage(), e);
        }
    }
}
