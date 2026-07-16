package space.cohub.vdp.protolake.publish;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        List<String> command = new ArrayList<>(List.of(
                "python3", script.toString(), wheel.toString(),
                "--package-name", packageName,
                "--version", version,
                "--repo", repo.toString()));
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS))
                .as("publisher timed out; output:\n%s", output).isTrue();
        return new ProcessResult(process.exitValue(), output);
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
