package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;
import space.cohub.vdp.protolake.util.git.GitCommand;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The breaking-change baseline: the merge base with origin/main when the
 * checkout has one, the previous commit otherwise.
 */
class ValidationRunnerBreakingBaselineTest {

    private static ValidationRunner runnerWith(Optional<String> mergeBase) {
        ValidationRunner runner = new ValidationRunner();
        runner.gitCommand = new GitCommand() {
            @Override
            public Optional<String> mergeBase(Path directory, String ref) {
                assertThat(ref).isEqualTo(ValidationRunner.BREAKING_BASE_REF);
                return mergeBase;
            }
        };
        return runner;
    }

    @Test
    void measuresFromTheMergeBaseWithMain() {
        assertThat(runnerWith(Optional.of("0123abcd")).breakingBaseline(Path.of("/lake")))
            .isEqualTo(".git#ref=0123abcd");
    }

    @Test
    void fallsBackToThePreviousCommitWithoutMain() {
        assertThat(runnerWith(Optional.empty()).breakingBaseline(Path.of("/lake")))
            .isEqualTo(".git#branch=HEAD~1");
    }
}
