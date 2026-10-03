package space.cohub.vdp.protolake.pipeline;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The copy of a lake that buf checks when the lake imports a proto the
 * protolake image provides: which lakes need it, what it holds, and that it
 * never writes to the lake beyond buf.lock, nor follows a link out of itself.
 */
class ProvidedProtoOverlayTest {

    private static final String EXPOSURE = "cohub/api/v1/exposure.proto";
    private static final List<String> PROVIDED = List.of(EXPOSURE);

    @TempDir
    Path tmp;

    private Path lake;
    private Path module;

    @BeforeEach
    void setUp() throws IOException {
        lake = Files.createDirectories(tmp.resolve("lake"));
        module = Files.createDirectories(tmp.resolve("module"));
        write(module.resolve(EXPOSURE), "syntax = \"proto3\";\npackage cohub.api.v1;\n");
        write(lake.resolve("buf.yaml"), "version: v1\n");
    }

    @Test
    void neededImports_aLakeThatImportsTheProto_needsIt() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "syntax = \"proto3\";\n  import public \"" + EXPOSURE + "\";\n");

        assertThat(ProvidedProtoOverlay.neededImports(lake, PROVIDED)).containsExactly(EXPOSURE);
    }

    @Test
    void neededImports_aLakeThatDoesNotImportIt_runsBufAsBefore() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"),
                "syntax = \"proto3\";\n// import \"" + EXPOSURE + "\";\nimport \"google/api/annotations.proto\";\n");

        assertThat(ProvidedProtoOverlay.neededImports(lake, PROVIDED)).isEmpty();
    }

    @Test
    void neededImports_singleQuotedImportAfterAnotherStatement_isFound() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "syntax = \"proto3\"; import '" + EXPOSURE + "';\n");

        assertThat(ProvidedProtoOverlay.neededImports(lake, PROVIDED)).containsExactly(EXPOSURE);
    }

    @Test
    void neededImports_anImportInsideABlockComment_isNotOne() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "syntax = \"proto3\";\n/*\nimport \"" + EXPOSURE + "\";\n*/\n");

        assertThat(ProvidedProtoOverlay.neededImports(lake, PROVIDED)).isEmpty();
    }

    @Test
    void neededImports_aLakeThatHoldsItsOwnCopy_resolvesItItself() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "import \"" + EXPOSURE + "\";\n");
        write(lake.resolve(EXPOSURE), "syntax = \"proto3\";\n");

        assertThat(ProvidedProtoOverlay.neededImports(lake, PROVIDED)).isEmpty();
    }

    @Test
    void create_copiesTheLakesBufInputsAndTheProvidedProto() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "import \"" + EXPOSURE + "\";\n");
        write(lake.resolve("buf.lock"), "version: v1\n");
        write(lake.resolve("demo/BUILD.bazel"), "proto_library()\n");
        write(lake.resolve("bazel-out/x/y.proto"), "not a lake proto\n");

        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            Path root = overlay.root();
            assertThat(root.resolve("buf.yaml")).hasContent("version: v1");
            assertThat(root.resolve("buf.lock")).exists();
            assertThat(root.resolve("demo/v1/demo.proto")).exists();
            assertThat(root.resolve(EXPOSURE)).hasSameTextualContentAs(module.resolve(EXPOSURE));
            assertThat(root.resolve("demo/BUILD.bazel")).doesNotExist();
            assertThat(root.resolve("bazel-out")).doesNotExist();
            assertThat(overlay.providedPaths()).containsExactly(EXPOSURE);
        }
        assertThat(lake.resolve("cohub")).as("the lake gains no provided file").doesNotExist();
    }

    @Test
    void copyLockBack_writesOnlyAChangedLock() throws IOException {
        write(lake.resolve("buf.lock"), "version: v1\n");
        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            write(overlay.root().resolve("buf.lock"), "version: v1\ndeps: []\n");
            overlay.copyLockBack();
        }

        assertThat(lake.resolve("buf.lock")).hasContent("version: v1\ndeps: []");
    }

    @Test
    void copyLockBack_aLockThatIsALink_isRefusedAndItsTargetLeftAlone() throws IOException {
        Path otherLake = Files.createDirectories(tmp.resolve("other-lake"));
        write(otherLake.resolve("buf.lock"), "version: v1\n");
        Files.createSymbolicLink(lake.resolve("buf.lock"), otherLake.resolve("buf.lock"));

        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            write(overlay.root().resolve("buf.lock"), "version: v1\ndeps: []\n");

            assertThatThrownBy(overlay::copyLockBack).isInstanceOf(IOException.class)
                    .hasMessageContaining("buf.lock is a symbolic link");
        }

        assertThat(otherLake.resolve("buf.lock")).hasContent("version: v1");
        assertThat(lake.resolve("buf.lock")).isSymbolicLink();
    }

    @Test
    void copyLockBack_anUnchangedLockBehindALink_needsNoWrite() throws IOException {
        Path otherLake = Files.createDirectories(tmp.resolve("other-lake"));
        write(otherLake.resolve("buf.lock"), "version: v1\n");
        Files.createSymbolicLink(lake.resolve("buf.lock"), otherLake.resolve("buf.lock"));

        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            overlay.copyLockBack();
        }

        assertThat(otherLake.resolve("buf.lock")).hasContent("version: v1");
    }

    @Test
    void copyLockBack_aLockWhoseDirectoryBecameALinkOutOfTheLake_isRefused() throws IOException {
        write(lake.resolve("sub/buf.lock"), "version: v1\n");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        write(outside.resolve("buf.lock"), "version: v1\n");

        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            write(overlay.root().resolve("sub/buf.lock"), "version: v1\ndeps: []\n");
            Files.delete(lake.resolve("sub/buf.lock"));
            Files.delete(lake.resolve("sub"));
            Files.createSymbolicLink(lake.resolve("sub"), outside);

            assertThatThrownBy(overlay::copyLockBack).isInstanceOf(IOException.class)
                    .hasMessageContaining("sits behind a symbolic link");
        }

        assertThat(outside.resolve("buf.lock")).hasContent("version: v1");
    }

    @Test
    void close_removesTheCopyAndNeverFollowsALinkIntoTheLake() throws IOException {
        write(lake.resolve("demo/v1/demo.proto"), "import \"" + EXPOSURE + "\";\n");
        Path root;
        try (ProvidedProtoOverlay overlay = ProvidedProtoOverlay.create(lake, module, PROVIDED)) {
            root = overlay.root();
            Files.createSymbolicLink(root.resolve("lake-link"), lake);
        }

        assertThat(root).doesNotExist();
        assertThat(lake.resolve("demo/v1/demo.proto")).exists();
        assertThat(lake.resolve("buf.yaml")).exists();
    }

    @Test
    void extractBufInputs_takesRegularBufFilesOnly_neverLinksOrPathsOutsideTheTarget() throws IOException {
        Path tar = tmp.resolve("baseline.tar");
        try (OutputStream out = Files.newOutputStream(tar); TarArchiveOutputStream entries = new TarArchiveOutputStream(out)) {
            file(entries, "buf.yaml", "version: v1\n");
            file(entries, "demo/v1/demo.proto", "syntax = \"proto3\";\n");
            file(entries, "README.md", "not a buf input\n");
            file(entries, "../escape.proto", "outside\n");
            TarArchiveEntry link = new TarArchiveEntry("demo/v1/link.proto", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("/etc/passwd");
            entries.putArchiveEntry(link);
            entries.closeArchiveEntry();
            TarArchiveEntry hardLink = new TarArchiveEntry("demo/v1/hard.proto", TarArchiveEntry.LF_LINK);
            hardLink.setLinkName("demo/v1/demo.proto");
            entries.putArchiveEntry(hardLink);
            entries.closeArchiveEntry();
        }
        Path target = Files.createDirectories(tmp.resolve("baseline"));

        ProvidedProtoOverlay.extractBufInputs(tar, target);

        assertThat(target.resolve("buf.yaml")).exists();
        assertThat(target.resolve("demo/v1/demo.proto")).exists();
        assertThat(target.resolve("README.md")).doesNotExist();
        assertThat(target.resolve("demo/v1/link.proto")).doesNotExist();
        assertThat(target.resolve("demo/v1/hard.proto")).doesNotExist();
        assertThat(tmp.resolve("escape.proto")).doesNotExist();
    }

    private static void file(TarArchiveOutputStream entries, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        entries.putArchiveEntry(entry);
        entries.write(bytes);
        entries.closeArchiveEntry();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
