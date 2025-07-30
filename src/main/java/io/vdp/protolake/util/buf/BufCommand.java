package io.vdp.protolake.util.buf;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for executing buf commands.
 * 
 * Provides a type-safe interface for buf operations with proper error handling
 * and timeout management. Buf is used for proto validation, linting, and breaking
 * change detection.
 */
@ApplicationScoped
public class BufCommand {
    private static final Logger LOG = Logger.getLogger(BufCommand.class);

    @ConfigProperty(name = "protolake.buf.command", defaultValue = "buf")
    String bufCommand;

    @ConfigProperty(name = "protolake.buf.timeout-seconds", defaultValue = "60")
    int timeoutSeconds;

    /**
     * Runs buf build to check if protos compile successfully.
     * 
     * @param directory the directory containing protos to build
     * @return list of lines from stderr if build fails, empty if successful
     */
    public List<String> build(Path directory) throws IOException {
        LOG.debugf("Running buf build in: %s", directory);
        
        List<String> errors = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(bufCommand, "build");
        pb.directory(directory.toFile());
        
        Process process = pb.start();
        
        try {
            // Capture error output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errors.add(line);
                }
            }
            
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Buf build timed out after " + timeoutSeconds + " seconds");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOG.debugf("Buf build failed with exit code: %d", exitCode);
            }
            
            return errors;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Buf build interrupted", e);
        }
    }

    /**
     * Runs buf lint and returns lint violations.
     * 
     * @param directory the directory containing protos to lint
     * @return list of lint violation lines from stdout
     */
    public List<String> lint(Path directory) throws IOException {
        LOG.debugf("Running buf lint in: %s", directory);
        
        return runWithOutput(directory, "lint");
    }

    /**
     * Runs buf breaking to detect breaking changes.
     * 
     * @param directory the directory containing protos to check
     * @param against the reference to compare against (e.g., ".git#branch=HEAD~1")
     * @return list of breaking change descriptions from stdout
     */
    public List<String> breaking(Path directory, String against) throws IOException {
        LOG.debugf("Running buf breaking in: %s against %s", directory, against);
        
        return runWithOutput(directory, "breaking", "--against", against);
    }

    /**
     * Runs buf format to check formatting.
     * 
     * @param directory the directory containing protos to format check
     * @param diff if true, shows diff output; if false, fixes formatting
     * @return list of format diff lines if diff=true, empty if fix mode
     */
    public List<String> format(Path directory, boolean diff) throws IOException {
        LOG.debugf("Running buf format in: %s (diff=%s)", directory, diff);
        
        if (diff) {
            return runWithOutput(directory, "format", "--diff");
        } else {
            runWithOutput(directory, "format");
            return new ArrayList<>();
        }
    }

    /**
     * Gets the buf version.
     * 
     * @return version string
     */
    public String getVersion() throws IOException {
        List<String> output = runWithOutput(Path.of("."), "--version");
        return output.isEmpty() ? "unknown" : output.get(0);
    }

    /**
     * Runs a buf command and returns the output lines.
     * 
     * @param directory working directory
     * @param args command arguments
     * @return list of output lines from stdout
     */
    private List<String> runWithOutput(Path directory, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(bufCommand);
        for (String arg : args) {
            command.add(arg);
        }
        
        LOG.debugf("Executing: %s in %s", String.join(" ", command), directory);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        
        Process process = pb.start();
        
        try {
            List<String> output = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            
            // Read standard output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            
            // Read error output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errors.add(line);
                }
            }
            
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Buf command timed out after " + timeoutSeconds + " seconds");
            }
            
            int exitCode = process.exitValue();
            if (exitCode > 1) {
                // Exit code 1 is often used for "found issues" which is expected
                // Exit code > 1 indicates actual command failure
                String errorMsg = String.join("\n", errors);
                throw new IOException("Buf command failed with exit code " + exitCode + 
                    ": " + errorMsg);
            }
            
            return output;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Buf command interrupted", e);
        }
    }
}