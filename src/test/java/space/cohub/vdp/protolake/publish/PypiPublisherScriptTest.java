package space.cohub.vdp.protolake.publish;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Executes the pypi_publisher template and pins the name a wheel is published
 * under: the PEP-427 filename read from the wheel's own {@code .dist-info}. The
 * bazel output basename ({@code <target>_bundle.whl}) is not a parseable wheel
 * filename — in a local simple index pip never resolves it, and a registry
 * rejects the upload (Artifact Registry answered 400 for cohub-vdp-proto
 * 2.64.0). Local mode copies the wheel under that name into a per-project
 * directory named per PEP 503; remote mode uploads a copy staged under it, by
 * twine or the stdlib fallback.
 *
 * <p>Also pins the refusals that run before anything is written or uploaded: a
 * file that is not a wheel, a wheel of another distribution, and a wheel built
 * at another version than the bundle's.
 *
 * <p>And where the wheel goes: {@code --repo}, else {@code --index-url}, else
 * {@code $PYPI_REPO}, else {@code ~/.cache/pip/simple}. The publish target
 * gazelle emits passes neither flag, so {@code $PYPI_REPO} (protolakew's
 * {@code --pypi-repo}, CI's registry URL) is the only way a remote registry
 * reaches the script.
 *
 * <p>Skipped when {@code python3} is not on the PATH (the script is stdlib-only,
 * any python3 works).
 */
class PypiPublisherScriptTest {

    private static final String TEMPLATE_DIR = "templates/tools/publish/";
    private static final Pattern UPLOAD_FILENAME = Pattern.compile("filename=\"([^\"]+)\"");

    @TempDir
    Path tempDir;

    private Path script;
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available on PATH");

        // Stage the script next to its publisher_utils import, as in a lake's tools/
        Path tools = Files.createDirectories(tempDir.resolve("tools"));
        script = copyTemplate("pypi_publisher_generated.py", tools);
        copyTemplate("publisher_utils_generated.py", tools);
        repo = tempDir.resolve("repo");
    }

    @Test
    void localRepo_renamesBazelWheelToPep427_underPep503ProjectDir() throws Exception {
        // Bazel names the wheel after the target, not the distribution
        Path wheel = wheel("vdp_py_bundle_bundle.whl", "company_user_proto", "1.2.3");

        ProcessResult result = runPublisher(wheel, "company_user_proto", "1.2.3");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        Path published = repo.resolve("company-user-proto")
                .resolve("company_user_proto-1.2.3-py3-none-any.whl");
        assertThat(published).exists().hasSameBinaryContentAs(wheel);
        assertThat(repo.resolve("company-user-proto/vdp_py_bundle_bundle.whl")).doesNotExist();

        // Both index levels must reference the names pip requests
        assertThat(repo.resolve("company-user-proto/index.html"))
                .content().contains("company_user_proto-1.2.3-py3-none-any.whl");
        assertThat(repo.resolve("index.html")).content().contains("company-user-proto/");
    }

    @Test
    void localRepo_normalizesProjectNamePerPep503() throws Exception {
        // setuptools escapes the distribution in the .dist-info name
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0");

        ProcessResult result = runPublisher(wheel, "Company.User_Proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        // Directory: PEP 503 (runs of -_. -> "-"); filename: PEP 427, from the wheel
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
    }

    @Test
    void severalTags_compressIntoTheFilename() throws Exception {
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0",
                "py2-none-any", "py3-none-any");

        ProcessResult result = runPublisher(wheel, "company_user_proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py2.py3-none-any.whl")).exists();
    }

    @Test
    void pypiRepoEnv_isTheRepoWhenNoRepoFlagIsGiven() throws Exception {
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0");

        ProcessResult result = run(wheel, "company_user_proto", "0.4.0", List.of(),
                Map.of("PYPI_REPO", repo.toString()));

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
    }

    @Test
    void repoFlag_winsOverPypiRepoEnv() throws Exception {
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0");
        Path envRepo = tempDir.resolve("env-repo");

        ProcessResult result = run(wheel, "company_user_proto", "0.4.0",
                List.of("--repo", repo.toString()), Map.of("PYPI_REPO", envRepo.toString()));

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
        assertThat(envRepo).doesNotExist();
    }

    @Test
    void pypiRepoEnvUrl_uploadsThePep427NamedWheelToThatRegistry() throws Exception {
        Path wheel = wheel("vdp_py_bundle_bundle.whl", "company_user_proto", "0.4.0");
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            Matcher filename = UPLOAD_FILENAME.matcher(body);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " file=" + (filename.find() ? filename.group(1) : "<none>"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pypi";
            // An empty PATH hides any installed twine, so the stdlib upload runs.
            Path emptyPath = Files.createDirectories(tempDir.resolve("empty-path"));
            ProcessResult result = run(wheel, "company_user_proto", "0.4.0", List.of(),
                    Map.of("PYPI_REPO", url, "REGISTRY_TOKEN", "test-token",
                            "PATH", emptyPath.toString()));

            assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
            assertThat(requests).containsExactly("POST /pypi/ auth=Bearer test-token"
                    + " file=company_user_proto-0.4.0-py3-none-any.whl");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteRegistry_twineUploadsThePep427NamedWheel() throws Exception {
        Path wheel = wheel("vdp_py_bundle_bundle.whl", "cohub_vdp_proto", "2.64.0");
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path captured = tempDir.resolve("captured");
        // A fake twine on an otherwise empty PATH: it answers --version, and for
        // an upload records the credentials and keeps a copy of each wheel it
        // was handed, under the name it was handed.
        Path twine = Files.writeString(bin.resolve("twine"), String.join("\n",
                "#!/bin/sh",
                "[ \"$1\" = \"--version\" ] && exit 0",
                "/bin/mkdir -p '" + captured + "'",
                "echo \"$TWINE_USERNAME:$TWINE_PASSWORD\" > '" + captured + "/creds'",
                "for arg in \"$@\"; do",
                "  case \"$arg\" in *.whl) /bin/cp \"$arg\" '" + captured + "/' ;; esac",
                "done",
                ""));
        assertThat(twine.toFile().setExecutable(true)).isTrue();

        ProcessResult result = run(wheel, "cohub_vdp_proto", "2.64.0", List.of(),
                Map.of("PYPI_REPO", "https://registry.invalid/python-internal/",
                        "REGISTRY_TOKEN", "test-token", "PATH", bin.toString()));

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        try (var uploaded = Files.list(captured)) {
            assertThat(uploaded.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("cohub_vdp_proto-2.64.0-py3-none-any.whl", "creds");
        }
        assertThat(captured.resolve("cohub_vdp_proto-2.64.0-py3-none-any.whl"))
                .hasSameBinaryContentAs(wheel);
        assertThat(captured.resolve("creds")).content()
                .isEqualTo("oauth2accesstoken:test-token\n");
    }

    @Test
    void indexUrlFlag_winsOverPypiRepoEnv() throws Exception {
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0");
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            paths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path emptyPath = Files.createDirectories(tempDir.resolve("empty-path"));
            ProcessResult result = run(wheel, "company_user_proto", "0.4.0",
                    List.of("--index-url", base + "/flag-registry"),
                    Map.of("PYPI_REPO", base + "/env-registry", "REGISTRY_TOKEN", "test-token",
                            "PATH", emptyPath.toString()));

            assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
            assertThat(paths).containsExactly("/flag-registry/");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localRepoFlag_winsOverIndexUrl() throws Exception {
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.4.0");

        // An unreachable URL: reaching it at all would fail the run.
        ProcessResult result = run(wheel, "company_user_proto", "0.4.0",
                List.of("--repo", repo.toString(), "--index-url", "http://127.0.0.1:9/never"),
                Map.of());

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
    }

    @Test
    void fileThatIsNotAWheel_isRefusedBeforeAnythingIsWritten() throws Exception {
        Path notAWheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "not a zip");

        ProcessResult result = runPublisher(notAWheel, "company_user_proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isNotZero();
        assertThat(result.output).contains("is not a wheel");
        assertThat(repo).doesNotExist();
    }

    @Test
    void wheelOfAnotherDistribution_isRefusedBeforeAnythingIsWritten() throws Exception {
        Path wheel = wheel("user_bundle.whl", "other_proto", "0.4.0");

        ProcessResult result = runPublisher(wheel, "company_user_proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isNotZero();
        assertThat(result.output).contains("holds distribution 'other_proto'");
        assertThat(repo).doesNotExist();
    }

    @Test
    void wheelBuiltAtAnotherVersion_isRefusedBeforeAnythingIsWritten() throws Exception {
        // A stale build: the wheel predates the bundle's version bump
        Path wheel = wheel("user_bundle.whl", "company_user_proto", "0.3.9");

        ProcessResult result = runPublisher(wheel, "company_user_proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isNotZero();
        assertThat(result.output).contains("carries version '0.3.9'").contains("'0.4.0'");
        assertThat(repo).doesNotExist();
    }

    /**
     * A minimal wheel as setuptools lays one out: a module, and one
     * {@code <distribution>-<version>.dist-info} holding METADATA, WHEEL (with
     * its tags) and RECORD. Written under {@code fileName}, the name bazel gives it.
     */
    private Path wheel(String fileName, String distribution, String version, String... tags)
            throws IOException {
        String distInfo = distribution + "-" + version + ".dist-info/";
        StringBuilder wheelFile = new StringBuilder(
                "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\n");
        for (String tag : tags.length == 0 ? new String[] {"py3-none-any"} : tags) {
            wheelFile.append("Tag: ").append(tag).append('\n');
        }
        Path path = tempDir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            putEntry(zip, "company/__init__.py", "");
            putEntry(zip, distInfo + "METADATA",
                    "Metadata-Version: 2.1\nName: " + distribution + "\nVersion: " + version + "\n");
            putEntry(zip, distInfo + "WHEEL", wheelFile.toString());
            putEntry(zip, distInfo + "RECORD", "");
        }
        return path;
    }

    private static void putEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Path copyTemplate(String name, Path targetDir) throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(TEMPLATE_DIR + name)) {
            assertThat(in).as("template resource %s", name).isNotNull();
            Path target = targetDir.resolve(name);
            Files.copy(in, target);
            return target;
        }
    }

    private ProcessResult runPublisher(Path wheel, String packageName, String version)
            throws Exception {
        return run(wheel, packageName, version, List.of("--repo", repo.toString()), Map.of());
    }

    private ProcessResult run(Path wheel, String packageName, String version,
            List<String> extraArgs, Map<String, String> env) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                python3(), script.toString(), wheel.toString(),
                "--package-name", packageName,
                "--version", version));
        command.addAll(extraArgs);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true);
        builder.environment().remove("PYPI_REPO");
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS))
                .as("publisher timed out; output:\n%s", output).isTrue();
        return new ProcessResult(process.exitValue(), output);
    }

    /** python3's absolute path, so a test can run the script with a narrowed PATH. */
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

    private record ProcessResult(int exitCode, String output) {
    }
}
