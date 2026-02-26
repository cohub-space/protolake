package io.vdp.protolake.util.bazel;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.Optional;

/**
 * Wrapper for executing Bazel commands.
 * 
 * Provides a type-safe interface for Bazel operations with proper error handling
 * and timeout management.
 */
@ApplicationScoped
public class BazelCommand {
    private static final Logger LOG = Logger.getLogger(BazelCommand.class);

    @ConfigProperty(name = "protolake.bazel.command", defaultValue = "bazel")
    String bazelCommand;

    @ConfigProperty(name = "protolake.bazel.timeout-seconds", defaultValue = "300")
    int timeoutSeconds;

    @ConfigProperty(name = "protolake.bazel.startup-options")
    Optional<String> startupOptions;

    /**
     * Runs a bazel command with the given arguments.
     */
    public void run(Path workingDir, String... args) throws IOException {
        List<String> command = buildCommand(args);
        executeCommand(workingDir, command, null, null);
    }

    /**
     * Runs a bazel command and returns the output.
     */
    public String runWithOutput(Path workingDir, String... args) throws IOException {
        List<String> command = buildCommand(args);
        return executeCommandWithOutput(workingDir, command, null);
    }

    /**
     * Runs a bazel command with additional environment variables and returns the output.
     */
    public String runWithOutput(Path workingDir, Map<String, String> env, String... args) throws IOException {
        List<String> command = buildCommand(args);
        return executeCommandWithOutput(workingDir, command, env);
    }

    /**
     * Result of a command execution, including output and exit code.
     */
    public static class CommandResult {
        public final String output;
        public final int exitCode;

        public CommandResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Runs a bazel command and returns the result (output + exit code) without throwing on non-zero exit.
     */
    public CommandResult runWithResult(Path workingDir, String... args) throws IOException {
        List<String> command = buildCommand(args);
        return executeCommandWithResult(workingDir, command, null);
    }

    /**
     * Builds the full command with bazel executable and startup options.
     */
    private List<String> buildCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add(bazelCommand);
        
        // Add startup options if configured
        if (startupOptions.isPresent() && !startupOptions.get().isEmpty()) {
            for (String option : startupOptions.get().split(" ")) {
                if (!option.trim().isEmpty()) {
                    command.add(option.trim());
                }
            }
        }
        
        // Add the actual command arguments
        for (String arg : args) {
            command.add(arg);
        }
        
        return command;
    }

    /**
     * Executes a command with optional output capture.
     */
    private void executeCommand(Path workingDir, List<String> command, 
                               Map<String, String> env, Consumer<String> outputConsumer) throws IOException {
        LOG.debugf("Executing: %s in %s", String.join(" ", command), workingDir);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        
        if (env != null) {
            pb.environment().putAll(env);
        }
        
        Process process = pb.start();
        
        try {
            // Read output to prevent buffer overflow
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(line);
                    }
                    if (outputConsumer != null) {
                        outputConsumer.accept(line);
                    }
                }
            }
            
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Bazel command timed out after " + timeoutSeconds + " seconds");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Bazel command failed with exit code " + exitCode);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Bazel command interrupted", e);
        }
    }

    /**
     * Executes a command and returns the output.
     */
    private String executeCommandWithOutput(Path workingDir, List<String> command,
                                          Map<String, String> env) throws IOException {
        LOG.debugf("Executing: %s in %s", String.join(" ", command), workingDir);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        if (env != null) {
            pb.environment().putAll(env);
        }

        Process process = pb.start();

        try {
            StringBuilder output = new StringBuilder();

            // Read combined stdout+stderr to prevent deadlock
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(line);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Bazel command timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Bazel command failed with exit code " + exitCode +
                    "\nError: " + output);
            }

            return output.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Bazel command interrupted", e);
        }
    }

    /**
     * Executes a command and returns output + exit code without throwing on non-zero exit.
     * Only throws on timeout, interrupt, or process start failure.
     */
    private CommandResult executeCommandWithResult(Path workingDir, List<String> command,
                                                   Map<String, String> env) throws IOException {
        LOG.debugf("Executing: %s in %s", String.join(" ", command), workingDir);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        if (env != null) {
            pb.environment().putAll(env);
        }

        Process process = pb.start();

        try {
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(line);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Bazel command timed out after " + timeoutSeconds + " seconds");
            }

            return new CommandResult(output.toString(), process.exitValue());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Bazel command interrupted", e);
        }
    }
}