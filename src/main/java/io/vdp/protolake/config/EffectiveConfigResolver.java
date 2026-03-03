package io.vdp.protolake.config;

import jakarta.enterprise.context.ApplicationScoped;
import protolake.v1.Bundle;
import protolake.v1.Lake;

/**
 * Resolves the effective (merged) configuration for a bundle by combining
 * lake-level defaults with bundle-level overrides.
 *
 * <p>Mirrors the Go-side {@code MergeConfigurations()} in bundle.go.
 * Resolution rules:
 * <ul>
 *   <li>Start with lake defaults from {@code Lake.config.language_defaults}</li>
 *   <li>Override with bundle config when {@code hasJava()/hasJavascript()/hasPython()} is true</li>
 *   <li>Version always comes from {@code Bundle.version}</li>
 * </ul>
 */
@ApplicationScoped
public class EffectiveConfigResolver {

    /** Merged language configuration for a single bundle. */
    public record ResolvedConfig(
        boolean javaEnabled,
        String javaGroupId,
        String javaArtifactId,
        boolean jsEnabled,
        String jsPackageName,
        boolean pythonEnabled,
        String pythonPackageName,
        boolean goEnabled,
        String goModulePath,
        String version
    ) {}

    /** Merges lake-level language defaults with bundle-level overrides. */
    public ResolvedConfig resolve(Lake lake, Bundle bundle) {
        // Start with lake defaults
        boolean javaEnabled = false;
        String javaGroupId = "";
        String javaArtifactId = "";
        boolean jsEnabled = false;
        String jsPackageName = "";
        boolean pythonEnabled = false;
        String pythonPackageName = "";
        boolean goEnabled = false;
        String goModulePath = "";

        if (lake.hasConfig() && lake.getConfig().hasLanguageDefaults()) {
            var defaults = lake.getConfig().getLanguageDefaults();
            if (defaults.hasJava()) {
                javaEnabled = defaults.getJava().getEnabled();
                javaGroupId = defaults.getJava().getGroupId();
            }
            if (defaults.hasPython()) {
                pythonEnabled = defaults.getPython().getEnabled();
                pythonPackageName = defaults.getPython().getPackagePrefix();
            }
            if (defaults.hasJavascript()) {
                jsEnabled = defaults.getJavascript().getEnabled();
                jsPackageName = defaults.getJavascript().getPackageScope();
            }
            if (defaults.hasGo()) {
                goEnabled = defaults.getGo().getEnabled();
                goModulePath = defaults.getGo().getModulePrefix();
            }
        }

        // Override with bundle config (using hasXxx() as tri-state guard)
        if (bundle.hasConfig() && bundle.getConfig().hasLanguages()) {
            var langs = bundle.getConfig().getLanguages();

            if (langs.hasJava()) {
                javaEnabled = langs.getJava().getEnabled();
                if (!langs.getJava().getGroupId().isEmpty()) {
                    javaGroupId = langs.getJava().getGroupId();
                }
                if (!langs.getJava().getArtifactId().isEmpty()) {
                    javaArtifactId = langs.getJava().getArtifactId();
                }
            }
            if (langs.hasPython()) {
                pythonEnabled = langs.getPython().getEnabled();
                if (!langs.getPython().getPackageName().isEmpty()) {
                    pythonPackageName = langs.getPython().getPackageName();
                }
            }
            if (langs.hasJavascript()) {
                jsEnabled = langs.getJavascript().getEnabled();
                if (!langs.getJavascript().getPackageName().isEmpty()) {
                    jsPackageName = langs.getJavascript().getPackageName();
                }
            }
            if (langs.hasGo()) {
                goEnabled = langs.getGo().getEnabled();
                if (!langs.getGo().getModulePath().isEmpty()) {
                    goModulePath = langs.getGo().getModulePath();
                }
            }
        }

        return new ResolvedConfig(
            javaEnabled, javaGroupId, javaArtifactId,
            jsEnabled, jsPackageName,
            pythonEnabled, pythonPackageName,
            goEnabled, goModulePath,
            bundle.getVersion()
        );
    }
}
