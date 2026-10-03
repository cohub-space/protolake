package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.cohub.vdp.protolake.model.ValidationError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Until cohub_api's companion packages exist, a bundle that imports its
 * protos builds no language packages: their generated code would load
 * classes nothing publishes.
 */
class ProvidedImportCheckTest {

    private static final String IMPORTING = "syntax = \"proto3\";\n\npackage api.v1;\n\n"
            + "import \"cohub/api/v1/exposure.proto\";\n";

    @TempDir
    Path lake;

    private final ProvidedImportCheck check = new ProvidedImportCheck();

    @Test
    void check_importingBundleWithLanguagePackages_isRefusedAtTheImport() throws IOException {
        bundle("api", IMPORTING, "java_grpc_library(\n)\npython_grpc_library(\n)\nes_proto_compile(\n)\n");

        assertThat(check.check(lake, ".")).singleElement().satisfies(error -> {
            assertThat(error.getSeverity()).isEqualTo(ValidationError.Severity.ERROR);
            assertThat(error.getType()).isEqualTo(ValidationError.Type.API_EXPOSURE);
            assertThat(error.getFile()).isEqualTo("api/v1/api.proto");
            assertThat(error.getLine()).isEqualTo(5);
            assertThat(error.getColumn()).isEqualTo(1);
            assertThat(error.getMessage()).contains("Bundle api imports cohub/api/v1/exposure.proto",
                    "Java, Python, JavaScript", "enabled: false", "api/bundle.yaml");
        });
    }

    @Test
    void check_importingBundleWithoutLanguagePackages_buildsItsSpecs() throws IOException {
        bundle("api", IMPORTING, "proto_library(\n    name = \"api_all_protos\",\n)\n");

        assertThat(check.check(lake, ".")).isEmpty();
    }

    @Test
    void check_bundleThatDoesNotImportIt_isUntouched() throws IOException {
        bundle("plain", "syntax = \"proto3\";\npackage plain.v1;\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).isEmpty();
    }

    @Test
    void check_bundleScope_looksAtThatBundleOnly() throws IOException {
        bundle("api", IMPORTING, "java_grpc_library(\n)\n");
        bundle("plain", "syntax = \"proto3\";\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, "plain")).isEmpty();
        assertThat(check.check(lake, "api")).hasSize(1);
    }

    private void bundle(String dir, String proto, String build) throws IOException {
        Path bundle = Files.createDirectories(lake.resolve(dir));
        Files.writeString(bundle.resolve("bundle.yaml"), "name: " + dir + "\nversion: \"1.0.0\"\n");
        Files.writeString(bundle.resolve("BUILD.bazel"), build);
        Path source = bundle.resolve("v1/" + dir + ".proto");
        Files.createDirectories(source.getParent());
        Files.writeString(source, proto);
    }
}
