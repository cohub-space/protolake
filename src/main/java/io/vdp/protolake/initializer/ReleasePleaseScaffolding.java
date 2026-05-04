package io.vdp.protolake.initializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vdp.protolake.config.YamlConfigParser;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.template.TemplateEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Emits the release-please scaffolding for a lake:
 * <ul>
 *   <li>{@code release-please-config.json} — monorepo config keyed by bundle path</li>
 *   <li>{@code .release-please-manifest.json} — initial seed (bundle path → version)</li>
 *   <li>{@code .github/workflows/release-please.yml} — opens release PRs on merges to main</li>
 *   <li>{@code .github/workflows/publish.yml} — runs {@code protolakew publish --bundle &lt;path&gt;}
 *       on tag push</li>
 * </ul>
 *
 * All four use {@code writeIfNotExists} semantics: protolake seeds the defaults,
 * users own them after that. Adding a bundle does NOT automatically update an
 * existing config.json — users either edit it directly or delete it and re-run
 * {@code protolake build} to get a fresh skeleton.
 *
 * <p>The Java path's {@code package-name} matches the maven {@code artifact_id} so
 * the release-please tag ({@code <package-name>-v<version>}) and the published
 * maven coordinate share the same identifier. Bundles without Java enabled fall
 * back to the bundle's own name.
 */
@ApplicationScoped
public class ReleasePleaseScaffolding {

    private static final Logger LOG = Logger.getLogger(ReleasePleaseScaffolding.class);

    private static final String CONFIG_FILE = "release-please-config.json";
    private static final String MANIFEST_FILE = ".release-please-manifest.json";
    private static final String RELEASE_WORKFLOW = ".github/workflows/release-please.yml";
    private static final String PUBLISH_WORKFLOW = ".github/workflows/publish.yml";

    private static final String CONFIG_SCHEMA =
            "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Inject
    YamlConfigParser yamlConfigParser;

    @Inject
    TemplateEngine templateEngine;

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    /**
     * Emits the four release-please artifacts for the given lake. Idempotent —
     * existing files are preserved. No-op if the lake has no bundles yet.
     */
    public void generate(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        List<Bundle> bundles = scanBundles(lakePath, lake);

        writeIfNotExists(lakePath.resolve(CONFIG_FILE),
                JSON.writeValueAsString(buildConfigJson(bundles)));
        writeIfNotExists(lakePath.resolve(MANIFEST_FILE),
                JSON.writeValueAsString(buildManifestJson(bundles)));

        // Workflow YAMLs are static — they're plain GitHub Actions files containing
        // bash `${...}` and Actions `${{...}}` syntax that conflicts with Qute's
        // expression syntax. Copy raw rather than render.
        Files.createDirectories(lakePath.resolve(".github").resolve("workflows"));
        copyResourceIfNotExists("release-please/release-please.yml",
                lakePath.resolve(RELEASE_WORKFLOW));
        copyResourceIfNotExists("release-please/publish.yml",
                lakePath.resolve(PUBLISH_WORKFLOW));
    }

    /**
     * Builds the release-please-config.json content. Shape matches the
     * cross-cutting internal-lib-versioning design (`docs/designs/cross-cutting/
     * internal-lib-versioning.md`):
     * <ul>
     *   <li>{@code include-component-in-tag: true} + {@code tag-separator: "-"}
     *       → tags of form {@code <package-name>-v<version>}</li>
     *   <li>{@code bump-minor-pre-major: true} → 0.x bumps default to minor on
     *       feat, not major</li>
     *   <li>{@code separate-pull-requests: true} → each bundle gets its own
     *       release PR</li>
     *   <li>{@code extra-files} uses the YAML jsonpath updater so release-please
     *       finds `version:` directly without a marker comment</li>
     * </ul>
     * Keys (bundle paths) are sorted for stable output.
     */
    private ObjectNode buildConfigJson(List<Bundle> bundles) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", CONFIG_SCHEMA);
        root.put("release-type", "simple");
        root.put("include-component-in-tag", true);
        root.put("tag-separator", "-");
        root.put("bump-minor-pre-major", true);
        root.put("separate-pull-requests", true);

        ObjectNode packages = root.putObject("packages");
        bundles.stream()
                .sorted(Comparator.comparing(this::bundlePath))
                .forEach(b -> {
                    ObjectNode pkg = packages.putObject(bundlePath(b));
                    pkg.put("package-name", releasePackageName(b));
                    ObjectNode extraFile = pkg.putArray("extra-files").addObject();
                    extraFile.put("type", "yaml");
                    extraFile.put("path", "bundle.yaml");
                    extraFile.put("jsonpath", "$.version");
                });
        return root;
    }

    private ObjectNode buildManifestJson(List<Bundle> bundles) {
        ObjectNode manifest = JSON.createObjectNode();
        bundles.stream()
                .sorted(Comparator.comparing(this::bundlePath))
                .forEach(b -> manifest.put(bundlePath(b), bundleVersion(b)));
        return manifest;
    }

    private String bundlePath(Bundle b) {
        // The lake-root-relative directory housing the bundle's bundle.yaml
        // (e.g. "cohub/vdp" for the cohub-protolake vdp bundle). This is what
        // release-please keys on — its `extra-files: ["bundle.yaml"]` is
        // resolved relative to this path.
        return BundleUtil.getLakeRootRelativePath(b);
    }

    private String bundleVersion(Bundle b) {
        String v = b.getVersion();
        return (v == null || v.isEmpty()) ? "1.0.0" : v;
    }

    /**
     * Component name used in the release-please tag and matched to the maven
     * artifact_id when Java publishing is enabled. Falls back to the bundle's
     * filesystem name when Java is disabled.
     */
    private String releasePackageName(Bundle b) {
        if (b.hasConfig() && b.getConfig().hasLanguages()
                && b.getConfig().getLanguages().hasJava()
                && b.getConfig().getLanguages().getJava().getEnabled()
                && !b.getConfig().getLanguages().getJava().getArtifactId().isEmpty()) {
            return b.getConfig().getLanguages().getJava().getArtifactId();
        }
        // Fallback: last path segment of the bundle's resource name.
        String path = bundlePath(b);
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private List<Bundle> scanBundles(Path lakePath, Lake lake) throws IOException {
        if (!Files.isDirectory(lakePath)) {
            return List.of();
        }
        String lakeId = LakeUtil.extractLakeId(lake.getName());
        List<Bundle> bundles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(lakePath)) {
            List<Path> bundleYamls = walk
                    .filter(p -> p.getFileName().toString().equals("bundle.yaml"))
                    .toList();
            for (Path bundleYaml : bundleYamls) {
                try {
                    bundles.add(yamlConfigParser.parseBundleYaml(bundleYaml, lakeId));
                } catch (Exception e) {
                    LOG.warnf("Skipping bundle.yaml at %s: %s", bundleYaml, e.getMessage());
                }
            }
        }
        return bundles;
    }

    private void writeIfNotExists(Path target, String content) throws IOException {
        if (Files.exists(target)) {
            LOG.debugf("Skipping %s (already exists, user-configurable)", target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content + System.lineSeparator());
        LOG.debugf("Wrote %s", target);
    }

    private void copyResourceIfNotExists(String resourcePath, Path target) throws IOException {
        if (Files.exists(target)) {
            LOG.debugf("Skipping %s (already exists, user-configurable)", target);
            return;
        }
        templateEngine.copyResource(resourcePath, target);
        LOG.debugf("Copied %s", target);
    }
}
