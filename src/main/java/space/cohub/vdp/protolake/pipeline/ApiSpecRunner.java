package space.cohub.vdp.protolake.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import protolake.v1.BuildOperationMetadata;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.util.bazel.BazelCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks each bundle's exposure annotations and writes its API specs.
 *
 * <p>After the main build, one {@code bazel build} applies the {@code api_specs}
 * aspect (templates/tools/api_specs.bzl) to the {@code <bundle>_all_protos}
 * target gazelle emits for every bundle in scope. The aspect's tool writes the
 * bundle's OpenAPI and AsyncAPI documents and a findings file, which this reads
 * back. The checks belong to building a bundle, not to buf validation, so
 * {@code --skip-validation} does not skip them; the orchestrator fails the build
 * on an ERROR finding before anything is published.
 */
@ApplicationScoped
public class ApiSpecRunner {
    private static final Logger LOG = Logger.getLogger(ApiSpecRunner.class);

    static final String ASPECT = "//tools:api_specs.bzl%api_specs";
    static final String OUTPUT_GROUP = "api_specs";
    static final String ALL_PROTOS_SUFFIX = "_all_protos";

    /** Directories that never hold bundles: VCS metadata, Bazel symlinks, npm trees. */
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".bazel", "node_modules", ".aspect_rules_js");

    /**
     * The bundlers' reading of a bundle.yaml version (jar_bundler_generated.py),
     * so the specs carry the version the libraries are published under. The
     * character set also keeps a version safe inside the aspect parameter.
     */
    private static final Pattern VERSION_LINE = Pattern.compile("^version:\\s*['\"]?([^'\"\\s]+)");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+~-]+");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private final BazelBuildRunner bazelBuildRunner;

    @Inject
    public ApiSpecRunner(BazelBuildRunner bazelBuildRunner) {
        this.bazelBuildRunner = bazelBuildRunner;
    }

    /**
     * A bundle the aspect runs on.
     *
     * @param packagePath the bundle's lake-relative directory, its Bazel package
     * @param name        the bundle.yaml {@code name}, which gazelle names targets after
     * @param version     the bundle.yaml {@code version}
     */
    public record BundleTarget(String packagePath, String name, String version) {
        /** The gazelle-emitted target the aspect is applied to. */
        public String label() {
            return "//" + packagePath + ":" + name + ALL_PROTOS_SUFFIX;
        }
    }

    /**
     * What the aspect left for one bundle.
     *
     * @param findings          the exposure checks' and the generators' findings
     * @param specs             the spec documents written, empty when the bundle
     *                          has neither REST bindings nor WebSocket RPCs
     * @param asyncApiGenerator what wrote the AsyncAPI document, empty when none was
     */
    public record BundleSpecs(BundleTarget bundle, List<ValidationError> findings, List<Path> specs,
                              String asyncApiGenerator) {
    }

    /** The outcome for every bundle in scope. */
    public record Result(List<BundleSpecs> bundles) {
        /** The findings of one severity, across all bundles. */
        public List<ValidationError> findings(ValidationError.Severity severity) {
            return bundles.stream()
                    .flatMap(b -> b.findings().stream())
                    .filter(f -> f.getSeverity() == severity)
                    .toList();
        }

        public boolean hasErrors() {
            return !findings(ValidationError.Severity.ERROR).isEmpty();
        }
    }

    /**
     * Runs the aspect over the bundles under a lake-relative target.
     *
     * @param lakeRoot           the lake root, where MODULE.bazel is
     * @param lakeRelativeTarget {@code .} for the whole lake, else a bundle's directory
     * @throws IOException when the aspect's build fails, which a finding never causes
     */
    public Result run(Path lakeRoot, String lakeRelativeTarget, BuildOperationMetadata metadata)
            throws IOException {
        List<BundleTarget> bundles = findBundles(lakeRoot, lakeRelativeTarget);
        if (bundles.isEmpty()) {
            LOG.infof("No bundles with protos under %s; no API specs to write", lakeRelativeTarget);
            return new Result(List.of());
        }
        List<String> labels = bundles.stream().map(BundleTarget::label).toList();
        LOG.infof("Writing API specs for %d bundle(s): %s", bundles.size(), labels);
        BazelCommand.CommandResult build = bazelBuildRunner.buildAspect(lakeRoot, ASPECT,
                "bundle_versions=" + versionsParameter(bundles), OUTPUT_GROUP, labels, metadata);
        if (!build.isSuccess()) {
            throw new IOException("Writing the API specs failed (bazel exit " + build.exitCode + "):\n"
                    + tail(build.output, 60));
        }
        Path bazelBin = bazelBuildRunner.bazelBin(lakeRoot, metadata);
        List<BundleSpecs> results = new ArrayList<>();
        for (BundleTarget bundle : bundles) {
            results.add(readSpecs(bazelBin, bundle));
        }
        return new Result(results);
    }

    /**
     * The bundles under a lake-relative target that have protos, sorted by
     * directory. A bundle without protos has no {@code _all_protos} target.
     */
    static List<BundleTarget> findBundles(Path lakeRoot, String lakeRelativeTarget) throws IOException {
        Path scope = lakeRelativeTarget.isEmpty() || ".".equals(lakeRelativeTarget)
                ? lakeRoot : lakeRoot.resolve(lakeRelativeTarget);
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        List<Path> bundleYamls = new ArrayList<>();
        Files.walkFileTree(scope, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(scope) && (SKIPPED_DIRECTORIES.contains(name) || name.startsWith("bazel-"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if ("bundle.yaml".equals(file.getFileName().toString())) {
                    bundleYamls.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        bundleYamls.sort(Comparator.comparing(Path::toString));

        List<BundleTarget> bundles = new ArrayList<>();
        for (Path bundleYaml : bundleYamls) {
            Path dir = bundleYaml.getParent();
            String packagePath = lakeRoot.relativize(dir).toString().replace('\\', '/');
            if (packagePath.isEmpty()) {
                continue; // a lake root is never a bundle
            }
            Optional<String> name = bundleName(bundleYaml);
            Optional<String> version = bundleVersion(bundleYaml);
            if (name.isEmpty() || version.isEmpty()) {
                LOG.warnf("Skipping API specs for %s: no usable name or version", bundleYaml);
                continue;
            }
            if (!declaresTarget(dir, name.get() + ALL_PROTOS_SUFFIX)) {
                LOG.debugf("Bundle %s has no protos yet; no API specs", packagePath);
                continue;
            }
            bundles.add(new BundleTarget(packagePath, name.get(), version.get()));
        }
        return bundles;
    }

    private static Optional<String> bundleName(Path bundleYaml) throws IOException {
        JsonNode name = YAML.readTree(bundleYaml.toFile()).path("name");
        return name.isValueNode() && !name.asText().isBlank()
                ? Optional.of(name.asText().trim()) : Optional.empty();
    }

    private static Optional<String> bundleVersion(Path bundleYaml) throws IOException {
        for (String line : Files.readAllLines(bundleYaml, StandardCharsets.UTF_8)) {
            Matcher matcher = VERSION_LINE.matcher(line);
            if (matcher.find()) {
                String version = matcher.group(1);
                return VERSION.matcher(version).matches() ? Optional.of(version) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean declaresTarget(Path dir, String target) throws IOException {
        Pattern declaration = Pattern.compile("name\\s*=\\s*\"" + Pattern.quote(target) + "\"");
        for (String buildFile : List.of("BUILD.bazel", "BUILD")) {
            Path path = dir.resolve(buildFile);
            if (Files.isRegularFile(path) && declaration.matcher(Files.readString(path)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The aspect's {@code bundle_versions}: {@code <package>=<version>} entries joined by {@code ;}. */
    static String versionsParameter(List<BundleTarget> bundles) {
        return bundles.stream()
                .map(b -> b.packagePath() + "=" + b.version())
                .collect(Collectors.joining(";"));
    }

    /** Reads one bundle's findings file and spec paths from the aspect's outputs. */
    static BundleSpecs readSpecs(Path bazelBin, BundleTarget bundle) throws IOException {
        Path dir = bazelBin.resolve(bundle.packagePath());
        Path findingsFile = dir.resolve(bundle.name() + "_api_findings.json");
        if (!Files.isRegularFile(findingsFile)) {
            throw new IOException("The API spec step left no findings for bundle " + bundle.packagePath()
                    + " at " + findingsFile);
        }
        JsonNode root = JSON.readTree(findingsFile.toFile());
        List<ValidationError> findings = new ArrayList<>();
        for (JsonNode item : root.path("findings")) {
            boolean error = "ERROR".equals(item.path("severity").asText());
            String file = item.path("file").isTextual() ? item.path("file").asText() : bundle.packagePath();
            findings.add(ValidationError.newBuilder()
                    .setType(ValidationError.Type.API_EXPOSURE)
                    .setSeverity(error ? ValidationError.Severity.ERROR : ValidationError.Severity.WARNING)
                    .setFile(file)
                    .setLine(item.path("line").asInt(0))
                    .setColumn(item.path("column").asInt(0))
                    .setMessage(item.path("message").asText())
                    .build());
        }
        Path specsDir = dir.resolve(bundle.name() + "_api_specs");
        List<Path> specs = new ArrayList<>();
        for (JsonNode spec : root.path("specs")) {
            specs.add(specsDir.resolve(spec.asText()));
        }
        JsonNode generator = root.path("asyncapi_generator");
        return new BundleSpecs(bundle, findings, specs, generator.isTextual() ? generator.asText() : "");
    }

    private static String tail(String output, int lines) {
        String[] all = output.split("\n");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }
}
