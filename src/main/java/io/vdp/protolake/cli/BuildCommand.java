package io.vdp.protolake.cli;

import io.vdp.protolake.pipeline.BuildOrchestrator;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;
import protolake.v1.BuildOperationMetadata;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Dependent
@CommandLine.Command(name = "build", description = "Build the lake (Gazelle + Validation + Bazel + Publish)")
public class BuildCommand implements Runnable {

    @CommandLine.Option(names = "--lake-path", description = "Path to the lake directory (default: auto-detect)")
    String lakePath;

    @CommandLine.Option(names = "--install-local", description = "Install artifacts locally after build")
    boolean installLocal;

    @CommandLine.Option(names = "--skip-validation", description = "Skip buf lint/breaking checks")
    boolean skipValidation;

    @CommandLine.Option(names = "--bundle", description = "Bundle name to build (repeatable, empty = all)")
    List<String> bundleNames = new ArrayList<>();

    @Inject
    LakeResolver lakeResolver;

    @Inject
    BuildOrchestrator buildOrchestrator;

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Override
    public void run() {
        try {
            Path resolvedPath = resolveLakePath();
            Lake lake = lakeResolver.resolveLake(resolvedPath);
            String lakeRelativePath = LakeUtil.getRelativePath(lake);

            ConsoleProgressListener listener = new ConsoleProgressListener();
            BuildOperationMetadata metadata = BuildOperationMetadata.getDefaultInstance();

            if (bundleNames.isEmpty()) {
                // Build all
                String target = lakeRelativePath;
                System.out.printf("[protolake] Building lake: %s%n", LakeUtil.extractLakeId(lake.getName()));
                buildOrchestrator.buildTargetSync(lake, target, skipValidation, installLocal,
                        null, listener, metadata);
            } else {
                // Build specific bundles
                for (String bundleName : bundleNames) {
                    Bundle bundle = lakeResolver.findBundle(resolvedPath, lake, bundleName);
                    String target = BundleUtil.getWorkspaceRelativePath(lake, bundle);
                    System.out.printf("[protolake] Building bundle: %s%n", bundleName);
                    metadata = buildOrchestrator.buildTargetSync(lake, target, skipValidation, installLocal,
                            null, listener, metadata);
                }
            }
        } catch (BuildOrchestrator.BuildException e) {
            System.err.println("[protolake] Build failed: " + e.getMessage());
            for (var error : e.getErrors().getErrors()) {
                System.err.printf("  %s:%d:%d: %s%n",
                        error.getFile(), error.getLine(), error.getColumn(), error.getMessage());
            }
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Build failed", e);
        } catch (CommandLine.ExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Build failed: " + e.getMessage(), e);
        }
    }

    private Path resolveLakePath() {
        if (lakePath != null) {
            return Path.of(lakePath);
        }
        return findLakeRoot();
    }

    private Path findLakeRoot() {
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
