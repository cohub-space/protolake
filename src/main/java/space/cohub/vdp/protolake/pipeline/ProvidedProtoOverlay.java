package space.cohub.vdp.protolake.pipeline;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import space.cohub.vdp.protolake.util.git.GitCommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A temporary copy of a lake's buf inputs that also holds the protos the
 * protolake image provides, such as {@code cohub/api/v1/exposure.proto}, at
 * their import paths.
 *
 * <p>buf resolves a module's imports from its own files and its buf.yaml deps.
 * Lakes get the provided protos from a Bazel module in the image, which buf
 * cannot see, and a lake's buf.yaml is the user's after init, so protolake
 * cannot add a dep to it. A lake that imports a provided proto it does not
 * hold is validated in this copy instead: its buf.yaml, buf.lock and protos,
 * plus the provided files, which lint leaves out ({@link #providedPaths()}).
 * The breaking check compares such copies whenever the lake or the commit it
 * is measured from needs a provided proto ({@link #breakingInputs}). The lake
 * is never written, except for the buf.lock that {@code buf dep update}
 * refreshes, which validation does for every lake ({@link #copyLockBack()}).
 */
final class ProvidedProtoOverlay implements AutoCloseable {

    /** Directories that hold no buf inputs: VCS metadata, Bazel symlinks, npm trees. */
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".bazel", "node_modules", ".aspect_rules_js");

    /** buf's own files, copied wherever they sit so workspace layouts survive. */
    private static final Set<String> BUF_FILES = Set.of("buf.yaml", "buf.lock", "buf.work.yaml");

    private final Path lake;
    private final Path root;
    private final List<String> provided;

    private ProvidedProtoOverlay(Path lake, Path root, List<String> provided) {
        this.lake = lake;
        this.root = root;
        this.provided = provided;
    }

    /**
     * The provided import paths a tree of protos needs: those its protos
     * import, as protoc reads the imports (ProtoImports), that it does not
     * hold itself. Empty when buf can run on the tree as it is.
     */
    static List<String> neededImports(Path tree, List<String> importPaths) throws IOException {
        List<String> missing = importPaths.stream()
                .filter(path -> !Files.exists(tree.resolve(path)))
                .toList();
        if (missing.isEmpty()) {
            return List.of();
        }
        Set<String> imported = new HashSet<>();
        for (Path proto : bufInputs(tree)) {
            if (proto.toString().endsWith(".proto")) {
                for (ProtoImports.Import use : ProtoImports.parse(Files.readString(proto, StandardCharsets.UTF_8))) {
                    imported.add(use.path());
                }
            }
        }
        return missing.stream().filter(imported::contains).toList();
    }

    /**
     * Copies the lake's buf inputs and the provided protos into a temporary
     * directory.
     *
     * @param providedRoot the directory holding the provided protos at their import paths
     * @param importPaths  the provided protos to copy in
     */
    static ProvidedProtoOverlay create(Path lake, Path providedRoot, List<String> importPaths) throws IOException {
        ProvidedProtoOverlay overlay = new ProvidedProtoOverlay(lake,
                Files.createTempDirectory("protolake-buf-"), List.copyOf(importPaths));
        try {
            for (Path input : bufInputs(lake)) {
                Path target = overlay.root.resolve(lake.relativize(input).toString());
                Files.createDirectories(target.getParent());
                Files.copy(input, target);
            }
            copyProvided(providedRoot, overlay.root, overlay.provided);
            return overlay;
        } catch (IOException | RuntimeException e) {
            overlay.close();
            throw e;
        }
    }

    /** The directory to run buf in, in place of the lake. */
    Path root() {
        return root;
    }

    /** The provided protos' paths, for buf lint's {@code --exclude-path}. */
    List<String> providedPaths() {
        return provided;
    }

    /**
     * Writes back each buf.lock {@code buf dep update} changed in the copy,
     * keeping the lake file's permissions. A lock that did not change is
     * never written. One that did is refused, with the reason, when the lake's
     * buf.lock is a symbolic link or sits behind one: the link may lead out of
     * the lake, to another lake's lock, and protolake writes only regular
     * files inside the lake.
     */
    void copyLockBack() throws IOException {
        Path lakeDirectory = lake.toRealPath();
        for (Path copy : bufInputs(root)) {
            if (!"buf.lock".equals(copy.getFileName().toString())) {
                continue;
            }
            Path original = lake.resolve(root.relativize(copy).toString());
            byte[] updated = Files.readAllBytes(copy);
            if (Files.exists(original) && Arrays.equals(Files.readAllBytes(original), updated)) {
                continue;
            }
            if (Files.isSymbolicLink(original)) {
                throw new IOException(lake.relativize(original) + " is a symbolic link, and buf dep update changed "
                        + "the lock it resolves to. protolake writes a lake's buf.lock only as a regular file inside "
                        + "the lake, never through a link that may lead out of it: replace the link with a copy of "
                        + "the file it points to, or run buf dep update in the lake yourself.");
            }
            if (!original.getParent().toRealPath().startsWith(lakeDirectory)) {
                throw new IOException(lake.relativize(original) + " sits behind a symbolic link that leads out of "
                        + "the lake, and buf dep update changed it; protolake writes only inside the lake.");
            }
            Files.write(original, updated, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /**
     * The breaking check's inputs when the lake, or the commit it is compared
     * against, imports a provided proto it does not hold. Both sides then
     * hold the image's copy of every such proto, so buf resolves each side's
     * imports and finds the provided protos unchanged: a change that drops
     * the last import of one, starts vendoring it, or stops, still compares
     * against a baseline that resolves. The commit's files come from
     * {@code git archive}, since buf's git input cannot carry the provided
     * protos. Empty when neither side needs one; buf then compares against
     * the commit itself.
     */
    static Optional<BreakingInputs> breakingInputs(Path lake, GitCommand git, String sha, Path providedRoot,
                                                   List<String> importPaths) throws IOException {
        Path baseline = Files.createTempDirectory("protolake-buf-baseline-");
        ProvidedProtoOverlay copy = null;
        try {
            extractCommit(git, lake, sha, baseline);
            Set<String> needed = new HashSet<>(neededImports(lake, importPaths));
            needed.addAll(neededImports(baseline, importPaths));
            List<String> paths = importPaths.stream().filter(needed::contains).toList();
            if (paths.isEmpty()) {
                deleteTree(baseline);
                return Optional.empty();
            }
            List<String> lakeLacks = paths.stream().filter(path -> !Files.exists(lake.resolve(path))).toList();
            if (!lakeLacks.isEmpty()) {
                copy = create(lake, providedRoot, lakeLacks);
            }
            // A copy the commit vendored is replaced too: the check covers the lake's own files.
            copyProvided(providedRoot, baseline, paths);
            return Optional.of(new BreakingInputs(lake, copy, baseline, paths));
        } catch (IOException | RuntimeException e) {
            if (copy != null) {
                try {
                    copy.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            try {
                deleteTree(baseline);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * The two inputs of a breaking check that involves provided protos: the
     * lake, or a copy of it holding the provided protos the lake lacks, and
     * the baseline commit's buf inputs holding the image's copy of each.
     */
    static final class BreakingInputs implements AutoCloseable {
        private final Path lake;
        private final ProvidedProtoOverlay copy;
        private final Path baseline;
        private final List<String> providedPaths;

        private BreakingInputs(Path lake, ProvidedProtoOverlay copy, Path baseline, List<String> providedPaths) {
            this.lake = lake;
            this.copy = copy;
            this.baseline = baseline;
            this.providedPaths = providedPaths;
        }

        /** The directory to run buf breaking in. */
        Path input() {
            return copy == null ? lake : copy.root();
        }

        /** The directory to pass as {@code --against}. */
        Path against() {
            return baseline;
        }

        /** The provided protos both sides hold. */
        List<String> providedPaths() {
            return providedPaths;
        }

        /** Deletes the temporary directories; they hold only regular files written here. */
        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (copy != null) {
                try {
                    copy.close();
                } catch (IOException e) {
                    failure = e;
                }
            }
            try {
                deleteTree(baseline);
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Deletes the copy; it holds only regular files this class wrote. */
    @Override
    public void close() throws IOException {
        deleteTree(root);
    }

    /** Copies the provided protos into target, replacing whatever target held at their paths. */
    private static void copyProvided(Path providedRoot, Path target, List<String> paths) throws IOException {
        for (String path : paths) {
            Path source = providedRoot.resolve(path);
            if (!Files.isRegularFile(source)) {
                throw new IOException("The protolake image provides " + path + " at " + source
                        + ", but the file is missing");
            }
            Path destination = target.resolve(path);
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Extracts a commit's regular buf-input files, from {@code git archive}, into dir. */
    private static void extractCommit(GitCommand git, Path lake, String sha, Path dir) throws IOException {
        Path tar = Files.createTempFile("protolake-buf-baseline-", ".tar");
        try {
            git.archive(lake, sha, tar);
            extractBufInputs(tar, dir);
        } finally {
            Files.deleteIfExists(tar);
        }
    }

    /** The lake's .proto and buf files, as regular files, outside the skipped directories. */
    private static List<Path> bufInputs(Path dir) throws IOException {
        List<Path> inputs = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (!path.equals(dir) && (SKIPPED_DIRECTORIES.contains(name) || name.startsWith("bazel-"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (isBufInput(path.getFileName().toString()) && Files.isRegularFile(path)) {
                    inputs.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return inputs;
    }

    private static boolean isBufInput(String fileName) {
        return fileName.endsWith(".proto") || BUF_FILES.contains(fileName);
    }

    /** Extracts the regular buf-input files of a tar; links and paths leaving dir are skipped. */
    static void extractBufInputs(Path tar, Path dir) throws IOException {
        try (InputStream in = Files.newInputStream(tar); TarArchiveInputStream entries = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                Path name = Path.of(entry.getName());
                if (!isRegularFile(entry) || name.getFileName() == null
                        || !isBufInput(name.getFileName().toString())) {
                    continue;
                }
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir) || target.equals(dir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(entries, target);
            }
        }
    }

    /**
     * Whether a tar entry is a regular file. {@link TarArchiveEntry#isFile()}
     * also admits symbolic and hard links, devices and FIFOs.
     */
    private static boolean isRegularFile(TarArchiveEntry entry) {
        byte flag = entry.getLinkFlag();
        return (flag == TarConstants.LF_NORMAL || flag == TarConstants.LF_OLDNORM || flag == TarConstants.LF_CONTIG)
                && !entry.getName().endsWith("/");
    }

    /** Deletes a tree without following links: a link is removed, never its target. */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
