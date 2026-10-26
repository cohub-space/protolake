package space.cohub.vdp.protolake.pipeline;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.BuildOperationMetadata;
import protolake.v1.InstallLocalConfig;
import protolake.v1.Lake;
import space.cohub.vdp.protolake.cli.BuildProgressListener;
import space.cohub.vdp.protolake.initializer.WorkspaceInitializer;
import space.cohub.vdp.protolake.model.ValidationError;
import space.cohub.vdp.protolake.model.ValidationResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The API spec step's place in a build: after Bazel builds, before anything
 * publishes, whether or not validation runs. An ERROR finding fails the build
 * with the finding; a WARNING reaches the listener and the build goes on.
 */
@QuarkusTest
class BuildOrchestratorApiSpecsTest {

    private static final Lake LAKE = Lake.newBuilder().setName("lakes/spec_lake").build();

    @Inject
    BuildOrchestrator orchestrator;

    @InjectMock
    WorkspaceInitializer workspaceInitializer;

    @InjectMock
    GazelleRunner gazelleRunner;

    @InjectMock
    ValidationRunner validationRunner;

    @InjectMock
    BazelBuildRunner bazelBuildRunner;

    @InjectMock
    ApiSpecRunner apiSpecRunner;

    private final RecordingListener listener = new RecordingListener();

    @BeforeEach
    void setUp() throws Exception {
        when(gazelleRunner.runForLake(any(), any())).thenAnswer(call -> call.getArgument(1));
        when(validationRunner.validateLake(any(), anyBoolean(), any())).thenAnswer(call -> ValidationResult
                .newBuilder().setSuccess(true).setMetadata(call.getArgument(2)).build());
        when(bazelBuildRunner.buildTarget(any(), anyString(), any())).thenAnswer(call -> call.getArgument(2));
        when(bazelBuildRunner.publishLocal(any(), anyString(), any(), any())).thenAnswer(call -> call.getArgument(2));
    }

    @Test
    void buildTargetSync_errorFinding_failsBeforePublishing() throws Exception {
        when(apiSpecRunner.run(any(), anyString(), any())).thenReturn(result(finding(
                ValidationError.Severity.ERROR, "api/v1/api.proto", 11, "websocket on a unary RPC")));

        assertThatThrownBy(() -> build(false))
                .isInstanceOfSatisfying(BuildOrchestrator.BuildException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("API exposure checks failed");
                    assertThat(e.getErrors().getErrors()).extracting(ValidationError::getMessage)
                            .containsExactly("websocket on a unary RPC");
                });
        verify(bazelBuildRunner, never()).publishLocal(any(), anyString(), any(), any());
        assertThat(listener.failed).containsExactly(BuildOrchestrator.API_SPECS_PHASE);
    }

    @Test
    void buildTargetSync_skippedValidation_stillChecksTheAnnotations() throws Exception {
        when(apiSpecRunner.run(any(), anyString(), any())).thenReturn(result(finding(
                ValidationError.Severity.ERROR, "api/v1/api.proto", 11, "websocket on a unary RPC")));

        assertThatThrownBy(() -> build(true)).isInstanceOf(BuildOrchestrator.BuildException.class);
        verify(apiSpecRunner).run(any(), eq("."), any());
    }

    @Test
    void buildTargetSync_warningFinding_reachesTheListenerAndPublishes() throws Exception {
        when(apiSpecRunner.run(any(), anyString(), any())).thenReturn(result(finding(
                ValidationError.Severity.WARNING, "api/v1/api.proto", 7, "binding on a bidi stream")));

        BuildOperationMetadata metadata = build(false);

        assertThat(listener.warnings).containsExactly("api/v1/api.proto:7:3: binding on a bidi stream");
        assertThat(metadata.getPhaseStatuses().getBuild().getLogLinesList())
                .contains("WARNING api/v1/api.proto:7:3: binding on a bidi stream");
        verify(bazelBuildRunner).publishLocal(any(), anyString(), any(), any());
    }

    private BuildOperationMetadata build(boolean skipValidation) throws Exception {
        return orchestrator.buildTargetSync(LAKE, "spec_lake", skipValidation,
                InstallLocalConfig.newBuilder().setShouldInstall(true).build(), null, listener,
                BuildOperationMetadata.getDefaultInstance());
    }

    private static ApiSpecRunner.Result result(ValidationError finding) {
        ApiSpecRunner.BundleTarget bundle = new ApiSpecRunner.BundleTarget("api", "api");
        return new ApiSpecRunner.Result(List.of(new ApiSpecRunner.BundleSpecs(bundle, List.of(finding),
                List.of(Path.of("/bin/api/api_api_specs/api.openapi.json")), "")));
    }

    private static ValidationError finding(ValidationError.Severity severity, String file, int line,
                                           String message) {
        return ValidationError.newBuilder().setType(ValidationError.Type.API_EXPOSURE).setSeverity(severity)
                .setFile(file).setLine(line).setColumn(3).setMessage(message).build();
    }

    private static final class RecordingListener implements BuildProgressListener {
        final List<String> warnings = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        @Override
        public void onPhaseStart(String phase) {
        }

        @Override
        public void onPhaseComplete(String phase, boolean success, String message) {
            if (!success) {
                failed.add(phase);
            }
        }

        @Override
        public void onWarning(String warning) {
            warnings.add(warning);
        }

        @Override
        public void onMetadataUpdate(BuildOperationMetadata metadata) {
        }

        @Override
        public void onBuildComplete(BuildOperationMetadata metadata) {
        }

        @Override
        public void onBuildFailed(String error) {
        }
    }
}
