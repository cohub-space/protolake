package io.vdp.protolake.snippet;

import io.vdp.protolake.config.EffectiveConfigResolver;
import io.vdp.protolake.config.EffectiveConfigResolver.ResolvedConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import protolake.v1.Bundle;
import protolake.v1.DependencySnippet;
import protolake.v1.GetDependencySnippetResponse;
import protolake.v1.Lake;
import protolake.v1.Language;
import protolake.v1.SnippetFormat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * Generates copy-paste-ready dependency snippets for bundle artifacts.
 *
 * <p>Shared by the gRPC RPC implementation and the CLI command.
 */
@ApplicationScoped
public class DependencySnippetService {

    /** Canonical ordering when no specific languages are requested. */
    private static final List<Language> CANONICAL_ORDER = List.of(
        Language.JAVA, Language.JAVASCRIPT, Language.PYTHON, Language.GO
    );

    @Inject
    EffectiveConfigResolver effectiveConfigResolver;

    /**
     * Generates dependency snippets for all enabled languages of a bundle.
     *
     * @param requestedLanguages languages to include (empty = all enabled in canonical order)
     * @param requestedFormat preferred format; falls back to the language default when inapplicable
     */
    public GetDependencySnippetResponse generateSnippets(
            Lake lake, Bundle bundle,
            List<Language> requestedLanguages,
            SnippetFormat requestedFormat) {

        ResolvedConfig config = effectiveConfigResolver.resolve(lake, bundle);

        List<DependencySnippet> snippets = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Determine which languages to render and in what order
        List<Language> languages = determineLanguages(requestedLanguages, config, warnings);

        for (Language lang : languages) {
            generateForLanguage(lang, config, requestedFormat,
                !requestedLanguages.isEmpty(), snippets, warnings);
        }

        return GetDependencySnippetResponse.newBuilder()
            .addAllSnippets(snippets)
            .addAllWarnings(warnings)
            .build();
    }

    private List<Language> determineLanguages(
            List<Language> requested, ResolvedConfig config, List<String> warnings) {

        if (requested.isEmpty()) {
            // Return all enabled languages in canonical order
            List<Language> result = new ArrayList<>();
            for (Language lang : CANONICAL_ORDER) {
                if (isEnabled(lang, config)) {
                    result.add(lang);
                }
            }
            return result;
        }

        // Preserve request order, deduplicate
        SequencedSet<Language> seen = new LinkedHashSet<>();
        for (Language lang : requested) {
            if (lang == Language.LANGUAGE_UNSPECIFIED) {
                continue;
            }
            if (!isEnabled(lang, config)) {
                warnings.add(lang.name() + ": language is not enabled for this bundle");
                continue;
            }
            seen.add(lang);
        }
        return new ArrayList<>(seen);
    }

    private void generateForLanguage(
            Language lang, ResolvedConfig config, SnippetFormat requestedFormat,
            boolean formatExplicitlyRequested,
            List<DependencySnippet> snippets, List<String> warnings) {

        switch (lang) {
            case JAVA -> generateJava(config, requestedFormat, formatExplicitlyRequested,
                snippets, warnings);
            case JAVASCRIPT -> generateJavaScript(config, requestedFormat, formatExplicitlyRequested,
                snippets, warnings);
            case PYTHON -> generatePython(config, snippets, warnings);
            case GO -> generateGo(config, snippets, warnings);
            default -> { /* skip unspecified */ }
        }
    }

    private void generateJava(
            ResolvedConfig config, SnippetFormat requestedFormat,
            boolean formatExplicitlyRequested,
            List<DependencySnippet> snippets, List<String> warnings) {

        if (config.javaGroupId().isEmpty() || config.javaArtifactId().isEmpty()) {
            warnings.add("JAVA: missing group_id or artifact_id, skipping snippet");
            return;
        }

        SnippetFormat effectiveFormat = resolveJavaFormat(requestedFormat,
            formatExplicitlyRequested, warnings);

        String snippet;
        switch (effectiveFormat) {
            case GRADLE_KOTLIN -> snippet = String.format(
                "implementation(\"%s:%s:%s\")",
                config.javaGroupId(), config.javaArtifactId(), config.version());
            case GRADLE_GROOVY -> snippet = String.format(
                "implementation '%s:%s:%s'",
                config.javaGroupId(), config.javaArtifactId(), config.version());
            default -> snippet = String.format(
                "<dependency>\n" +
                "  <groupId>%s</groupId>\n" +
                "  <artifactId>%s</artifactId>\n" +
                "  <version>%s</version>\n" +
                "</dependency>",
                config.javaGroupId(), config.javaArtifactId(), config.version());
        }

        snippets.add(DependencySnippet.newBuilder()
            .setLanguage(Language.JAVA)
            .setFormat(effectiveFormat)
            .setSnippet(snippet)
            .build());
    }

    private SnippetFormat resolveJavaFormat(
            SnippetFormat requested, boolean explicit, List<String> warnings) {
        return switch (requested) {
            case MAVEN, GRADLE_KOTLIN, GRADLE_GROOVY -> requested;
            case NPM, YARN -> {
                if (explicit) {
                    warnings.add("JAVA: requested format " + requested.name()
                        + " does not apply to Java, using MAVEN");
                }
                yield SnippetFormat.MAVEN;
            }
            default -> SnippetFormat.MAVEN;
        };
    }

    private void generateJavaScript(
            ResolvedConfig config, SnippetFormat requestedFormat,
            boolean formatExplicitlyRequested,
            List<DependencySnippet> snippets, List<String> warnings) {

        if (config.jsPackageName().isEmpty()) {
            warnings.add("JAVASCRIPT: missing package_name, skipping snippet");
            return;
        }

        SnippetFormat effectiveFormat = resolveJsFormat(requestedFormat,
            formatExplicitlyRequested, warnings);

        // Snippet is package.json format for both npm and yarn
        String snippet = String.format("\"%s\": \"%s\"",
            config.jsPackageName(), config.version());
        String installCommand = effectiveFormat == SnippetFormat.YARN
            ? String.format("yarn add %s@%s", config.jsPackageName(), config.version())
            : String.format("npm install %s@%s", config.jsPackageName(), config.version());

        snippets.add(DependencySnippet.newBuilder()
            .setLanguage(Language.JAVASCRIPT)
            .setFormat(effectiveFormat)
            .setSnippet(snippet)
            .setInstallCommand(installCommand)
            .build());
    }

    private SnippetFormat resolveJsFormat(
            SnippetFormat requested, boolean explicit, List<String> warnings) {
        return switch (requested) {
            case NPM, YARN -> requested;
            case MAVEN, GRADLE_KOTLIN, GRADLE_GROOVY -> {
                if (explicit) {
                    warnings.add("JAVASCRIPT: requested format " + requested.name()
                        + " does not apply to JavaScript, using NPM");
                }
                yield SnippetFormat.NPM;
            }
            default -> SnippetFormat.NPM;
        };
    }

    private void generatePython(
            ResolvedConfig config, List<DependencySnippet> snippets, List<String> warnings) {

        if (config.pythonPackageName().isEmpty()) {
            warnings.add("PYTHON: missing package_name, skipping snippet");
            return;
        }

        String snippet = String.format("%s==%s",
            config.pythonPackageName(), config.version());
        String installCommand = String.format("pip install %s==%s",
            config.pythonPackageName(), config.version());

        snippets.add(DependencySnippet.newBuilder()
            .setLanguage(Language.PYTHON)
            .setFormat(SnippetFormat.FORMAT_UNSPECIFIED)
            .setSnippet(snippet)
            .setInstallCommand(installCommand)
            .build());
    }

    private void generateGo(
            ResolvedConfig config, List<DependencySnippet> snippets, List<String> warnings) {

        if (config.goModulePath().isEmpty()) {
            warnings.add("GO: missing module_path, skipping snippet");
            return;
        }

        String snippet = String.format("require %s v%s",
            config.goModulePath(), config.version());
        String installCommand = String.format("go get %s@v%s",
            config.goModulePath(), config.version());

        snippets.add(DependencySnippet.newBuilder()
            .setLanguage(Language.GO)
            .setFormat(SnippetFormat.FORMAT_UNSPECIFIED)
            .setSnippet(snippet)
            .setInstallCommand(installCommand)
            .build());
    }

    private boolean isEnabled(Language lang, ResolvedConfig config) {
        return switch (lang) {
            case JAVA -> config.javaEnabled();
            case JAVASCRIPT -> config.jsEnabled();
            case PYTHON -> config.pythonEnabled();
            case GO -> config.goEnabled();
            default -> false;
        };
    }
}
