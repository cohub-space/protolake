package space.cohub.vdp.protolake.util.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    void configArgs_trustTheRealPathBehindALink() throws Exception {
        // Git reports the real path of the directory it runs in. Going through a link makes
        // it differ from the given path on every host, not only where the temp dir sits
        // under a symlink (macOS: /var -> /private/var).
        Path link = Files.createSymbolicLink(tmp.resolve("link"), lake);

        assertThat(SafeDirectory.configArgs(link)).contains("safe.directory=" + lake.toRealPath());
        Result trusted = run(link, FOREIGN_OWNER, trustedGit(link, "rev-parse", "HEAD"));
        assertThat(trusted.exitCode()).as(trusted.output()).isZero();
    }

    @Test
    void configArgs_resolveALinkBeforeItsDotDot() throws Exception {
        // work/link points at sub, so work/link/../<lake> opens the lake beside sub. Dropping
        // "link/.." lexically would trust work/<lake>, a directory git never opens.
        Files.createDirectories(tmp.resolve("sub"));
        Files.createDirectories(tmp.resolve("work"));
        Files.createSymbolicLink(tmp.resolve("work").resolve("link"), tmp.resolve("sub"));
        Path viaLink = tmp.resolve("work").resolve("link").resolve("..").resolve(lake.getFileName());

        assertThat(SafeDirectory.configArgs(viaLink)).doesNotContain("safe.directory=" + viaLink.normalize());
        Result trusted = run(viaLink, FOREIGN_OWNER, trustedGit(viaLink, "rev-parse", "HEAD"));
        assertThat(trusted.exitCode()).as(trusted.output()).isZero();
    }

    @Test
    void configArgs_trustOnlyTheGivenRepository() throws Exception {
        List<String> args = SafeDirectory.configArgs(lake);
        assertThat(args).doesNotContain("safe.directory=*");
        assertThat(args).allSatisfy(arg -> assertThat(arg).isIn("-c",
            "safe.directory=" + lake.toAbsolutePath(),
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

    @Test
    void trustForLocalClone_skipsAUserConfigGitCannotRead() throws Exception {
        // Git skips a global config it may not read but dies on such an include: neither an
        // unreadable file nor one behind a directory git may not search may be included.
        Path unreadable = tmp.resolve("unreadable.gitconfig");
        Files.writeString(unreadable, "[user]\n\tname = Hidden\n");
        Path closed = tmp.resolve("closed");
        Files.createDirectories(closed);
        Path behindClosed = closed.resolve("config");
        Files.writeString(behindClosed, "[user]\n\tname = Hidden\n");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"));
        try {
            // Root reads both anyway, and so would git running as root.
            assumeFalse(Files.isReadable(unreadable) || Files.isReadable(behindClosed));
            try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(lake,
                    List.of(unreadable.toString(), behindClosed.toString()))) {
                Result trusted = run(lake.resolve(".git"), foreignOwner(trust), "git", "rev-parse", "HEAD");
                assertThat(trusted.exitCode()).as(trusted.output()).isZero();
            }
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void trustForLocalClone_deletesItsFileWhenTheWriteFails() {
        List<Path> files = new ArrayList<>();
        assertThatThrownBy(() -> SafeDirectory.trustForLocalClone(lake, List.of(), (file, content) -> {
            files.add(file);
            throw new IOException("No space left on device");
        })).isInstanceOf(IOException.class).hasMessage("No space left on device");

        assertThat(files).hasSize(1);
        assertThat(files.get(0)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GIT_CONFIG_GLOBAL", "HOME", "XDG_CONFIG_HOME"})
    void userGlobalConfigs_refuseARelativePath(String variable) {
        // buf's client git would resolve it against a temporary directory, which ".." can
        // leave for a real config, and the serving git inside the lake's .git: dropping it
        // loses the user's policy, and resolving it may read the lake's.
        Map<String, String> env = new HashMap<>(Map.of("HOME", tmp.resolve("home").toString()));
        env.put(variable, "../gitconfig");

        assertThatThrownBy(() -> SafeDirectory.userGlobalConfigs(env))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(variable + " is '../gitconfig'")
            .hasMessageContaining("absolute path");
    }

    @Test
    void userGlobalConfigs_emptyValuesCountAsUnset() throws Exception {
        // buf drops empty variables before it starts git.
        Path home = tmp.resolve("home");
        assertThat(SafeDirectory.userGlobalConfigs(Map.of(
                "HOME", home.toString(), "GIT_CONFIG_GLOBAL", "", "XDG_CONFIG_HOME", "")))
            .containsExactly(home.resolve(".config/git/config").toString(), home.resolve(".gitconfig").toString());
    }

    @Test
    void userGlobalConfigs_followGitsOrder() throws Exception {
        Path home = tmp.resolve("home");
        Path xdg = tmp.resolve("xdg");
        Path explicit = tmp.resolve("explicit.gitconfig");

        assertThat(SafeDirectory.userGlobalConfigs(Map.of("HOME", home.toString()))).containsExactly(
            home.resolve(".config/git/config").toString(), home.resolve(".gitconfig").toString());
        assertThat(SafeDirectory.userGlobalConfigs(Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", xdg.toString())))
            .containsExactly(xdg.resolve("git/config").toString(), home.resolve(".gitconfig").toString());
        assertThat(SafeDirectory.userGlobalConfigs(Map.of("HOME", home.toString(), "GIT_CONFIG_GLOBAL", explicit.toString())))
            .containsExactly(explicit.toString());
    }

    private static Map<String, String> foreignOwner(SafeDirectory.CloneTrust trust) {
        Map<String, String> env = new HashMap<>(FOREIGN_OWNER);
        env.putAll(trust.environment());
        return env;
    }

    private static String[] trustedGit(Path repository, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(SafeDirectory.configArgs(repository));
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    @Test
    void trustForLocalClone_fromThisProcessEnvironment_writesAConfigGitReads() throws Exception {
        // The includes come from this JVM's HOME, XDG_CONFIG_HOME or GIT_CONFIG_GLOBAL; an
        // include git cannot expand fails every git command that reads the file. The lake is
        // named through a link, so its real path differs from the given one on every host.
        Path link = Files.createSymbolicLink(tmp.resolve("link"), lake);
        try (SafeDirectory.CloneTrust trust = SafeDirectory.trustForLocalClone(link)) {
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
