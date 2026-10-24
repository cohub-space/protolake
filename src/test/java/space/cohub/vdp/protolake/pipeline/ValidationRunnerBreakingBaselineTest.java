package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;
import space.cohub.vdp.protolake.util.git.GitCommand;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The breaking-change baseline: the merge base with origin/main when the
 * checkout has one, the previous commit otherwise, and nothing at all for
 * a checkout with neither — which the runner reports instead of passing.
 */
class ValidationRunnerBreakingBaselineTest {

    private static ValidationRunner runnerWith(Optional<String> mergeBase, Optional<String> previous) {
        ValidationRunner runner = new ValidationRunner();
        runner.gitCommand = new GitCommand() {
            @Override
            public Optional<String> mergeBase(Path directory, String ref) {
                assertThat(ref).isEqualTo(ValidationRunner.BREAKING_BASE_REF);
                return mergeBase;
            }

            @Override
            public Optional<String> revParse(Path directory, String revision) {
                assertThat(revision).isEqualTo("HEAD~1");
                return previous;
            }
        };
        return runner;
    }

    @Test
    void measuresFromTheMergeBaseWithMain() {
        assertThat(runnerWith(Optional.of("0123abcd"), Optional.of("ffff0000")).breakingBaseSha(Path.of("/lake")))
            .contains("0123abcd");
    }

    @Test
    void fallsBackToThePreviousCommitWithoutMain() {
        assertThat(runnerWith(Optional.empty(), Optional.of("ffff0000")).breakingBaseSha(Path.of("/lake")))
            .contains("ffff0000");
    }

    @Test
    void aShallowCheckoutHasNoBaseline() {
        assertThat(runnerWith(Optional.empty(), Optional.empty()).breakingBaseSha(Path.of("/lake")))
            .isEmpty();
    }

    @Test
    void comparesAgainstARefInputAtTheCommit() {
        // branch= takes only a branch name; branch=HEAD~1 failed every run and read as clean.
        assertThat(ValidationRunner.gitRefInput("0123abcd")).isEqualTo(".git#ref=0123abcd");
    }
}
