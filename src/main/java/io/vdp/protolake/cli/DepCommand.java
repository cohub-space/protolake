package io.vdp.protolake.cli;

import com.google.protobuf.util.JsonFormat;
import io.vdp.protolake.snippet.DependencySnippetService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;
import protolake.v1.Bundle;
import protolake.v1.DependencySnippet;
import protolake.v1.GetDependencySnippetResponse;
import protolake.v1.Lake;
import protolake.v1.Language;
import protolake.v1.SnippetFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** CLI command group for dependency snippet operations. */
@Dependent
@CommandLine.Command(name = "dep", description = "Dependency snippet commands",
    subcommands = {CommandLine.HelpCommand.class})
public class DepCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /** Prints dependency snippets for a bundle in human-readable, JSON, or plain format. */
    @Dependent
    @CommandLine.Command(name = "show", description = "Show dependency snippets for a bundle")
    public static class ShowCommand implements Runnable {

        @CommandLine.Parameters(index = "0", description = "Bundle name")
        String bundleName;

        @CommandLine.Option(names = "--lake-path",
            description = "Path to the lake directory (default: auto-detect)")
        String lakePath;

        @CommandLine.Option(names = "--lang",
            description = "Language filter: java, js, python, go")
        String language;

        @CommandLine.Option(names = "--format",
            description = "Snippet format: maven, gradle-kotlin, gradle-groovy, npm, yarn")
        String format;

        @CommandLine.Option(names = "--plain",
            description = "Print only snippet text (for piping)")
        boolean plain;

        @CommandLine.Option(names = "--json",
            description = "Output as JSON")
        boolean json;

        @Inject
        LakeResolver lakeResolver;

        @Inject
        DependencySnippetService dependencySnippetService;

        @ConfigProperty(name = "protolake.storage.base-path")
        String basePath;

        @Override
        public void run() {
            try {
                Path resolvedPath = resolveLakePath();
                Lake lake = lakeResolver.resolveLake(resolvedPath);
                Bundle bundle = lakeResolver.findBundle(resolvedPath, lake, bundleName);

                List<Language> languages = parseLanguages();
                SnippetFormat snippetFormat = parseFormat();

                GetDependencySnippetResponse response = dependencySnippetService.generateSnippets(
                    lake, bundle, languages, snippetFormat);

                if (json) {
                    System.out.println(JsonFormat.printer()
                        .includingDefaultValueFields()
                        .print(response));
                    return;
                }

                if (plain) {
                    for (DependencySnippet snippet : response.getSnippetsList()) {
                        System.out.println(snippet.getSnippet());
                    }
                    return;
                }

                // Formatted output
                String bundleDisplay = bundleName + " " + bundle.getVersion();
                System.out.printf("[protolake] Dependency snippets for %s%n%n", bundleDisplay);

                for (DependencySnippet snippet : response.getSnippetsList()) {
                    String langName = snippet.getLanguage().name().toLowerCase();
                    String formatName = snippet.getFormat() != SnippetFormat.FORMAT_UNSPECIFIED
                        ? " (" + snippet.getFormat().name().toLowerCase().replace("_", "-") + ")"
                        : "";
                    System.out.printf("  %s%s:%n", langName, formatName);
                    for (String line : snippet.getSnippet().split("\n")) {
                        System.out.printf("    %s%n", line);
                    }
                    if (!snippet.getInstallCommand().isEmpty()) {
                        System.out.printf("    # %s%n", snippet.getInstallCommand());
                    }
                    System.out.println();
                }

                for (String warning : response.getWarningsList()) {
                    System.err.printf("[protolake] \u26A0 %s%n", warning);
                }

            } catch (Exception e) {
                throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Failed: " + e.getMessage(), e);
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
            try (var stream = java.nio.file.Files.walk(base, 3)) {
                var paths = stream
                    .filter(p -> p.getFileName().toString().equals("lake.yaml"))
                    .map(Path::getParent)
                    .toList();
                if (paths.size() == 1) {
                    return paths.getFirst();
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

        private List<Language> parseLanguages() {
            if (language == null || language.isEmpty()) {
                return List.of();
            }
            return switch (language.toLowerCase()) {
                case "java" -> List.of(Language.JAVA);
                case "js", "javascript" -> List.of(Language.JAVASCRIPT);
                case "python", "py" -> List.of(Language.PYTHON);
                case "go" -> List.of(Language.GO);
                default -> throw new IllegalArgumentException(
                    "Unknown language: " + language + ". Use: java, js, python, go");
            };
        }

        private SnippetFormat parseFormat() {
            if (format == null || format.isEmpty()) {
                return SnippetFormat.FORMAT_UNSPECIFIED;
            }
            return switch (format.toLowerCase()) {
                case "maven" -> SnippetFormat.MAVEN;
                case "gradle-kotlin", "gradle_kotlin" -> SnippetFormat.GRADLE_KOTLIN;
                case "gradle-groovy", "gradle_groovy" -> SnippetFormat.GRADLE_GROOVY;
                case "npm" -> SnippetFormat.NPM;
                case "yarn" -> SnippetFormat.YARN;
                default -> throw new IllegalArgumentException(
                    "Unknown format: " + format + ". Use: maven, gradle-kotlin, gradle-groovy, npm, yarn");
            };
        }
    }
}
