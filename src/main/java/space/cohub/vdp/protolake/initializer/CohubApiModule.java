package space.cohub.vdp.protolake.initializer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;

/**
 * The cohub_api Bazel module protolake ships in its image: cohub's API
 * annotation protos ({@code cohub/api/v1/exposure.proto}), which lakes import
 * the way they import googleapis and protovalidate.
 *
 * <p>Each lake's generated MODULE.bazel points a {@code local_path_override}
 * at {@link #path()}, and its root BUILD.bazel resolves the import to
 * {@link #LABEL}. buf has no such module, so validation hands the file to buf
 * itself (ValidationRunner). The module's skeleton lives in
 * {@code src/main/bazel/cohub_api}; the Dockerfile copies the proto in from
 * {@code src/main/proto}, so the file lakes import is the one protolake
 * compiles.
 */
@ApplicationScoped
public class CohubApiModule {

    /** The module name, and the repository name lakes refer to. */
    public static final String NAME = "cohub_api";

    /** The version lakes declare in their {@code bazel_dep}; the override ignores it. */
    public static final String VERSION = "1.0.0";

    /** The proto import paths the module provides, relative to its root. */
    public static final List<String> IMPORT_PATHS = List.of("cohub/api/v1/exposure.proto");

    /** The proto_library a lake's import of {@code cohub/api/v1/exposure.proto} resolves to. */
    public static final String LABEL = "@cohub_api//cohub/api/v1:exposure_proto";

    private final Path path;

    @Inject
    public CohubApiModule(
            @ConfigProperty(name = "protolake.cohub-api.module-path",
                    defaultValue = "/opt/protolake/bazel/cohub_api") String path) {
        this.path = Path.of(path);
    }

    /** Where the module's root is: the image path, or a test's copy. */
    public Path path() {
        return path;
    }
}
