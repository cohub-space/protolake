package space.cohub.vdp.protolake.util.buf;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The breaking check against the real buf: a removed field is reported
 * through a {@code ref=} input, and an input buf cannot resolve is an
 * error, never a clean result. Runs only where a {@code buf} binary is on
 * PATH (the service image and developer machines carry one).
 */
class BufCommandBreakingTest {

    @TempDir
    Path lake;

    private BufCommand buf;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(onPath("buf"), "buf not on PATH");
        buf = new BufCommand();
        buf.bufCommand = "buf";
        buf.timeoutSeconds = 60;
        Files.writeString(lake.resolve("buf.yaml"), "version: v2\nmodules:\n  - path: .\n");
        Path proto = lake.resolve("acme/v1/thing.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, "syntax = \"proto3\";\npackage acme.v1;\nmessage Thing {\n  string id = 1;\n  string label = 2;\n}\n");
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("add", ".");
        git("commit", "-q", "-m", "one");
        Files.writeString(proto, "syntax = \"proto3\";\npackage acme.v1;\nmessage Thing {\n  string id = 1;\n}\n");
        git("commit", "-q", "-am", "drop label");
    }

    @Test
    void aRemovedFieldIsReportedAgainstThePreviousCommitByRef() throws IOException {
        List<String> findings = buf.breaking(lake, ".git#ref=HEAD~1");
        assertThat(findings).anySatisfy(line -> assertThat(line).contains("label"));
    }

    @Test
    void anUnresolvableBaselineIsAnError_neverClean() {
        // branch= takes a branch name; HEAD~1 is not one, and buf exits 1
        // with nothing on stdout — the old runner read that as no findings.
        assertThatThrownBy(() -> buf.breaking(lake, ".git#branch=HEAD~1"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("could not compare");
    }

    private void git(String... args) throws Exception {
        // Repo-local identity and no signing or hooks: a developer's global
        // commit.gpgsign or core.hooksPath must not fail the bootstrap.
        String[] prefix = {"git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath="};
        String[] cmd = new String[prefix.length + args.length];
        System.arraycopy(prefix, 0, cmd, 0, prefix.length);
        System.arraycopy(args, 0, cmd, prefix.length, args.length);
        Process p = new ProcessBuilder(cmd).directory(lake.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", cmd)).isZero();
    }

    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }
}
