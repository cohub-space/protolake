package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protolake.v1.BuildOperationMetadata;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.util.bazel.BazelCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the API spec step finds a lake's bundles, hands their versions to the
 * aspect, and reads back what the aspect's tool left.
 */
class ApiSpecRunnerTest {

    @TempDir
    Path lake;

    @Test
    void findBundles_takesBundlesWithProtos_inDirectoryOrder() throws IOException {
        bundle("company_b/apps/service_b", "service_b", "version: \"2.0.0\" # x-release-please-version", true);
        bundle("company_a/platform/service_a", "service_a", "version: 1.4.0", true);
        // create-bundle wrote a bundle.yaml, but gazelle found no protos yet.
        bundle("empty", "empty", "version: \"0.1.0\"", false);

        assertThat(ApiSpecRunner.findBundles(lake, ".")).containsExactly(
                new ApiSpecRunner.BundleTarget("company_a/platform/service_a", "service_a", "1.4.0"),
                new ApiSpecRunner.BundleTarget("company_b/apps/service_b", "service_b", "2.0.0"));
    }

    @Test
    void findBundles_bundleScope_takesOnlyThatBundle() throws IOException {
        bundle("company_a/platform/service_a", "service_a", "version: \"1.4.0\"", true);
        bundle("company_b/apps/service_b", "service_b", "version: \"2.0.0\"", true);

        assertThat(ApiSpecRunner.findBundles(lake, "company_b/apps/service_b"))
                .extracting(ApiSpecRunner.BundleTarget::label)
                .containsExactly("//company_b/apps/service_b:service_b_all_protos");
    }

    @Test
    void findBundles_skipsBazelAndNpmTrees() throws IOException {
        bundle("api", "api", "version: \"1.0.0\"", true);
        bundle("node_modules/pkg", "pkg", "version: \"1.0.0\"", true);
        bundle(".bazel/bin/api", "api", "version: \"1.0.0\"", true);
        bundle("bazel-out/api", "api", "version: \"1.0.0\"", true);

        assertThat(ApiSpecRunner.findBundles(lake, ".")).extracting(ApiSpecRunner.BundleTarget::packagePath)
                .containsExactly("api");
    }

    @Test
    void findBundles_versionTheBundlersCannotPublish_isSkipped() throws IOException {
        bundle("odd", "odd", "version: \"1.0;rm\"", true);

        assertThat(ApiSpecRunner.findBundles(lake, ".")).isEmpty();
    }

    @Test
    void versionsParameter_pairsEachBundleDirectoryWithItsVersion() {
        assertThat(ApiSpecRunner.versionsParameter(List.of(
                new ApiSpecRunner.BundleTarget("company_a/platform/service_a", "service_a", "1.4.0"),
                new ApiSpecRunner.BundleTarget("api", "api", "2.0.0-rc.1"))))
                .isEqualTo("company_a/platform/service_a=1.4.0;api=2.0.0-rc.1");
    }

    @Test
    void readSpecs_mapsFindingsAndSpecPaths() throws IOException {
        ApiSpecRunner.BundleTarget bundle = new ApiSpecRunner.BundleTarget("api", "api", "1.0.0");
        Path bin = lake.resolve("bin");
        findings(bin, bundle, """
                {"bundle": "api",
                 "findings": [
                   {"severity": "ERROR", "message": "websocket on a unary RPC", "rpc": "api.v1.S.Get",
                    "file": "api/v1/api.proto", "line": 11, "column": 5},
                   {"severity": "WARNING", "message": "the plugin's AsyncAPI failed the check"}],
                 "specs": ["api.openapi.json", "api.asyncapi.json"],
                 "asyncapi_generator": "built-in"}
                """);

        ApiSpecRunner.BundleSpecs specs = ApiSpecRunner.readSpecs(bin, bundle);

        assertThat(specs.findings()).hasSize(2);
        ValidationError error = specs.findings().get(0);
        assertThat(error.getSeverity()).isEqualTo(ValidationError.Severity.ERROR);
        assertThat(error.getType()).isEqualTo(ValidationError.Type.API_EXPOSURE);
        assertThat(error.getFile()).isEqualTo("api/v1/api.proto");
        assertThat(error.getLine()).isEqualTo(11);
        assertThat(error.getColumn()).isEqualTo(5);
        ValidationError warning = specs.findings().get(1);
        assertThat(warning.getSeverity()).isEqualTo(ValidationError.Severity.WARNING);
        assertThat(warning.getFile()).as("a bundle-wide finding names the bundle").isEqualTo("api");
        assertThat(specs.specs()).containsExactly(
                bin.resolve("api/api_api_specs/api.openapi.json"),
                bin.resolve("api/api_api_specs/api.asyncapi.json"));
        assertThat(specs.asyncApiGenerator()).isEqualTo("built-in");
    }

    @Test
    void readSpecs_noFindingsFile_throws() {
        ApiSpecRunner.BundleTarget bundle = new ApiSpecRunner.BundleTarget("api", "api", "1.0.0");

        assertThatThrownBy(() -> ApiSpecRunner.readSpecs(lake.resolve("bin"), bundle))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("left no findings for bundle api");
    }

    @Test
    void run_appliesTheAspectToEveryBundle_andReadsEachBundlesFindings() throws IOException {
        bundle("company_a/platform/service_a", "service_a", "version: \"1.4.0\"", true);
        bundle("api", "api", "version: \"2.0.0\"", true);
        Path bin = lake.resolve("bin");
        findings(bin, new ApiSpecRunner.BundleTarget("api", "api", "2.0.0"), """
                {"findings": [{"severity": "WARNING", "message": "w"}], "specs": ["api.openapi.json"]}
                """);
        findings(bin, new ApiSpecRunner.BundleTarget("company_a/platform/service_a", "service_a", "1.4.0"),
                "{\"findings\": [], \"specs\": []}");
        FakeBazel bazel = new FakeBazel(0, bin);

        ApiSpecRunner.Result result = new ApiSpecRunner(bazel).run(lake, ".", BuildOperationMetadata.getDefaultInstance());

        assertThat(bazel.aspect).isEqualTo("//tools:api_specs.bzl%api_specs");
        assertThat(bazel.outputGroup).isEqualTo("api_specs");
        assertThat(bazel.aspectParameter)
                .isEqualTo("bundle_versions=api=2.0.0;company_a/platform/service_a=1.4.0");
        assertThat(bazel.labels).containsExactly(
                "//api:api_all_protos", "//company_a/platform/service_a:service_a_all_protos");
        assertThat(result.findings(ValidationError.Severity.WARNING)).hasSize(1);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.bundles()).extracting(b -> b.specs().size()).containsExactly(1, 0);
    }

    @Test
    void run_noBundlesWithProtos_runsNoBuild() throws IOException {
        bundle("empty", "empty", "version: \"0.1.0\"", false);
        FakeBazel bazel = new FakeBazel(0, lake.resolve("bin"));

        ApiSpecRunner.Result result = new ApiSpecRunner(bazel).run(lake, ".", BuildOperationMetadata.getDefaultInstance());

        assertThat(result.bundles()).isEmpty();
        assertThat(bazel.labels).isEmpty();
    }

    @Test
    void run_aspectBuildFails_throwsWithBazelsOutput() throws IOException {
        bundle("api", "api", "version: \"2.0.0\"", true);
        FakeBazel bazel = new FakeBazel(1, lake.resolve("bin"));

        assertThatThrownBy(() -> new ApiSpecRunner(bazel).run(lake, ".", BuildOperationMetadata.getDefaultInstance()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Writing the API specs failed (bazel exit 1)")
                .hasMessageContaining("ERROR: an action failed");
    }

    private void bundle(String dir, String name, String versionLine, boolean withProtos) throws IOException {
        Path path = Files.createDirectories(lake.resolve(dir));
        Files.writeString(path.resolve("bundle.yaml"), "name: " + name + "\n" + versionLine + "\n");
        if (withProtos) {
            Files.writeString(path.resolve("BUILD.bazel"),
                    "proto_library(\n    name = \"" + name + "_all_protos\",\n)\n");
        }
    }

    private static void findings(Path bin, ApiSpecRunner.BundleTarget bundle, String json) throws IOException {
        Path dir = Files.createDirectories(bin.resolve(bundle.packagePath()));
        Files.writeString(dir.resolve(bundle.name() + "_api_findings.json"), json);
    }

    /** Records the aspect build instead of running Bazel. */
    private static final class FakeBazel extends BazelBuildRunner {
        private final int exitCode;
        private final Path bin;
        String aspect;
        String aspectParameter;
        String outputGroup;
        List<String> labels = new ArrayList<>();

        FakeBazel(int exitCode, Path bin) {
            this.exitCode = exitCode;
            this.bin = bin;
        }

        @Override
        public BazelCommand.CommandResult buildAspect(Path lakeRoot, String aspect, String aspectParameter,
                                                      String outputGroup, List<String> labels,
                                                      BuildOperationMetadata metadata) {
            this.aspect = aspect;
            this.aspectParameter = aspectParameter;
            this.outputGroup = outputGroup;
            this.labels = labels;
            return new BazelCommand.CommandResult(exitCode == 0 ? "" : "ERROR: an action failed\n", exitCode);
        }

        @Override
        public Path bazelBin(Path lakeRoot, BuildOperationMetadata metadata) {
            return bin;
        }
    }
}
