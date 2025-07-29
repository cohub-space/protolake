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
    String startupOptions;

    /**
     * Runs a bazel command with the given arguments.
     */
    public void run(Path workingDir, String... args) throws IOException {
        List<String> command = buildCommand(args);
        executeCommand(workingDir, command, null, null);
    }
    
    /**
     * Runs a bazel command with the given arguments and captures output.
     */
    public void run(Path workingDir, String command, String subcommand, Consumer<String> outputConsumer) throws IOException {
        List<String> fullCommand = buildCommand(command, subcommand);
        executeCommand(workingDir, fullCommand, null, outputConsumer);
    }

    /**
     * Runs a bazel command with environment variables.
     */
    public void runWithEnv(Path workingDir, Map<String, String> env, String... args) throws IOException {
        List<String> command = buildCommand(args);
        executeCommand(workingDir, command, env, null);
    }

    /**
     * Runs a bazel command and returns the output.
     */
    public String runWithOutput(Path workingDir, String... args) throws IOException {
        List<String> command = buildCommand(args);
        return executeCommandWithOutput(workingDir, command, null);
    }

    /**
     * Builds the full command with bazel executable and startup options.
     */
    private List<String> buildCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add(bazelCommand);
        
        // Add startup options if configured
        if (startupOptions != null && !startupOptions.isEmpty()) {
            for (String option : startupOptions.split(" ")) {
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
        
        if (env != null) {
            pb.environment().putAll(env);
        }
        
        Process process = pb.start();
        
        try {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            
            // Read standard output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // Read error output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
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
                    "\nError: " + error);
            }
            
            return output.toString();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Bazel command interrupted", e);
        }
    }

    /**
     * Checks if bazel is available and working.
     */
    public boolean isAvailable() {
        try {
            List<String> command = buildCommand("version");
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.warnf("Bazel not available: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Gets the Bazel version.
     */
    public String getVersion() throws IOException {
        String output = runWithOutput(Path.of("."), "version");
        
        // Parse version from output
        for (String line : output.split("\n")) {
            if (line.startsWith("Build label:")) {
                return line.substring("Build label:".length()).trim();
            }
        }
        
        return "unknown";
    }
}