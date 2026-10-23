package space.cohub.vdp.protolake.util.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protolake's git works in a lake another user owns, which is how a bind-mounted
 * lake looks from inside the image. Git's GIT_TEST_ASSUME_DIFFERENT_OWNER makes it
 * treat every repository as another user's, so the mismatch exists on any host.
 */
class GitCommandForeignOwnerTest {

    // Every repository looks like another user's, and no user or system config is read:
    // a safe.directory=* there would let these tests pass without protolake's trust.
    // LC_ALL=C keeps git's messages in English for the premise checks.
    private static final Map<String, String> FOREIGN_OWNER = Map.of(
        "GIT_TEST_ASSUME_DIFFERENT_OWNER", "1",
        "GIT_CONFIG_GLOBAL", "/dev/null",
        "GIT_CONFIG_NOSYSTEM", "1",
        "LC_ALL", "C");

    @TempDir
    Path lake;

    private GitCommand git;

    @BeforeEach
    void setUp() {
        git = new GitCommand();
        git.environment = FOREIGN_OWNER;
    }

    @Test
    void plainGit_refusesTheForeignOwnedLake() throws Exception {
        // The premise: without protolake's trust, git refuses this lake. A git that
        // ignored the variable would let every other test here pass without the fix.
        git.init(lake);
        ProcessBuilder plain = new ProcessBuilder("git", "status", "--porcelain")
            .directory(lake.toFile())
            .redirectErrorStream(true);
        plain.environment().putAll(FOREIGN_OWNER);
        Process process = plain.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).isNotZero();
        assertThat(output).contains("dubious ownership");
    }

    @Test
    void initConfigAndCommit_succeedInAForeignOwnedLake() throws Exception {
        initLake();
        Files.writeString(lake.resolve("lake.yaml"), "name: owned\n");
        git.addAll(lake);
        git.commit(lake, "Initialize ProtoLake: owned");

        assertThat(git.isClean(lake)).isTrue();
        assertThat(git.getCurrentCommit(lake)).matches("[0-9a-f]{40}");
    }

    @Test
    void historyReads_resolveInAForeignOwnedLake() throws Exception {
        // The reads the breaking check and the build make. Their callers swallow a git
        // failure, so an untrusted lake silently lost its breaking-change baseline.
        initLake();
        Files.writeString(lake.resolve("lake.yaml"), "name: owned\n");
        git.addAll(lake);
        git.commit(lake, "one");
        Path bundle = lake.resolve("acme/svc");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("bundle.yaml"), "name: svc\n");
        git.add(lake, bundle.toString());
        git.commit(lake, "Add bundle: svc");

        assertThat(git.revParse(lake, "HEAD~1")).isPresent();
        assertThat(git.getCurrentBranch(lake)).isNotBlank().isNotEqualTo("HEAD");
        assertThat(git.getModifiedFiles(lake)).isEmpty();
    }

    private void initLake() throws Exception {
        git.init(lake);
        git.config(lake, "user.name", "ProtoLake");
        git.config(lake, "user.email", "protolake@localhost");
    }
}
