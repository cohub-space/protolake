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
import protolake.v1.InstallLocalConfig;
import protolake.v1.Lake;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build then run all per-bundle publish targets.
 *
 * Equivalent to {@code build --install-local} but exposed as its own
 * subcommand because it's the entry point that tag-triggered CI workflows
 * call: a release-please-cut tag fires {@code publish.yml}, which calls
 * {@code protolakew publish --bundle <path>} for the bundle whose tag
 * fired. See the publisher-execution-model design doc for the full flow.
 */
@Dependent
@CommandLine.Command(name = "publish",
        description = "Build then publish bundle artifacts to their respective registries")
public class PublishCommand implements Runnable {

    @CommandLine.Option(names = "--lake-path",
            description = "Path to the lake directory (default: auto-detect)")
    String lakePath;

    @CommandLine.Option(names = "--bundle",
            description = "Bundle path to publish (repeatable; empty = all bundles in the lake). "
                    + "Required by tag-driven workflows that publish only the bundle whose tag fired.")
    List<String> bundleNames = new ArrayList<>();

    @CommandLine.Option(names = "--skip-validation",
            description = "Skip buf lint/breaking checks before build")
    boolean skipValidation;

    @CommandLine.Option(names = "--js-target",
            description = "Target JS/TS project for workspace install (repeatable). Implies "
                    + "NPM_PUBLISH_MODE=workspace.")
    List<String> jsTargets = new ArrayList<>();

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

            // shouldInstall=true is what triggers publishLocal in BuildOrchestrator;
            // js-targets configure NPM_PUBLISH_MODE=workspace.
            InstallLocalConfig installLocalConfig = InstallLocalConfig.newBuilder()
                .setShouldInstall(true)
                .addAllJsTargets(jsTargets)
                .build();

            if (bundleNames.isEmpty()) {
                String target = lakeRelativePath;
                System.out.printf("[protolake] Publishing lake: %s%n",
                        LakeUtil.extractLakeId(lake.getName()));
                buildOrchestrator.buildTargetSync(lake, target, skipValidation, installLocalConfig,
                        null, listener, metadata);
            } else {
                for (String bundleName : bundleNames) {
                    Bundle bundle = lakeResolver.findBundle(resolvedPath, lake, bundleName);
                    String target = BundleUtil.getWorkspaceRelativePath(lake, bundle);
                    System.out.printf("[protolake] Publishing bundle: %s%n", bundleName);
                    metadata = buildOrchestrator.buildTargetSync(lake, target, skipValidation,
                            installLocalConfig, null, listener, metadata);
                }
            }
        } catch (BuildOrchestrator.BuildException e) {
            System.err.println("[protolake] Publish failed: " + e.getMessage());
            for (var error : e.getErrors().getErrors()) {
                System.err.printf("  %s:%d:%d: %s%n",
                        error.getFile(), error.getLine(), error.getColumn(), error.getMessage());
            }
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Publish failed", e);
        } catch (CommandLine.ExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Publish failed: " + e.getMessage(), e);
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
