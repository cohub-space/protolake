package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The protoc plugins behind the API specs are pinned twice: the Dockerfile
 * installs a version, checked against a SHA-256 per platform, and
 * templates/tools/api_specs.bzl names the same version, which keys Bazel's
 * cache and which the tool checks against the installed binary. A bump in one
 * file without the other fails every spec build; this fails first.
 */
class ApiSpecPinsTest {

    @Test
    void dockerfileAndTemplate_pinTheSamePluginVersions() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String template = template("templates/tools/api_specs.bzl");

        assertThat(arg(dockerfile, "PROTOC_GEN_OPENAPIV2_VERSION"))
                .isEqualTo(constant(template, "PROTOC_GEN_OPENAPIV2_VERSION"));
        assertThat(arg(dockerfile, "PROTOC_GEN_CONNECT_OPENAPI_VERSION"))
                .isEqualTo(constant(template, "PROTOC_GEN_CONNECT_OPENAPI_VERSION"));
    }

    @Test
    void dockerfile_checksEveryPluginOnEveryPlatform() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        for (String plugin : new String[] {"PROTOC_GEN_OPENAPIV2", "PROTOC_GEN_CONNECT_OPENAPI"}) {
            for (String arch : new String[] {"AMD64", "ARM64"}) {
                assertThat(arg(dockerfile, plugin + "_SHA256_" + arch)).matches("[0-9a-f]{64}");
            }
        }
        assertThat(dockerfile).contains("sha256sum -c -");
    }

    private static String arg(String dockerfile, String name) {
        Matcher matcher = Pattern.compile("(?m)^ARG " + name + "=(\\S+)$").matcher(dockerfile);
        assertThat(matcher.find()).as("ARG %s in the Dockerfile", name).isTrue();
        return matcher.group(1);
    }

    private static String constant(String template, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + " = \"([^\"]+)\"$").matcher(template);
        assertThat(matcher.find()).as("%s in api_specs.bzl", name).isTrue();
        return matcher.group(1);
    }

    private static String template(String path) throws IOException {
        try (InputStream in = ApiSpecPinsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
