package space.cohub.vdp.protolake.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maven publish-target selection by mode: local installs run the {@code _local}
 * twin ({@code <version>-local} coordinates) so they can never squat the
 * release coordinates; remote registries run the plain target.
 */
class BazelBuildRunnerTest {

    private static final String MAVEN = "//cohub/vdp:publish_vdp_to_maven";
    private static final String MAVEN_LOCAL = "//cohub/vdp:publish_vdp_to_maven_local";
    private static final String PYPI = "//cohub/vdp:publish_vdp_to_pypi";
    private static final String NPM = "//cohub/vdp:publish_vdp_to_npm";

    @Test
    void localMode_picksLocalTwin_dropsReleaseTarget() {
        List<String> selected = BazelBuildRunner.selectMavenTargetsForMode(
                List.of(MAVEN, MAVEN_LOCAL, PYPI, NPM), "/home/protolake/.m2/repository");

        assertThat(selected).containsExactly(MAVEN_LOCAL, PYPI, NPM);
    }

    @Test
    void localMode_fileScheme_picksLocalTwin() {
        List<String> selected = BazelBuildRunner.selectMavenTargetsForMode(
                List.of(MAVEN, MAVEN_LOCAL), "file:///home/protolake/.m2/repository");

        assertThat(selected).containsExactly(MAVEN_LOCAL);
    }

    @Test
    void remoteMode_picksReleaseTarget_dropsLocalTwin() {
        List<String> selected = BazelBuildRunner.selectMavenTargetsForMode(
                List.of(MAVEN, MAVEN_LOCAL, PYPI), "https://us-maven.pkg.dev/x/y");

        assertThat(selected).containsExactly(MAVEN, PYPI);
    }

    @Test
    void localMode_lakeWithoutLocalTwin_fallsBackToReleaseTarget() {
        // Lakes generated before the _local twin existed: publish something
        // (with a warning) rather than nothing.
        List<String> selected = BazelBuildRunner.selectMavenTargetsForMode(
                List.of(MAVEN, PYPI), null);

        assertThat(selected).containsExactly(MAVEN, PYPI);
    }

    @Test
    void nonMavenTargets_passThroughUnchanged_bothModes() {
        assertThat(BazelBuildRunner.selectMavenTargetsForMode(List.of(PYPI, NPM), null))
                .containsExactly(PYPI, NPM);
        assertThat(BazelBuildRunner.selectMavenTargetsForMode(List.of(PYPI, NPM), "https://r"))
                .containsExactly(PYPI, NPM);
    }
}
