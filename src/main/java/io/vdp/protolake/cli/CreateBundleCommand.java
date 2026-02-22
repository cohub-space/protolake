package io.vdp.protolake.cli;

import io.vdp.protolake.initializer.BundleInitializer;
import io.vdp.protolake.util.BundleUtil;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;
import protolake.v1.*;

import java.nio.file.Path;

@Dependent
@CommandLine.Command(name = "create-bundle", description = "Create a new bundle in the lake")
public class CreateBundleCommand implements Runnable {

    @CommandLine.Option(names = "--name", required = true, description = "Bundle name")
    String name;

    @CommandLine.Option(names = "--bundle-prefix", description = "Bundle prefix (dot-separated, e.g. com.company)", defaultValue = "")
    String bundlePrefix;

    @CommandLine.Option(names = "--display-name", description = "Human-readable display name")
    String displayName;

    @CommandLine.Option(names = "--description", description = "Bundle description")
    String description;

    @CommandLine.Option(names = "--java-artifact-id", description = "Java artifact ID for this bundle")
    String javaArtifactId;

    @CommandLine.Option(names = "--java-group-id", description = "Java group ID override for this bundle")
    String javaGroupId;

    @CommandLine.Option(names = "--python-package-name", description = "Python package name for this bundle")
    String pythonPackageName;

    @CommandLine.Option(names = "--js-package-name", description = "JavaScript package name for this bundle")
    String jsPackageName;

    @CommandLine.Option(names = "--lake-path", description = "Path to the lake directory (default: auto-detect)")
    String lakePath;

    @Inject
    LakeResolver lakeResolver;

    @Inject
    BundleInitializer bundleInitializer;

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Override
    public void run() {
        try {
            Path resolvedPath = resolveLakePath();
            Lake lake = lakeResolver.resolveLake(resolvedPath);

            System.out.printf("[protolake] Creating bundle: %s%n", name);

            // Build BundleConfig from CLI options
            BundleConfig.Builder configBuilder = BundleConfig.newBuilder();
            LanguageConfig.Builder langBuilder = LanguageConfig.newBuilder();

            if (javaArtifactId != null || javaGroupId != null) {
                JavaConfig.Builder javaBuilder = JavaConfig.newBuilder().setEnabled(true);
                if (javaArtifactId != null) javaBuilder.setArtifactId(javaArtifactId);
                if (javaGroupId != null) javaBuilder.setGroupId(javaGroupId);
                langBuilder.setJava(javaBuilder.build());
            }

            if (pythonPackageName != null) {
                langBuilder.setPython(PythonConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName(pythonPackageName)
                        .build());
            }

            if (jsPackageName != null) {
                langBuilder.setJavascript(JavaScriptConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName(jsPackageName)
                        .build());
            }

            configBuilder.setLanguages(langBuilder.build());
            BundleConfig config = configBuilder.build();

            Bundle bundle = bundleInitializer.initializeBundle(
                    lake, name, displayName, description, bundlePrefix, config);

            Path bundlePath = BundleUtil.calculateBundlePath(lake, bundle, basePath);
            System.out.printf("[protolake] Bundle created at: %s%n", bundlePath);
            System.out.println("[protolake] Add your .proto files to this directory, then run:");
            System.out.println("  ./protolakew build --install-local");
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Create bundle failed: " + e.getMessage(), e);
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
