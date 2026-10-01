package space.cohub.vdp.protolake.initializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import space.cohub.vdp.protolake.util.LakeUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release-please birth contract (the engine's ReleasePleaseEntryManager
 * shape, ported to lake scaffolding): a FRESH bundle (birth version, or
 * none) seeds the manifest at 0.0.0 with a {@code release-as} first-release
 * pin in the config — a fresh component has no tag to anchor version math,
 * and with {@code separate-pull-requests: false} it would inherit a sibling
 * lineage instead of bumping from the manifest. A bundle at any other
 * version has released/imported history and re-seeds verbatim (keeps
 * delete-and-regen recovery correct). Once the manifest moves past 0.0.0
 * the pin is removed on the next build — a lingering pin would freeze every
 * release at 0.1.0.
 */
@QuarkusTest
class ReleasePleaseScaffoldingTest extends InitializerTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    ReleasePleaseScaffolding scaffolding;

    private Lake lake;
    private Path lakePath;

    @BeforeEach
    void setup() throws Exception {
        basePath = Paths.get(System.getProperty("java.io.tmpdir"), "proto-lake-test");
        if (Files.exists(basePath)) {
            deleteRecursively(basePath);
        }
        Files.createDirectories(basePath);
        lake = Lake.newBuilder()
                .setName(LakeUtil.toResourceName("rp-lake"))
                .setDisplayName("rp-lake")
                .setConfig(LakeConfig.getDefaultInstance())
                .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
                .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()))
                .build();
        lakePath = basePath.resolve("rp-lake");
        writeBundle("fresh", "0.1.0");
        writeBundle("released", "0.4.2");
    }

    private void writeBundle(String name, String version) throws Exception {
        Path dir = lakePath.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bundle.yaml"), """
                name: %s
                display_name: %s
                description: test bundle
                version: "%s"
                """.formatted(name, name, version));
    }

    /**
     * A release PR must not merge before its branch carries the regenerated
     * BUILD files: release-please opens it as a draft; release.yml holds a
     * ready one as a draft before release-please can move it, readies again
     * the ones it left alone, and regenerates every draft, marking it ready
     * only after pushing.
     */
    @Test
    void releasePrsStayDraftsUntilTheirBranchIsRegenerated() throws Exception {
        scaffolding.generate(lake);

        JsonNode config = JSON.readTree(
                Files.readString(lakePath.resolve("release-please-config.json")));
        assertThat(config.get("draft-pull-request").asBoolean()).isTrue();

        String release = Files.readString(lakePath.resolve(".github/workflows/release.yml"));
        int releaseJob = release.indexOf("  release-please:");
        int regenerateJob = release.indexOf("  regenerate-release-branch:");
        assertThat(releaseJob).isPositive();
        assertThat(regenerateJob).isGreaterThan(releaseJob);
        String releasePlease = release.substring(releaseJob, regenerateJob);
        int hold = releasePlease.indexOf("gh pr ready --undo");
        int action = releasePlease.indexOf("uses: googleapis/release-please-action@v4");
        int readyAgain = releasePlease.indexOf(
                "if [ \"$head\" = \"$(echo \"$entry\" | cut -d: -f2)\" ]; then");
        int drafts = releasePlease.indexOf("select(.isDraft)] | tojson");
        assertThat(hold).as("held before release-please can move it").isPositive();
        assertThat(action).isGreaterThan(hold);
        assertThat(readyAgain).as("an unmoved head is readied again").isGreaterThan(action);
        assertThat(releasePlease).contains("if: always() && steps.hold.outputs.held != ''");
        assertThat(drafts).as("every draft is regenerated").isGreaterThan(readyAgain);
        assertThat(releasePlease).contains("drafts:           ${{ steps.drafts.outputs.prs }}");

        String regenerate = release.substring(regenerateJob, release.indexOf("\n  publish:", regenerateJob));
        assertThat(regenerate).contains("pr: ${{ fromJson(needs.release-please.outputs.drafts) }}");
        assertThat(regenerate).contains("pull-requests: write");
        assertThat(regenerate).doesNotContain("gh pr ready --undo");
        int push = regenerate.indexOf("git push --force-with-lease");
        int ready = regenerate.indexOf("gh pr ready \"$RELEASE_PR\"");
        assertThat(push).isPositive();
        assertThat(ready).as("marked ready only after the push").isGreaterThan(push);
    }

    @Test
    void freshSeedsBootstrapPlusPin_releasedSeedsVerbatim_pinRemovedOnceShipped()
            throws Exception {
        scaffolding.generate(lake);

        JsonNode manifest = JSON.readTree(
                Files.readString(lakePath.resolve(".release-please-manifest.json")));
        assertThat(manifest.get("fresh").asText())
                .as("fresh bundle: manifest records last RELEASED — none yet")
                .isEqualTo("0.0.0");
        assertThat(manifest.get("released").asText())
                .as("non-birth version = released/imported history, verbatim")
                .isEqualTo("0.4.2");

        JsonNode config = JSON.readTree(
                Files.readString(lakePath.resolve("release-please-config.json")));
        assertThat(config.at("/packages/fresh/release-as").asText())
                .isEqualTo("0.1.0");
        assertThat(config.at("/packages/released/release-as").isMissingNode())
                .as("released bundles carry no first-release pin")
                .isTrue();

        // First release ships: the manifest moves; the next build removes
        // ONLY the pin (config is otherwise user-owned after birth).
        Files.writeString(lakePath.resolve(".release-please-manifest.json"),
                "{\n  \"fresh\": \"0.1.0\",\n  \"released\": \"0.4.2\"\n}\n");
        scaffolding.generate(lake);

        JsonNode converged = JSON.readTree(
                Files.readString(lakePath.resolve("release-please-config.json")));
        assertThat(converged.at("/packages/fresh/release-as").isMissingNode()).isTrue();
        assertThat(converged.at("/packages/fresh/package-name").isMissingNode())
                .as("only the pin is touched")
                .isFalse();
    }
}
