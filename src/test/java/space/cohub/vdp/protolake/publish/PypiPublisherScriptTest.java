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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Executes the pypi_publisher template in local-repo mode and pins the simple
 * index layout pip resolves from: a per-project directory named per PEP 503
 * and the wheel renamed to a PEP-427 filename. The bazel output basename
 * ({@code <target>_bundle.whl}) is not a parseable wheel filename — keeping it
 * would make {@code pip install <pkg>==<version> --index-url file://<repo>}
 * fail to resolve.
 *
 * <p>Also pins where the wheel goes: {@code --repo}, else {@code --index-url},
 * else {@code $PYPI_REPO}, else {@code ~/.cache/pip/simple}. The publish target gazelle emits passes no
 * {@code --repo}, so {@code $PYPI_REPO} (protolakew's {@code --pypi-repo}, CI's
 * registry URL) is the only way a remote registry reaches the script.
 *
 * <p>Skipped when {@code python3} is not on the PATH (the script is stdlib-only,
 * any python3 works).
 */
class PypiPublisherScriptTest {

    private static final String TEMPLATE_DIR = "templates/tools/publish/";

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
        Path wheel = Files.writeString(tempDir.resolve("vdp_py_bundle_bundle.whl"), "fake");

        ProcessResult result = runPublisher(wheel, "company_user_proto", "1.2.3");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        Path published = repo.resolve("company-user-proto")
                .resolve("company_user_proto-1.2.3-py3-none-any.whl");
        assertThat(published).exists();
        assertThat(repo.resolve("company-user-proto/vdp_py_bundle_bundle.whl")).doesNotExist();

        // Both index levels must reference the names pip requests
        assertThat(repo.resolve("company-user-proto/index.html"))
                .content().contains("company_user_proto-1.2.3-py3-none-any.whl");
        assertThat(repo.resolve("index.html")).content().contains("company-user-proto/");
    }

    @Test
    void localRepo_normalizesProjectNamePerPep503() throws Exception {
        Path wheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "fake");

        ProcessResult result = runPublisher(wheel, "Company.User_Proto", "0.4.0");

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        // Directory: PEP 503 (runs of -_. -> "-"); filename: PEP 427 (-> "_")
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
    }

    @Test
    void pypiRepoEnv_isTheRepoWhenNoRepoFlagIsGiven() throws Exception {
        Path wheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "fake");

        ProcessResult result = run(wheel, "company_user_proto", "0.4.0", List.of(),
                Map.of("PYPI_REPO", repo.toString()));

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
    }

    @Test
    void repoFlag_winsOverPypiRepoEnv() throws Exception {
        Path wheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "fake");
        Path envRepo = tempDir.resolve("env-repo");

        ProcessResult result = run(wheel, "company_user_proto", "0.4.0",
                List.of("--repo", repo.toString()), Map.of("PYPI_REPO", envRepo.toString()));

        assertThat(result.exitCode).as("publisher output:\n%s", result.output).isZero();
        assertThat(repo.resolve("company-user-proto")
                .resolve("company_user_proto-0.4.0-py3-none-any.whl")).exists();
        assertThat(envRepo).doesNotExist();
    }

    @Test
    void pypiRepoEnvUrl_uploadsToThatRegistry() throws Exception {
        Path wheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "fake-wheel");
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " wheel=" + body.contains("filename=\"user_bundle.whl\""));
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
            assertThat(requests).containsExactly(
                    "POST /pypi/ auth=Bearer test-token wheel=true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void indexUrlFlag_winsOverPypiRepoEnv() throws Exception {
        Path wheel = Files.writeString(tempDir.resolve("user_bundle.whl"), "fake-wheel");
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
