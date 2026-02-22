package io.vdp.protolake.cli;

import io.vdp.protolake.config.YamlConfigParser;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.Lake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves Lake and Bundle protos from the filesystem for CLI commands.
 * Reads lake.yaml and bundle.yaml files instead of querying StorageService.
 */
@ApplicationScoped
public class LakeResolver {
    private static final Logger LOG = Logger.getLogger(LakeResolver.class);

    @Inject
    YamlConfigParser yamlConfigParser;

    /**
     * Resolves a Lake proto from a lake directory containing lake.yaml.
     */
    public Lake resolveLake(Path lakePath) throws IOException {
        Path lakeYaml = lakePath.resolve("lake.yaml");
        if (!Files.exists(lakeYaml)) {
            throw new IOException("No lake.yaml found in " + lakePath +
                    ". Run 'protolake init' first.");
        }
        return yamlConfigParser.parseLakeYaml(lakeYaml);
    }

    /**
     * Finds all bundles in a lake by scanning for bundle.yaml files.
     */
    public List<Bundle> findBundles(Path lakePath, Lake lake) throws IOException {
        String lakeId = LakeUtil.extractLakeId(lake.getName());
        List<Bundle> bundles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(lakePath)) {
            List<Path> bundleYamls = walk
                    .filter(p -> p.getFileName().toString().equals("bundle.yaml"))
                    .toList();

            for (Path bundleYaml : bundleYamls) {
                try {
                    Bundle bundle = yamlConfigParser.parseBundleYaml(bundleYaml, lakeId);
                    bundles.add(bundle);
                } catch (Exception e) {
                    LOG.warnf("Failed to parse bundle.yaml at %s: %s", bundleYaml, e.getMessage());
                }
            }
        }

        return bundles;
    }

    /**
     * Finds a specific bundle by name within a lake.
     */
    public Bundle findBundle(Path lakePath, Lake lake, String bundleName) throws IOException {
        String lakeId = LakeUtil.extractLakeId(lake.getName());
        String expectedResourceName = BundleUtil.toResourceName(lakeId, bundleName);

        List<Bundle> allBundles = findBundles(lakePath, lake);
        return allBundles.stream()
                .filter(b -> b.getName().equals(expectedResourceName))
                .findFirst()
                .orElseThrow(() -> new IOException("Bundle not found: " + bundleName));
    }
}
