package space.cohub.vdp.protolake.util.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust protolake grants a lake: the worktree on each git command line, and
 * the git directory in a temporary global config for tools that clone the lake.
 */
class SafeDirectoryTest {

    // Every repository looks like another user's, and no user or system config is read:
    // a safe.directory=* there would let these tests pass without protolake's trust.
    // LC_ALL=C keeps git's messages in English for the premise checks.
    private static final Map<String, String> FOREIGN_OWNER = Map.of(
        "GIT_TEST_ASSUME_DIFFERENT_OWNER", "1",
        "GIT_CONFIG_GLOBAL", "/dev/null",
        "GIT_CONFIG_NOSYSTEM", "1",
        "LC_ALL", "C");

    @TempDir
    Path tmp;

    private Path lake;

    @BeforeEach
    void setUp() throws Exception {
        // Characters a git config value must quote: a space, '#' and ';'.
        lake = tmp.resolve("lake #1; owned");
        Files.createDirectories(lake);
        assertThat(run(lake, Map.of(), "git", "init", "-q").exitCode()).isZero();
        // Repo-local identity and no signing or hooks: a developer's global
        // commit.gpgsign or core.hooksPath must not fail the bootstrap.
        Result commit = run(lake, Map.of(), "git", "-c", "user.name=t", "-c", "user.email=t@example.com",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=", "commit", "-q", "--allow-empty", "-m", "one");
        assertThat(commit.exitCode()).as(commit.output()).isZero();
    }

    @Test
    void configArgs_trustTheRealPathGitReports() throws Exception {
        // macOS temp dirs sit under /var, a symlink to /private/var; git reports the latter.
        assertThat(SafeDirectory.configArgs(lake))
            .contains("safe.directory=" + lake.toRealPath());
    }

    @Test
    void configArgs_trustOnlyTheGivenRepository() throws Exception {
        List<String> args = SafeDirectory.configArgs(lake);
        assertThat(args).doesNotContain("safe.directory=*");
        assertThat(args).allSatisfy(arg -> assertThat(arg).isIn("-c",
            "safe.directory=" + lake.toAbsolutePath().normalize(),
            "safe.directory=" + lake.toRealPath()));
    }

    @Test
    void trustForLocalClone_letsGitOpenTheForeignOwnedGitDirectory() throws Exception {
        // Inside .git, git checks the git directory's owner: the check the image's git
        // makes before serving a local clone.
        Path gitDir = lake.resolve(".git");
        assertThat(run(gitDir, FOREIGN_OWNER, "git", "rev-parse", "HEAD").exitCode()).isNotZero();

        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(lake, List.of())) {
            Result trusted = run(gitDir, foreignOwner(trust), "git", "rev-parse", "HEAD");
            assertThat(trusted.exitCode()).as(trusted.output()).isZero();
        }
    }

    @Test
    void trustForLocalClone_keepsTheUsersOwnGlobalConfig() throws Exception {
        // The user's config already trusts the lake; the clone trust is for another
        // repository. Opening the lake still works only if git follows the include.
        Path userConfig = tmp.resolve("user.gitconfig");
        Files.writeString(userConfig, "[user]\n\tname = Someone\n[safe]\n\tdirectory = \""
            + lake.toRealPath().resolve(".git") + "\"\n");
        Path other = tmp.resolve("other");
        Files.createDirectories(other);

        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(other, List.of(userConfig.toString()))) {
            Result name = run(tmp, trust.environment(), "git", "config", "--global", "--includes", "--get", "user.name");
            assertThat(name.output().trim()).isEqualTo("Someone");

            Result lakeOpened = run(lake.resolve(".git"), foreignOwner(trust), "git", "rev-parse", "HEAD");
            assertThat(lakeOpened.exitCode()).as(lakeOpened.output()).isZero();
        }
    }

    @Test
    void trustForLocalClone_survivesAResetInTheUsersConfig() throws Exception {
        // An empty safe.directory drops the entries read before it. The user's config is
        // included first, so its reset cannot drop the lake's entry.
        Path userConfig = tmp.resolve("user.gitconfig");
        Files.writeString(userConfig, "[safe]\n\tdirectory =\n");

        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(lake, List.of(userConfig.toString()))) {
            Result trusted = run(lake.resolve(".git"), foreignOwner(trust), "git", "rev-parse", "HEAD");
            assertThat(trusted.exitCode()).as(trusted.output()).isZero();
        }
    }

    private static Map<String, String> foreignOwner(SafeDirectory.CloneTrust trust) {
        Map<String, String> env = new HashMap<>(FOREIGN_OWNER);
        env.putAll(trust.environment());
        return env;
    }

    @Test
    void trustForLocalClone_fromThisProcessEnvironment_writesAConfigGitReads() throws Exception {
        // The includes come from this JVM's HOME, XDG_CONFIG_HOME or GIT_CONFIG_GLOBAL; an
        // include git cannot expand fails every git command that reads the file.
        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(lake)) {
            Result read = run(tmp, trust.environment(), "git", "config", "--global", "--includes", "--list");
            assertThat(read.exitCode()).as(read.output()).isZero();
            assertThat(read.output()).contains("safe.directory=" + lake.toRealPath().resolve(".git"));
        }
    }

    @Test
    void trustForLocalClone_deletesItsFileOnClose() throws Exception {
        Path file;
        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(lake)) {
            file = trust.file();
            assertThat(file).exists();
        }
        assertThat(file).doesNotExist();
    }

    private record Result(int exitCode, String output) {}

    private static Result run(Path dir, Map<String, String> env, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(List.of(command)))
            .directory(dir.toFile())
            .redirectErrorStream(true);
        pb.environment().putAll(env);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }
}
