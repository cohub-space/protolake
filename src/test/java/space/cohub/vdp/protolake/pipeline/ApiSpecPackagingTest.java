package space.cohub.vdp.protolake.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A bundle's API specs ship inside its language packages, stamped with the
 * version the package is published under: the jar at
 * {@code META-INF/api-specs/}, the npm package at {@code api-specs/}, and the
 * wheel in its {@code .dist-info/api-specs/}, listed in RECORD. Runs the
 * bundler templates (stdlib-only Python) the way the bundle rules do.
 *
 * <p>Skipped when {@code python3} is not on the PATH.
 */
class ApiSpecPackagingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path bundler;
    private Path specs;
    private Path bundleYaml;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available on PATH");
        bundler = Files.createDirectories(tmp.resolve("tools/bundler"));
        for (String name : List.of("jar_bundler_generated.py", "npm_bundler_generated.py",
                "wheel_builder_generated.py", "api_spec_files_generated.py")) {
            Files.writeString(bundler.resolve(name), resource("templates/tools/bundler/" + name));
        }
        specs = Files.createDirectories(tmp.resolve("demo_api_specs"));
        // As the aspect writes them: the version left for the bundler, unless the protos set one.
        Files.writeString(specs.resolve("demo.openapi.json"),
                "{\"swagger\": \"2.0\", \"info\": {\"title\": \"demo\", \"version\": \"version not set\"}}");
        Files.writeString(specs.resolve("demo.asyncapi.json"),
                "{\"asyncapi\": \"3.1.0\", \"info\": {\"title\": \"demo\", \"version\": \"3.0.0\"}}");
        bundleYaml = tmp.resolve("bundle.yaml");
        Files.writeString(bundleYaml, "name: demo\nversion: \"2.5.0\" # x-release-please-version\n");
    }

    @Test
    void jar_carriesTheSpecsUnderMetaInf_stampedWithTheBundleVersion() throws Exception {
        Path jar = tmp.resolve("demo.jar");

        run("jar_bundler_generated.py", "--output", jar.toString(), "--group-id", "com.example",
                "--artifact-id", "demo-proto", "--bundle-yaml", bundleYaml.toString(),
                "--api-specs-dir", specs.toString());

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertThat(json(zip, "META-INF/api-specs/demo.openapi.json").at("/info/version").asText())
                    .isEqualTo("2.5.0");
            // A version the protos set stays.
            assertThat(json(zip, "META-INF/api-specs/demo.asyncapi.json").at("/info/version").asText())
                    .isEqualTo("3.0.0");
        }
    }

    @Test
    void jar_withoutSpecs_carriesNone() throws Exception {
        Path jar = tmp.resolve("demo.jar");
        Path empty = Files.createDirectories(tmp.resolve("empty_api_specs"));

        run("jar_bundler_generated.py", "--output", jar.toString(), "--group-id", "com.example",
                "--artifact-id", "demo-proto", "--bundle-yaml", bundleYaml.toString(),
                "--api-specs-dir", empty.toString());

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).noneMatch(name -> name.contains("api-specs"));
        }
    }

    @Test
    void npmPackage_carriesTheSpecsUnderApiSpecs_andPublishesThem() throws Exception {
        Path tgz = tmp.resolve("demo.tgz");

        run("npm_bundler_generated.py", "--output", tgz.toString(), "--package-name", "@example/demo-proto",
                "--bundle-yaml", bundleYaml.toString(), "--api-specs-dir", specs.toString());

        Map<String, byte[]> files = untar(tgz);
        assertThat(JSON.readTree(files.get("example-demo-proto/api-specs/demo.openapi.json"))
                .at("/info/version").asText()).isEqualTo("2.5.0");
        assertThat(files).containsKey("example-demo-proto/api-specs/demo.asyncapi.json");
        // npm packs only the files package.json lists.
        assertThat(JSON.readTree(files.get("example-demo-proto/package.json")).path("files"))
                .extracting(JsonNode::asText).contains("api-specs/*.json");
    }

    @Test
    void wheel_carriesTheSpecsInItsDistInfo_listedInRecord() throws Exception {
        Path wheel = tmp.resolve("demo_proto-2.5.0-py3-none-any.whl");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(wheel))) {
            entry(zip, "demo/__init__.py", "");
            entry(zip, "demo_proto-2.5.0.dist-info/WHEEL", "Wheel-Version: 1.0\nTag: py3-none-any\n");
            entry(zip, "demo_proto-2.5.0.dist-info/RECORD",
                    "demo/__init__.py,sha256=47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU,0\n"
                            + "demo_proto-2.5.0.dist-info/WHEEL,,\ndemo_proto-2.5.0.dist-info/RECORD,,\n");
        }

        runPython("import sys; sys.path.insert(0, sys.argv[1]); import wheel_builder_generated as w, "
                + "api_spec_files_generated as a; w.add_dist_info_files(sys.argv[2], a.api_spec_files([sys.argv[3]], '2.5.0'))",
                bundler.toString(), wheel.toString(), specs.toString());

        try (ZipFile zip = new ZipFile(wheel.toFile())) {
            String specName = "demo_proto-2.5.0.dist-info/api-specs/demo.openapi.json";
            byte[] spec = zip.getInputStream(zip.getEntry(specName)).readAllBytes();
            assertThat(JSON.readTree(spec).at("/info/version").asText()).isEqualTo("2.5.0");
            List<String> record = List.of(new String(zip.getInputStream(
                    zip.getEntry("demo_proto-2.5.0.dist-info/RECORD")).readAllBytes(), StandardCharsets.UTF_8)
                    .split("\n"));
            String digest = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(spec));
            assertThat(record).contains(specName + ",sha256=" + digest + "," + spec.length);
            assertThat(record.get(record.size() - 1)).isEqualTo("demo_proto-2.5.0.dist-info/RECORD,,");
            assertThat(record).filteredOn(line -> line.startsWith("demo_proto-2.5.0.dist-info/RECORD,")).hasSize(1);
            assertThat(zip.getEntry("demo/__init__.py")).isNotNull();
        }
    }

    private void run(String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(python3(), bundler.resolve(script).toString()));
        command.addAll(List.of(args));
        execute(command);
    }

    private void runPython(String code, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(python3(), "-B", "-c", code));
        command.addAll(List.of(args));
        execute(command);
    }

    private void execute(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(tmp.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("timed out:\n%s", output).isTrue();
        assertThat(process.exitValue()).as("output:\n%s", output).isZero();
    }

    private static JsonNode json(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertThat(entry).as(name).isNotNull();
        return JSON.readTree(zip.getInputStream(entry).readAllBytes());
    }

    private static Map<String, byte[]> untar(Path tgz) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (InputStream in = Files.newInputStream(tgz);
             TarArchiveInputStream entries = new TarArchiveInputStream(new GzipCompressorInputStream(in))) {
            TarArchiveEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                if (entry.isFile()) {
                    files.put(entry.getName(), entries.readAllBytes());
                }
            }
        }
        return files;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = ApiSpecPackagingTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String python3() throws Exception {
        Process process = new ProcessBuilder("python3", "-c", "import sys; print(sys.executable)")
                .redirectErrorStream(true).start();
        String path = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return path;
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version").start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
