package space.cohub.vdp.protolake.pipeline;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import space.cohub.vdp.protolake.util.git.GitCommand;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A temporary copy of a lake's buf inputs that also holds the protos the
 * protolake image provides, such as {@code cohub/api/v1/exposure.proto}, at
 * their import paths.
 *
 * <p>buf resolves a module's imports from its own files and its buf.yaml deps.
 * Lakes get the provided protos from a Bazel module in the image, which buf
 * cannot see, and a lake's buf.yaml is the user's after init, so protolake
 * cannot add a dep to it. A lake that imports a provided proto is validated in
 * this copy instead: its buf.yaml, buf.lock and protos, plus the provided
 * files, which lint and the breaking check leave out
 * ({@link #providedPaths()}). The lake is never written, except for the
 * buf.lock that {@code buf dep update} refreshes, which validation does for
 * every lake ({@link #copyLockBack()}).
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
    private final List<Path> temporaryDirectories = new ArrayList<>();

    private ProvidedProtoOverlay(Path lake, Path root, List<String> provided) {
        this.lake = lake;
        this.root = root;
        this.provided = provided;
        temporaryDirectories.add(root);
    }

    /**
     * The provided import paths a lake needs from the image: those its protos
     * import that it does not hold itself. Empty when buf can run in the lake.
     */
    static List<String> neededImports(Path lake, List<String> importPaths) throws IOException {
        List<String> missing = importPaths.stream()
                .filter(path -> !Files.exists(lake.resolve(path)))
                .toList();
        if (missing.isEmpty()) {
            return List.of();
        }
        List<String> needed = new ArrayList<>();
        for (Path proto : bufInputs(lake)) {
            if (!proto.toString().endsWith(".proto")) {
                continue;
            }
            String content = Files.readString(proto, StandardCharsets.UTF_8);
            for (String path : missing) {
                if (!needed.contains(path) && importPattern(path).matcher(content).find()) {
                    needed.add(path);
                }
            }
        }
        return needed;
    }

    /** Matches an import statement of one proto, plain, public or weak. */
    static Pattern importPattern(String importPath) {
        return Pattern.compile("(?m)^\\s*import\\s+(?:public\\s+|weak\\s+)?\"" + Pattern.quote(importPath) + "\"");
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
            overlay.copyProvided(providedRoot, overlay.root);
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

    /** The provided protos' paths, for buf's {@code --exclude-path}. */
    List<String> providedPaths() {
        return provided;
    }

    /**
     * Writes back each buf.lock {@code buf dep update} changed in the copy,
     * keeping the lake file's permissions.
     */
    void copyLockBack() throws IOException {
        for (Path copy : bufInputs(root)) {
            if (!"buf.lock".equals(copy.getFileName().toString())) {
                continue;
            }
            Path original = lake.resolve(root.relativize(copy).toString());
            byte[] updated = Files.readAllBytes(copy);
            if (!Files.exists(original) || !Arrays.equals(Files.readAllBytes(original), updated)) {
                Files.write(original, updated);
            }
        }
    }

    /**
     * The lake's buf inputs at a commit, plus the provided protos: the
     * breaking check's baseline. A git input cannot carry the provided
     * protos, so the commit's files are extracted from {@code git archive}.
     */
    Path baseline(GitCommand git, String sha, Path providedRoot) throws IOException {
        Path dir = Files.createTempDirectory("protolake-buf-baseline-");
        temporaryDirectories.add(dir);
        Path tar = Files.createTempFile("protolake-buf-baseline-", ".tar");
        try {
            git.archive(lake, sha, tar);
            extractBufInputs(tar, dir);
        } finally {
            Files.deleteIfExists(tar);
        }
        copyProvided(providedRoot, dir);
        return dir;
    }

    /** Deletes the temporary directories; they hold only regular files this class wrote. */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (Path dir : temporaryDirectories) {
            try {
                deleteTree(dir);
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        temporaryDirectories.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private void copyProvided(Path providedRoot, Path target) throws IOException {
        for (String path : provided) {
            Path source = providedRoot.resolve(path);
            if (!Files.isRegularFile(source)) {
                throw new IOException("The protolake image provides " + path + " at " + source
                        + ", but the file is missing");
            }
            Path destination = target.resolve(path);
            if (!Files.exists(destination)) {
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
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
                if (!entry.isFile() || name.getFileName() == null
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

    /** Deletes a tree without following links: a link is removed, never its target. */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
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
