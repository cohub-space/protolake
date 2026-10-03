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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Refuses a bundle that builds Java, Python or JavaScript packages from protos
 * that reach a proto the cohub_api module provides.
 *
 * <p>A bundle's generated code loads the classes of every file it imports,
 * options included. For googleapis and protovalidate those classes come from
 * published packages the bundle's packages depend on; nothing publishes
 * cohub_api's yet, so a jar, wheel or npm package of such a bundle would fail
 * when it loads. Until the companion packages exist, a bundle whose protos
 * reach cohub/api protos builds its API specs and exposure checks only, and
 * this says so before Bazel runs, instead of a compile error or a package that
 * breaks its consumers.
 *
 * <p>"Reach" is the transitive closure of the bundle's imports, read as protoc
 * reads them (ProtoImports): a bundle that imports another bundle's proto,
 * which imports exposure.proto, compiles that proto's code into its packages
 * or depends on it. Gazelle builds one proto_library per directory and proto
 * package, and a bundle compiles the whole target of each proto it imports, so
 * a file reached through an import brings in the other protos of its
 * directory too.
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
     * One ERROR per bundle under the target whose protos reach a provided
     * proto while it builds language packages, at the bundle's import that
     * leads there.
     */
    public List<ValidationError> check(Path lakeRoot, String lakeRelativeTarget) throws IOException {
        lakeRoot = lakeRoot.toAbsolutePath().normalize();
        Path scope = lakeRelativeTarget.isEmpty() || ".".equals(lakeRelativeTarget)
                ? lakeRoot : lakeRoot.resolve(lakeRelativeTarget).normalize();
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        ImportGraph graph = new ImportGraph(lakeRoot);
        List<ValidationError> errors = new ArrayList<>();
        for (Path bundleDir : bundleDirectories(scope)) {
            List<String> languages = languages(bundleDir);
            if (languages.isEmpty()) {
                continue;
            }
            Optional<List<Step>> found = graph.firstProvidedImport(bundleDir);
            if (found.isEmpty()) {
                continue;
            }
            List<Step> chain = found.get();
            String bundle = relative(lakeRoot, bundleDir);
            String provided = chain.get(chain.size() - 1).path();
            String message;
            if (chain.size() == 2) {
                message = String.format("Bundle %s imports %s and builds %s packages, whose generated code would "
                        + "load cohub's annotation classes from companion packages that are not published yet. "
                        + "Set enabled: false for those languages in %s/bundle.yaml to build only the bundle's API "
                        + "specs and exposure checks.", bundle, provided, String.join(", ", languages), bundle);
            } else {
                message = String.format("Bundle %s builds %s packages from protos that reach %s: %s. Their generated "
                        + "code would load cohub's annotation classes from companion packages that are not published "
                        + "yet. Set enabled: false for those languages in %s/bundle.yaml to build only the bundle's "
                        + "API specs and exposure checks, or drop the import that leads there.",
                        bundle, String.join(", ", languages), provided, describe(chain), bundle);
            }
            Step first = chain.get(1);
            errors.add(ValidationError.newBuilder()
                    .setType(ValidationError.Type.API_EXPOSURE)
                    .setSeverity(ValidationError.Severity.ERROR)
                    .setFile(chain.get(0).path())
                    .setLine(first.line())
                    .setColumn(first.column())
                    .setMessage(message)
                    .build());
        }
        return errors;
    }

    /**
     * One file on the way to a provided proto: how it was reached from the
     * step before it, and for an import, the import statement's position in
     * that earlier file.
     */
    private record Step(String path, Edge edge, int line, int column) {
    }

    private enum Edge { START, IMPORT, SAME_TARGET }

    /** {@code a.proto imports b.proto, which Bazel compiles with c.proto, which imports d.proto}. */
    private static String describe(List<Step> chain) {
        StringBuilder text = new StringBuilder(chain.get(0).path());
        for (int i = 1; i < chain.size(); i++) {
            Step step = chain.get(i);
            text.append(i == 1 ? " " : ", which ")
                    .append(step.edge() == Edge.IMPORT ? "imports " : "Bazel compiles with ")
                    .append(step.path());
        }
        return text.toString();
    }

    /** The lake's protos and their imports, parsed as the closure reaches them. */
    private static final class ImportGraph {
        private final Path lakeRoot;
        private final Map<String, List<ProtoImports.Import>> imports = new HashMap<>();

        ImportGraph(Path lakeRoot) {
            this.lakeRoot = lakeRoot;
        }

        /**
         * The shortest chain from one of the bundle's protos to an import of a
         * provided proto, breadth-first so a direct import wins over a
         * transitive one; empty when the closure reaches none.
         */
        Optional<List<Step>> firstProvidedImport(Path bundleDir) throws IOException {
            Map<String, Step> reachedBy = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            for (Path proto : protos(bundleDir, true)) {
                String path = relative(lakeRoot, proto);
                reachedBy.put(path, new Step(path, Edge.START, 0, 0));
                queue.add(path);
            }
            while (!queue.isEmpty()) {
                String file = queue.remove();
                for (ProtoImports.Import use : imports(file)) {
                    if (CohubApiModule.IMPORT_PATHS.contains(use.path())) {
                        List<Step> chain = chain(reachedBy, file);
                        chain.add(new Step(use.path(), Edge.IMPORT, use.line(), use.column()));
                        return Optional.of(chain);
                    }
                    Optional<String> target = lakeFile(use.path());
                    if (target.isEmpty() || reachedBy.containsKey(target.get())) {
                        continue;
                    }
                    reachedBy.put(target.get(), new Step(file, Edge.IMPORT, use.line(), use.column()));
                    queue.add(target.get());
                    for (Path sibling : protos(lakeRoot.resolve(target.get()).getParent(), false)) {
                        String path = relative(lakeRoot, sibling);
                        if (!reachedBy.containsKey(path)) {
                            reachedBy.put(path, new Step(target.get(), Edge.SAME_TARGET, 0, 0));
                            queue.add(path);
                        }
                    }
                }
            }
            return Optional.empty();
        }

        /**
         * The steps from a start file to {@code file}; each entry of reachedBy
         * names the file it was reached from, and the result names each file
         * with the edge that reached it.
         */
        private static List<Step> chain(Map<String, Step> reachedBy, String file) {
            List<Step> chain = new ArrayList<>();
            String current = file;
            while (true) {
                Step by = reachedBy.get(current);
                if (by.edge() == Edge.START) {
                    chain.add(new Step(current, Edge.START, 0, 0));
                    break;
                }
                chain.add(new Step(current, by.edge(), by.line(), by.column()));
                current = by.path();
            }
            Collections.reverse(chain);
            return chain;
        }

        private List<ProtoImports.Import> imports(String file) throws IOException {
            List<ProtoImports.Import> parsed = imports.get(file);
            if (parsed == null) {
                parsed = ProtoImports.parse(Files.readString(lakeRoot.resolve(file), StandardCharsets.UTF_8));
                imports.put(file, parsed);
            }
            return parsed;
        }

        /** The lake proto an import path names, by its path from the lake root, if the lake holds it. */
        private Optional<String> lakeFile(String importPath) {
            Path target = lakeRoot.resolve(importPath).normalize();
            if (!target.startsWith(lakeRoot) || !Files.isRegularFile(target)) {
                return Optional.empty();
            }
            for (Path part : lakeRoot.relativize(target)) {
                String name = part.toString();
                if (SKIPPED_DIRECTORIES.contains(name) || name.startsWith("bazel-")) {
                    return Optional.empty();
                }
            }
            return Optional.of(relative(lakeRoot, target));
        }
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

    /** The .proto files under dir, outside the skipped directories, sorted; recursive or dir's own. */
    private static List<Path> protos(Path dir, boolean recursive) throws IOException {
        List<Path> protos = new ArrayList<>();
        if (recursive) {
            walk(dir, path -> path.toString().endsWith(".proto"), protos);
        } else {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(path -> path.toString().endsWith(".proto") && Files.isRegularFile(path))
                        .forEach(protos::add);
            }
        }
        protos.sort(Comparator.comparing(Path::toString));
        return protos;
    }

    private static String relative(Path lakeRoot, Path path) {
        return lakeRoot.relativize(path).toString().replace('\\', '/');
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
