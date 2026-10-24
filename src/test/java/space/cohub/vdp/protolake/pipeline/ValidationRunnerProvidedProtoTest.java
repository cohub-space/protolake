package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protolake.v1.BuildOperationMetadata;
import protolake.v1.Lake;
import space.cohub.vdp.protolake.initializer.CohubApiModule;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.model.ValidationResult;
import space.cohub.vdp.protolake.util.buf.BufCommand;
import space.cohub.vdp.protolake.util.git.GitCommand;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation, against the real buf, of a lake whose protos import
 * cohub/api/v1/exposure.proto, which the lake neither holds nor can list in
 * its buf.yaml deps: buf resolves it from the image's copy, leaves it out of
 * lint and the breaking check, and the lake's own files are left as they
 * were. Runs only where a {@code buf} binary is on PATH.
 */
class ValidationRunnerProvidedProtoTest {

    private static final String DEMO = """
            syntax = "proto3";

            package demo.v1;

            import "cohub/api/v1/exposure.proto";

            service DemoService {
              rpc Chat(stream ChatRequest) returns (stream ChatResponse) {
                option (cohub.api.v1.exposure) = {websocket: true};
              }
            }

            message ChatRequest {
              string text = 1;
              string room = 2;
            }

            message ChatResponse {
              string text = 1;
            }
            """;

    @TempDir
    Path tmp;

    private Path lake;
    private ValidationRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(onPath("buf"), "buf not on PATH");
        Path module = tmp.resolve("module");
        Path exposure = module.resolve("cohub/api/v1/exposure.proto");
        Files.createDirectories(exposure.getParent());
        Files.copy(Path.of("src/main/proto/cohub/api/v1/exposure.proto"), exposure);

        Path base = Files.createDirectories(tmp.resolve("base"));
        lake = Files.createDirectories(base.resolve("lake"));
        Files.writeString(lake.resolve("buf.yaml"), "version: v1\nlint:\n  use:\n    - STANDARD\n");
        Files.createDirectories(lake.resolve("demo/v1"));
        Files.writeString(lake.resolve("demo/v1/demo.proto"), DEMO);

        BufCommand buf = new BufCommand();
        // BufCommand's config fields are package-private to util.buf.
        set(buf, "bufCommand", "buf");
        set(buf, "timeoutSeconds", 120);
        runner = new ValidationRunner();
        runner.bufCommand = buf;
        runner.gitCommand = new GitCommand();
        runner.cohubApiModule = new CohubApiModule(module.toString());
        runner.basePath = base.toString();
    }

    @Test
    void validateLake_resolvesTheImportFromTheImagesCopy() throws Exception {
        ValidationResult result = validate();

        assertThat(errors(result)).isEmpty();
        assertThat(result.getErrors().getErrors()).extracting(ValidationError::getMessage)
                .noneMatch(message -> message.contains("cohub/api/v1/exposure.proto"));
        assertThat(lake.resolve("cohub")).as("the lake gains no provided file").doesNotExist();
        assertThat(lake.resolve("buf.yaml")).hasContent("version: v1\nlint:\n  use:\n    - STANDARD");
    }

    @Test
    void validateLake_comparesAgainstABaselineThatAlsoImportsIt() throws Exception {
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "one");
        Files.writeString(lake.resolve("demo/v1/demo.proto"), DEMO.replace("  string room = 2;\n", ""));
        git("commit", "-q", "-am", "drop room");

        ValidationResult result = validate();

        assertThat(errors(result)).singleElement().satisfies(error -> {
            assertThat(error.getType()).isEqualTo(ValidationError.Type.BREAKING_CHANGE);
            assertThat(error.getMessage()).contains("demo/v1/demo.proto", "room");
        });
    }

    @Test
    void validateLake_afterDroppingAVendoredCopy_comparesOnlyTheLakesOwnFiles() throws Exception {
        // A lake that vendored the options before they were provided; its copy
        // has a field the image's lacks, a deletion buf would call breaking.
        Path vendored = lake.resolve("cohub/api/v1/exposure.proto");
        Files.createDirectories(vendored.getParent());
        Files.writeString(vendored, Files.readString(Path.of("src/main/proto/cohub/api/v1/exposure.proto"))
                .replace("  optional bool custom = 2;\n", "  optional bool custom = 2;\n  bool legacy = 3;\n"));
        assertThat(Files.readString(vendored)).contains("bool legacy = 3;");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "vendored");
        git("rm", "-q", "-r", "cohub");
        git("commit", "-q", "-m", "import the provided copy");

        ValidationResult result = validate();

        assertThat(errors(result)).isEmpty();
    }

    @Test
    void validateLake_afterDroppingTheLastImport_comparesAgainstABaselineThatResolves() throws Exception {
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "with the options");
        // The author drops the WebSocket option and, as buf's IMPORT_USED lint asks, its import.
        Files.writeString(lake.resolve("demo/v1/demo.proto"), DEMO
                .replace("import \"cohub/api/v1/exposure.proto\";\n", "")
                .replace("    option (cohub.api.v1.exposure) = {websocket: true};\n", ""));
        assertThat(Files.readString(lake.resolve("demo/v1/demo.proto"))).doesNotContain("cohub");
        git("commit", "-q", "-am", "without the options");

        ValidationResult result = validate();

        assertThat(errors(result)).isEmpty();
    }

    @Test
    void validateLake_afterStartingToVendorTheProto_comparesAgainstABaselineThatResolves() throws Exception {
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "imports the provided copy");
        Path vendored = lake.resolve("cohub/api/v1/exposure.proto");
        Files.createDirectories(vendored.getParent());
        Files.copy(Path.of("src/main/proto/cohub/api/v1/exposure.proto"), vendored);
        git("add", ".");
        git("commit", "-q", "-m", "vendors it");

        ValidationResult result = validate();

        assertThat(errors(result)).isEmpty();
    }

    @Test
    void validateLake_aLakeThatNeverImportsIt_comparesAgainstTheCommitAsBefore() throws Exception {
        String plain = "syntax = \"proto3\";\n\npackage demo.v1;\n\nmessage Note {\n  string text = 1;\n"
                + "  string room = 2;\n}\n";
        Files.writeString(lake.resolve("demo/v1/demo.proto"), plain);
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "one");
        Files.writeString(lake.resolve("demo/v1/demo.proto"), plain.replace("  string room = 2;\n", ""));
        git("commit", "-q", "-am", "drop room");

        ValidationResult result = validate();

        assertThat(errors(result)).singleElement()
                .satisfies(error -> assertThat(error.getMessage()).contains("demo/v1/demo.proto", "room"));
        assertThat(result.getMetadata().getPhaseStatuses().getValidation().getLogLinesList())
                .noneMatch(line -> line.contains("protolake image's copy"));
    }

    private ValidationResult validate() throws Exception {
        return runner.validateLake(Lake.newBuilder().setName("lakes/lake").build(), false,
                BuildOperationMetadata.getDefaultInstance());
    }

    private static List<ValidationError> errors(ValidationResult result) {
        return result.getErrors().getErrors().stream()
                .filter(e -> e.getSeverity() == ValidationError.Severity.ERROR)
                .toList();
    }

    private void git(String... args) throws Exception {
        // Repo-local identity and no signing or hooks: a developer's global
        // commit.gpgsign or core.hooksPath must not fail the bootstrap.
        String[] prefix = {"git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=",
                "-c", "user.email=t@example.com", "-c", "user.name=t"};
        String[] cmd = new String[prefix.length + args.length];
        System.arraycopy(prefix, 0, cmd, 0, prefix.length);
        System.arraycopy(args, 0, cmd, prefix.length, args.length);
        Process p = new ProcessBuilder(cmd).directory(lake.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", cmd)).isZero();
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }
}
