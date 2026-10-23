package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The protoc plugins behind the API specs are pinned twice: the Dockerfile
 * builds a version from source, checked against a SHA-256 per platform, and
 * templates/tools/api_specs.bzl names the same version and binary digests,
 * which key Bazel's cache and which the tool checks against the installed
 * binary. A bump in one file without the other fails every spec build; this
 * fails first.
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
    void dockerfileAndTemplate_pinTheSameBinaries() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String template = template("templates/tools/api_specs.bzl");

        assertThat(digests(template, "PROTOC_GEN_OPENAPIV2_SHA256")).containsExactly(
                arg(dockerfile, "PROTOC_GEN_OPENAPIV2_SHA256_AMD64"),
                arg(dockerfile, "PROTOC_GEN_OPENAPIV2_SHA256_ARM64"));
        assertThat(digests(template, "PROTOC_GEN_CONNECT_OPENAPI_SHA256")).containsExactly(
                arg(dockerfile, "PROTOC_GEN_CONNECT_OPENAPI_SHA256_AMD64"),
                arg(dockerfile, "PROTOC_GEN_CONNECT_OPENAPI_SHA256_ARM64"));
    }

    /**
     * One SHA-256 check of a built plugin binary: the shell variable the
     * platform's case branch sets from its pin, the file, and the commands
     * the check must come between, the build and the first use.
     */
    private record Check(String variable, String pin, String file, String build, String use) {
    }

    private static final List<Check> CHECKS = List.of(
            new Check("OPENAPIV2_SHA256", "PROTOC_GEN_OPENAPIV2_SHA256", "${BIN}/protoc-gen-openapiv2",
                    "\"github.com/grpc-ecosystem/grpc-gateway/v2/protoc-gen-openapiv2@v${PROTOC_GEN_OPENAPIV2_VERSION}\"",
                    "mv \"${BIN}/protoc-gen-openapiv2\""),
            new Check("CONNECT_SHA256", "PROTOC_GEN_CONNECT_OPENAPI_SHA256", "${BIN}/protoc-gen-connect-openapi",
                    "\"github.com/sudorandom/protoc-gen-connect-openapi@v${PROTOC_GEN_CONNECT_OPENAPI_VERSION}\"",
                    "mv \"${BIN}/protoc-gen-connect-openapi\""));

    @Test
    void dockerfile_checksEveryPluginOnEveryPlatform() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String install = pluginInstall(dockerfile);

        for (String arch : List.of("amd64", "arm64")) {
            String branch = caseBranch(install, arch);
            for (Check check : CHECKS) {
                String pin = check.pin() + "_" + arch.toUpperCase(java.util.Locale.ROOT);
                assertThat(arg(dockerfile, pin)).as("ARG %s", pin).matches("[0-9a-f]{64}");
                assertThat(branch).as("the %s branch sets %s from %s", arch, check.variable(), pin)
                        .contains(check.variable() + "=\"${" + pin + "}\"");
            }
        }
        for (Check check : CHECKS) {
            String command = "echo \"${" + check.variable() + "}  " + check.file() + "\" | sha256sum -c -";
            int at = install.indexOf(command);
            assertThat(at).as("the install checks %s: %s", check.file(), command).isNotNegative();
            assertThat(install.indexOf(command, at + 1)).as("one check of %s", check.file()).isNegative();
            assertThat(install.indexOf(check.build())).as("%s is built before its check", check.file())
                    .isNotNegative().isLessThan(at);
            assertThat(install.indexOf(check.use(), at)).as("%s is used only after its check", check.file())
                    .isGreaterThan(at);
            assertThat(install.substring(0, at)).as("%s is not used before its check", check.file())
                    .doesNotContain(check.use());
        }
    }

    @Test
    void dockerfile_buildsThePluginsWithAPinnedGoAndShipsOnlyThoseBuilds() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        // A floating Go would move the digests, and a toolchain older than the
        // standard library fixes would ship the vulnerabilities the source
        // build exists to avoid; the version is in the stage's base image.
        assertThat(dockerfile).containsPattern(
                "(?m)^FROM --platform=\\$BUILDPLATFORM golang:\\d+\\.\\d+\\.\\d+-\\S+ AS plugins$");
        assertThat(pluginInstall(dockerfile)).contains("GOTOOLCHAIN=local");
        // The image takes the plugins only from that stage, after their checks.
        assertThat(dockerfile).contains(
                "COPY --from=plugins /out/protoc-gen-openapiv2 /out/protoc-gen-connect-openapi /usr/local/bin/");
        assertThat(dockerfile).doesNotContain("releases/download/v${PROTOC_GEN");
    }

    /** The plugins stage, from its FROM to the next stage. */
    private static String pluginInstall(String dockerfile) {
        int marker = dockerfile.indexOf("OPENAPIV2_SHA256=");
        assertThat(marker).as("the plugin install in the Dockerfile").isNotNegative();
        int start = dockerfile.lastIndexOf("\nFROM ", marker);
        int end = dockerfile.indexOf("\nFROM ", marker);
        assertThat(start).isNotNegative();
        return dockerfile.substring(start, end < 0 ? dockerfile.length() : end);
    }

    /** The case branch of the install for one TARGETARCH, up to its {@code ;;}. */
    private static String caseBranch(String install, String arch) {
        Matcher branch = Pattern.compile("(?s)\\b" + arch + "\\)(.*?);;").matcher(install);
        assertThat(branch.find()).as("the %s branch of the plugin install", arch).isTrue();
        return branch.group(1);
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

    /** The quoted digests of a list constant in api_specs.bzl, in order. */
    private static java.util.List<String> digests(String template, String name) {
        Matcher block = Pattern.compile("(?ms)^" + name + " = \\[(.*?)^\\]").matcher(template);
        assertThat(block.find()).as("%s in api_specs.bzl", name).isTrue();
        Matcher digest = Pattern.compile("\"([0-9a-f]{64})\"").matcher(block.group(1));
        java.util.List<String> out = new java.util.ArrayList<>();
        while (digest.find()) {
            out.add(digest.group(1));
        }
        return out;
    }

    private static String template(String path) throws IOException {
        try (InputStream in = ApiSpecPinsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
