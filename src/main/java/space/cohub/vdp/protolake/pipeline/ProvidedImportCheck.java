package space.cohub.vdp.protolake.pipeline;

import jakarta.enterprise.context.ApplicationScoped;
import space.cohub.vdp.protolake.initializer.CohubApiModule;
import space.cohub.vdp.protolake.model.ValidationError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Refuses a bundle that imports a proto the cohub_api module provides while
 * it builds Java, Python or JavaScript packages.
 *
 * <p>A bundle's generated code loads the classes of every file it imports,
 * options included. For googleapis and protovalidate those classes come from
 * published packages the bundle's packages depend on; nothing publishes
 * cohub_api's yet, so a jar, wheel or npm package of such a bundle would fail
 * when it loads. Until the companion packages exist, a bundle that imports
 * cohub/api protos builds its API specs and exposure checks only, and this
 * says so before Bazel runs, instead of a compile error or a package that
 * breaks its consumers.
 */
// TODO(t/G-dcw1-definition-catalog-wave-1): wire cohub_api's companion packages (Maven, PyPI, npm) into the bundle packages, then drop this refusal.
@ApplicationScoped
public class ProvidedImportCheck {

    /** The gazelle-emitted rules that compile a bundle's protos for each language. */
    private static final Map<String, String> LANGUAGE_RULES = languageRules();

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".bazel", "node_modules", ".aspect_rules_js");

    private static Map<String, String> languageRules() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("java_grpc_library(", "Java");
        rules.put("python_grpc_library(", "Python");
        rules.put("es_proto_compile(", "JavaScript");
        return rules;
    }

    /**
     * One ERROR per bundle under the target that imports a provided proto and
     * builds language packages, at the first such import.
     */
    public List<ValidationError> check(Path lakeRoot, String lakeRelativeTarget) throws IOException {
        Path scope = lakeRelativeTarget.isEmpty() || ".".equals(lakeRelativeTarget)
                ? lakeRoot : lakeRoot.resolve(lakeRelativeTarget);
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        List<ValidationError> errors = new ArrayList<>();
        for (Path bundleDir : bundleDirectories(scope)) {
            List<String> languages = languages(bundleDir);
            if (languages.isEmpty()) {
                continue;
            }
            Optional<Import> found = firstProvidedImport(bundleDir);
            if (found.isEmpty()) {
                continue;
            }
            Import use = found.get();
            String bundle = lakeRoot.relativize(bundleDir).toString().replace('\\', '/');
            errors.add(ValidationError.newBuilder()
                    .setType(ValidationError.Type.API_EXPOSURE)
                    .setSeverity(ValidationError.Severity.ERROR)
                    .setFile(lakeRoot.relativize(use.file()).toString().replace('\\', '/'))
                    .setLine(use.line())
                    .setColumn(use.column())
                    .setMessage(String.format("Bundle %s imports %s and builds %s packages, whose generated code "
                            + "would load cohub's annotation classes from companion packages that are not "
                            + "published yet. Set enabled: false for those languages in %s/bundle.yaml to build "
                            + "only the bundle's API specs and exposure checks.",
                            bundle, use.importPath(), String.join(", ", languages), bundle))
                    .build());
        }
        return errors;
    }

    private record Import(Path file, int line, int column, String importPath) {
    }

    private static List<String> languages(Path bundleDir) throws IOException {
        List<String> languages = new ArrayList<>();
        for (String buildFile : List.of("BUILD.bazel", "BUILD")) {
            Path path = bundleDir.resolve(buildFile);
            if (Files.isRegularFile(path)) {
                String build = Files.readString(path, StandardCharsets.UTF_8);
                LANGUAGE_RULES.forEach((rule, language) -> {
                    if (build.contains(rule) && !languages.contains(language)) {
                        languages.add(language);
                    }
                });
            }
        }
        return languages;
    }

    private static Optional<Import> firstProvidedImport(Path bundleDir) throws IOException {
        List<Path> protos = new ArrayList<>();
        walk(bundleDir, path -> path.toString().endsWith(".proto"), protos);
        protos.sort(Comparator.comparing(Path::toString));
        for (Path proto : protos) {
            String content = Files.readString(proto, StandardCharsets.UTF_8);
            for (String importPath : CohubApiModule.IMPORT_PATHS) {
                Matcher matcher = ProvidedProtoOverlay.importPattern(importPath).matcher(content);
                if (matcher.find()) {
                    int keyword = matcher.start() + matcher.group().indexOf("import");
                    int line = 1 + (int) content.substring(0, keyword).chars().filter(c -> c == '\n').count();
                    int column = keyword - content.lastIndexOf('\n', keyword - 1);
                    return Optional.of(new Import(proto, line, column, importPath));
                }
            }
        }
        return Optional.empty();
    }

    /** Bundle directories (holding a bundle.yaml) under the scope, sorted. */
    private static List<Path> bundleDirectories(Path scope) throws IOException {
        List<Path> bundleYamls = new ArrayList<>();
        walk(scope, path -> "bundle.yaml".equals(path.getFileName().toString()), bundleYamls);
        return bundleYamls.stream().map(Path::getParent).sorted(Comparator.comparing(Path::toString)).toList();
    }

    private static void walk(Path root, java.util.function.Predicate<Path> include, List<Path> into)
            throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (SKIPPED_DIRECTORIES.contains(name) || name.startsWith("bazel-"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (include.test(file)) {
                    into.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
