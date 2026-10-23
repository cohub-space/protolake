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
    void check_indentedPublicImport_isReportedAtItsKeyword() throws IOException {
        bundle("api", "syntax = \"proto3\";\n\n\n  import public \"cohub/api/v1/exposure.proto\";\n",
                "java_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).singleElement().satisfies(error -> {
            assertThat(error.getLine()).isEqualTo(4);
            assertThat(error.getColumn()).isEqualTo(3);
        });
    }

    @Test
    void check_singleQuotedImportAfterAnotherStatement_isRefusedAtItsKeyword() throws IOException {
        bundle("api", "syntax = \"proto3\"; import 'cohub/api/v1/exposure.proto';\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).singleElement().satisfies(error -> {
            assertThat(error.getLine()).isEqualTo(1);
            assertThat(error.getColumn()).isEqualTo(20);
        });
    }

    @Test
    void check_importInsideABlockComment_isNotRefused() throws IOException {
        bundle("api", "syntax = \"proto3\";\n/*\nimport \"cohub/api/v1/exposure.proto\";\n*/\n",
                "java_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).isEmpty();
    }

    @Test
    void check_bundleImportingAProtoThatImportsIt_isRefusedAtItsOwnImport() throws IOException {
        // lib builds no packages, so only app's packages would carry lib's generated code.
        bundle("lib", IMPORTING, "proto_library(\n)\n");
        bundle("app", "syntax = \"proto3\";\npackage app.v1;\nimport \"lib/v1/lib.proto\";\n", "python_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).singleElement().satisfies(error -> {
            assertThat(error.getFile()).isEqualTo("app/v1/app.proto");
            assertThat(error.getLine()).isEqualTo(3);
            assertThat(error.getColumn()).isEqualTo(1);
            assertThat(error.getMessage()).contains("Bundle app builds Python packages",
                    "app/v1/app.proto imports lib/v1/lib.proto, which imports cohub/api/v1/exposure.proto",
                    "app/bundle.yaml");
        });
    }

    @Test
    void check_bundleImportingASiblingOfAProtoThatImportsIt_isRefused() throws IOException {
        // Gazelle compiles lib/v1 as one target, so app's packages carry lib.proto's code too.
        bundle("lib", IMPORTING, "proto_library(\n)\n");
        Files.writeString(lake.resolve("lib/v1/types.proto"), "syntax = \"proto3\";\npackage api.v1;\n");
        bundle("app", "syntax = \"proto3\";\nimport \"lib/v1/types.proto\";\n", "es_proto_compile(\n)\n");

        assertThat(check.check(lake, "app")).singleElement().satisfies(error -> assertThat(error.getMessage())
                .contains("app/v1/app.proto imports lib/v1/types.proto, which Bazel compiles with lib/v1/lib.proto, "
                        + "which imports cohub/api/v1/exposure.proto"));
    }

    @Test
    void check_importSpelledWithByteEscapes_isFollowedToTheFileItNames() throws IOException {
        // protoc resolves "lib/v1/\303\251.proto" to lib/v1/é.proto, which imports the options.
        bundle("lib", "syntax = \"proto3\";\n", "proto_library(\n)\n");
        Files.writeString(lake.resolve("lib/v1/é.proto"), IMPORTING);
        bundle("app", "syntax = \"proto3\";\nimport \"lib/v1/\\303\\251.proto\";\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, "app")).singleElement().satisfies(error -> assertThat(error.getMessage())
                .contains("app/v1/app.proto imports lib/v1/é.proto, which imports cohub/api/v1/exposure.proto"));
    }

    @Test
    void check_importOfAPathThatIsNotUtf8_reachesNoFile() throws IOException {
        // \uD83D alone spells bytes that are not UTF-8, so protoc finds no file for them. Read with
        // replacement they would name lib/v1/\uFFFD.proto, which exists here and imports the options.
        bundle("lib", "syntax = \"proto3\";\n", "proto_library(\n)\n");
        Files.writeString(lake.resolve("lib/v1/\uFFFD.proto"), IMPORTING);
        bundle("app", "syntax = \"proto3\";\nimport \"lib/v1/\\uD83D.proto\";\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, "app")).isEmpty();
    }

    @Test
    void check_importOfAPathNoFileSystemHolds_reachesNoFile() throws IOException {
        // \0 decodes to valid UTF-8, but no file name holds a NUL: protoc and buf cannot open
        // that import, and resolving it must not fail the check either.
        bundle("app", "syntax = \"proto3\";\nimport \"lib/v1/\\0.proto\";\n", "java_grpc_library(\n)\n");

        assertThat(check.check(lake, "app")).isEmpty();
    }

    @Test
    void check_bundleWhoseImportsNeverReachIt_isUntouched() throws IOException {
        bundle("lib", "syntax = \"proto3\";\nimport \"google/api/annotations.proto\";\n", "proto_library(\n)\n");
        bundle("app", "syntax = \"proto3\";\nimport \"lib/v1/lib.proto\";\nimport \"missing/v1/x.proto\";\n",
                "java_grpc_library(\n)\n");

        assertThat(check.check(lake, ".")).isEmpty();
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
