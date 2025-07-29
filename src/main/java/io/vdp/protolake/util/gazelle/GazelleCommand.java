package io.vdp.protolake.util.gazelle;

import io.vdp.protolake.util.bazel.BazelCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper for executing Gazelle commands through Bazel.
 * 
 * Gazelle automatically generates BUILD.bazel files by analyzing
 * import statements in proto files.
 */
@ApplicationScoped
public class GazelleCommand {
    private static final Logger LOG = Logger.getLogger(GazelleCommand.class);

    @Inject
    BazelCommand bazelCommand;

    /**
     * Runs gazelle with default options.
     */
    public void run(Path workingDir) throws IOException {
        run(workingDir, new String[0]);
    }

    /**
     * Runs gazelle with custom arguments.
     */
    public void run(Path workingDir, String... args) throws IOException {
        LOG.debugf("Running gazelle in: %s", workingDir);
        
        List<String> command = new ArrayList<>();
        command.add("run");
        command.add("//:gazelle");
        command.add("--");
        
        // Add custom arguments
        for (String arg : args) {
            command.add(arg);
        }
        
        try {
            bazelCommand.run(workingDir, command.toArray(new String[0]));
            LOG.debugf("Gazelle completed successfully");
        } catch (Exception e) {
            throw new IOException("Gazelle failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs gazelle in update mode (default behavior).
     */
    public void update(Path workingDir) throws IOException {
        run(workingDir, "-mode=update");
    }

    /**
     * Runs gazelle in fix mode to update imports.
     */
    public void fix(Path workingDir) throws IOException {
        run(workingDir, "-mode=fix");
    }

    /**
     * Runs gazelle in diff mode to show what would change.
     */
    public String diff(Path workingDir) throws IOException {
        LOG.debugf("Running gazelle diff in: %s", workingDir);
        
        try {
            return bazelCommand.runWithOutput(workingDir, 
                "run", "//:gazelle", "--", "-mode=diff");
        } catch (Exception e) {
            throw new IOException("Gazelle diff failed: " + e.getMessage(), e);
        }
    }

    /**
     * Updates proto imports to use the correct format.
     */
    public void updateProtoImports(Path workingDir) throws IOException {
        LOG.debugf("Updating proto imports in: %s", workingDir);
        
        run(workingDir, 
            "-proto=default",
            "-proto_group=tag=proto_library",
            "-build_file_name=BUILD.bazel"
        );
    }

    /**
     * Runs gazelle for a specific directory only.
     */
    public void runForDirectory(Path workingDir, String directory) throws IOException {
        LOG.debugf("Running gazelle for directory: %s", directory);
        
        run(workingDir,
            "-r",  // Recursive
            "-build_file_name=BUILD.bazel",
            directory
        );
    }

    /**
     * Configures gazelle directives in BUILD files.
     */
    public void configure(Path workingDir, String directive, String value) throws IOException {
        LOG.debugf("Configuring gazelle: %s = %s", directive, value);
        
        String arg = String.format("-go_prefix=%s", value);
        if (!directive.equals("go_prefix")) {
            arg = String.format("-%s=%s", directive, value);
        }
        
        run(workingDir, arg);
    }
}