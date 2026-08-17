package space.cohub.vdp.protolake.initializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import space.cohub.vdp.protolake.config.YamlConfigParser;
import space.cohub.vdp.protolake.util.BundleUtil;
import space.cohub.vdp.protolake.util.LakeUtil;
import space.cohub.vdp.protolake.util.template.TemplateEngine;
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
 *   <li>{@code .github/workflows/release.yml} — opens Release PRs on merges to main and,
 *       when a Release PR merges, runs the publish job per bundle (matrix on
 *       {@code paths_released})</li>
 *   <li>{@code .github/workflows/pr-title-lint.yml} — enforces Conventional Commit
 *       PR titles so release-please can parse the squash-merged commit message</li>
 *   <li>{@code .github/workflows/publish-bundle.yml} — manual {@code workflow_dispatch}
 *       escape hatch to re-publish a single bundle when the auto-publish in
 *       {@code release.yml} failed but the tag/manifest entry was already
 *       created (release-please-action returns {@code releases_created: false}
 *       on re-run, so the regular path can't recover)</li>
 * </ul>
 *
 * All five use {@code writeIfNotExists} semantics: protolake seeds the defaults,
 * users own them after that — with ONE managed exception: a package's
 * {@code release-as} first-release pin is removed by every build once its
 * manifest entry moves past the bootstrap seed (see
 * {@link #convergeReleaseAsPins}). Adding a bundle does NOT automatically
 * update an existing config.json — users either edit it directly or delete it
 * and re-run {@code protolake build} to get a fresh skeleton. Delete-and-regen
 * stays correct on a released lake: bundle.yaml is release-please's own
 * version output, so a released bundle re-seeds at its released version;
 * only a bundle whose sole release was exactly the birth version re-proposes
 * it (tag {@code already_exists} → the recycled-release-PR recovery).
 *
 * <p>Why one workflow instead of a separate tag-triggered publish: refs (tags,
 * commits) created by the default {@code GITHUB_TOKEN} do NOT trigger downstream
 * workflows
 * (<a href="https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication#using-the-github_token-in-a-workflow">GitHub
 * Actions docs</a>). A separate {@code on: push: tags} publish would silently
 * never fire. Gating the publish job inline on {@code releases_created} bypasses
 * this entirely.
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

    /** Manifest seed for a bundle whose first release has not shipped. */
    static final String BOOTSTRAP_VERSION = "0.0.0";

    /** First-release pin — the 0.x version the greenfield release PR proposes. */
    static final String FIRST_RELEASE_VERSION = "0.1.0";
    private static final String RELEASE_WORKFLOW = ".github/workflows/release.yml";
    private static final String PR_TITLE_LINT_WORKFLOW = ".github/workflows/pr-title-lint.yml";
    private static final String PUBLISH_BUNDLE_WORKFLOW = ".github/workflows/publish-bundle.yml";

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
        convergeReleaseAsPins(lakePath);

        // Workflow YAMLs are static — they're plain GitHub Actions files containing
        // bash `${...}` and Actions `${{...}}` syntax that conflicts with Qute's
        // expression syntax. Copy raw rather than render.
        Files.createDirectories(lakePath.resolve(".github").resolve("workflows"));
        copyResourceIfNotExists("release-please/release.yml",
                lakePath.resolve(RELEASE_WORKFLOW));
        copyResourceIfNotExists("release-please/publish-bundle.yml",
                lakePath.resolve(PUBLISH_BUNDLE_WORKFLOW));
        copyResourceIfNotExists("release-please/pr-title-lint.yml",
                lakePath.resolve(PR_TITLE_LINT_WORKFLOW));
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
     *   <li>{@code separate-pull-requests: false} → one combined Release PR per
     *       cycle covering all bundles. Default for protolake because bundles
     *       in a lake typically release in lockstep (same proto changes, same
     *       version bump). Per-bundle tags + per-bundle publishes still fan
     *       out via release.yml's matrix on {@code paths_released}. Flip to
     *       {@code true} for lakes where bundles have genuinely independent
     *       release cadence.</li>
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
        root.put("separate-pull-requests", false);

        ObjectNode packages = root.putObject("packages");
        bundles.stream()
                .sorted(Comparator.comparing(this::bundlePath))
                .forEach(b -> {
                    ObjectNode pkg = packages.putObject(bundlePath(b));
                    pkg.put("package-name", releasePackageName(b));
                    if (isFreshBundle(b)) {
                        // A fresh component has no tag to anchor release-please's
                        // version math, and with separate-pull-requests:false it
                        // inherits a sibling bundle's lineage instead of bumping
                        // from the 0.0.0 manifest seed — the pin carries the
                        // first release; convergeReleaseAsPins removes it once
                        // the manifest moves.
                        pkg.put("release-as", FIRST_RELEASE_VERSION);
                    }
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

    /**
     * A bundle still at its birth version (or with none declared) has
     * released nothing — its lineage starts at the bootstrap seed, with a
     * {@code release-as} pin carrying the first release (the engine's
     * ReleasePleaseEntryManager contract). Any other declared version means
     * released or imported history: bundle.yaml is release-please's own
     * extra-files output, so re-seeding from it keeps the delete-and-regen
     * manifest recovery correct for released lakes.
     */
    private boolean isFreshBundle(Bundle b) {
        String v = b.getVersion();
        return v == null || v.isEmpty() || FIRST_RELEASE_VERSION.equals(v);
    }

    private String bundleVersion(Bundle b) {
        // The manifest records the last RELEASED version — seeding a fresh
        // bundle at its declared version made version probes report
        // unreleased bundles as released.
        return isFreshBundle(b) ? BOOTSTRAP_VERSION : b.getVersion();
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

    /**
     * Removes a package's {@code release-as} first-release pin once the
     * manifest shows its first release shipped (moved past
     * {@value #BOOTSTRAP_VERSION}) — a lingering pin would freeze every
     * subsequent release at {@value #FIRST_RELEASE_VERSION}. Runs on every
     * workspace init (the per-build lifecycle owner protolake-scaffolded
     * lakes otherwise lack — the engine-managed twin is
     * ReleasePleaseEntryManager). Touches ONLY the pin key; user edits to
     * everything else survive, and the file is rewritten only on change.
     */
    private void convergeReleaseAsPins(Path lakePath) throws IOException {
        Path configPath = lakePath.resolve(CONFIG_FILE);
        Path manifestPath = lakePath.resolve(MANIFEST_FILE);
        if (!Files.exists(configPath) || !Files.exists(manifestPath)) {
            return;
        }
        ObjectNode config = (ObjectNode) JSON.readTree(Files.readString(configPath));
        com.fasterxml.jackson.databind.JsonNode manifest =
                JSON.readTree(Files.readString(manifestPath));
        com.fasterxml.jackson.databind.JsonNode packages = config.get("packages");
        if (packages == null || !packages.isObject()) {
            return;
        }
        boolean changed = false;
        var names = packages.fieldNames();
        while (names.hasNext()) {
            String path = names.next();
            com.fasterxml.jackson.databind.JsonNode pkg = packages.get(path);
            if (pkg.has("release-as")
                    && manifest.has(path)
                    && !BOOTSTRAP_VERSION.equals(manifest.get(path).asText())) {
                ((ObjectNode) pkg).remove("release-as");
                changed = true;
                LOG.infof("First release of %s shipped (%s) — removed its"
                        + " release-as pin", path, manifest.get(path).asText());
            }
        }
        if (changed) {
            Files.writeString(configPath,
                    JSON.writeValueAsString(config) + System.lineSeparator());
        }
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
