package space.cohub.vdp.protolake.initializer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cohub_api module is assembled from three places: its skeleton in
 * src/main/bazel/cohub_api, the proto in src/main/proto (copied in by the
 * Dockerfile), and the names lakes are rendered with (CohubApiModule, the root
 * BUILD.bazel's resolve directive). A rename in one of them breaks every lake
 * that imports cohub/api/v1/exposure.proto at its next build; this fails first.
 */
class CohubApiModuleTest {

    private static final Path SKELETON = Path.of("src/main/bazel/cohub_api");
    private static final String IMAGE_PATH = "/opt/protolake/bazel/cohub_api";

    @Test
    void skeleton_declaresTheModuleLakesDependOn() throws IOException {
        String module = Files.readString(SKELETON.resolve("MODULE.bazel"));

        assertThat(module).contains("name = \"" + CohubApiModule.NAME + "\"",
                "version = \"" + CohubApiModule.VERSION + "\"");
    }

    @Test
    void skeleton_declaresTheTargetImportsResolveTo() throws IOException {
        // @cohub_api//cohub/api/v1:exposure_proto, compiling cohub/api/v1/exposure.proto.
        assertThat(CohubApiModule.LABEL).isEqualTo("@" + CohubApiModule.NAME + "//cohub/api/v1:exposure_proto");
        String build = Files.readString(SKELETON.resolve("cohub/api/v1/BUILD.bazel"));

        assertThat(build).contains("name = \"exposure_proto\"", "srcs = [\"exposure.proto\"]");
        assertThat(CohubApiModule.IMPORT_PATHS).containsExactly("cohub/api/v1/exposure.proto");
    }

    @Test
    void dockerfile_shipsTheSkeletonAndProtolakesOwnProto() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile).contains("COPY src/main/bazel/cohub_api/ " + IMAGE_PATH + "/");
        for (String importPath : CohubApiModule.IMPORT_PATHS) {
            assertThat(Path.of("src/main/proto").resolve(importPath)).isRegularFile();
            assertThat(dockerfile).contains("COPY src/main/proto/" + importPath + " " + IMAGE_PATH + "/" + importPath);
        }
    }

    @Test
    void imagePath_isTheDefault() {
        assertThat(new CohubApiModule(IMAGE_PATH).path()).isEqualTo(Path.of(IMAGE_PATH));
    }

    @Test
    void rootBuild_resolvesTheImportToTheModule() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/bazel/BUILD.root")) {
            assertThat(in).isNotNull();
            String rootBuild = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            for (String importPath : CohubApiModule.IMPORT_PATHS) {
                assertThat(rootBuild).contains("# gazelle:resolve proto " + importPath + " " + CohubApiModule.LABEL);
            }
        }
    }
}
