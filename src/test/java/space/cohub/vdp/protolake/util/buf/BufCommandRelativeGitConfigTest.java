package space.cohub.vdp.protolake.util.buf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The breaking check stops before buf starts when GIT_CONFIG_GLOBAL, HOME or XDG_CONFIG_HOME
 * is relative: buf's git would resolve it against a temporary directory, and the git serving
 * the clone inside the lake. A stand-in buf records whether it ran, so no real buf is needed.
 */
class BufCommandRelativeGitConfigTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"GIT_CONFIG_GLOBAL", "HOME", "XDG_CONFIG_HOME"})
    void aRelativePathStopsTheCheckBeforeBufRuns(String variable) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("HOME", tmp.resolve("home").toString()));
        env.put(variable, "../gitconfig");
        BufCommand buf = standInBuf(env);

        assertThatThrownBy(() -> buf.breaking(tmp, ".git#ref=HEAD~1"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(variable)
            .hasMessageContaining("absolute path");
        assertThat(tmp.resolve("buf-ran")).doesNotExist();
    }

    @Test
    void absolutePathsLetBufRun() throws Exception {
        // The stand-in does run when nothing is relative, so the test above is not vacuous.
        BufCommand buf = standInBuf(Map.of("HOME", tmp.resolve("home").toString()));

        assertThat(buf.breaking(tmp, ".git#ref=HEAD~1")).isEmpty();
        assertThat(tmp.resolve("buf-ran")).exists();
    }

    private BufCommand standInBuf(Map<String, String> inheritedEnvironment) throws IOException {
        Path standIn = tmp.resolve("buf");
        Files.writeString(standIn, "#!/bin/sh\ntouch '" + tmp.resolve("buf-ran") + "'\n");
        assertThat(standIn.toFile().setExecutable(true)).isTrue();
        BufCommand buf = new BufCommand();
        buf.bufCommand = standIn.toString();
        buf.timeoutSeconds = 30;
        buf.inheritedEnvironment = inheritedEnvironment;
        return buf;
    }
}
