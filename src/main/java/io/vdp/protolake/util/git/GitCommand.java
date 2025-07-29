package io.vdp.protolake.util.git;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for executing git commands.
 * 
 * Provides a type-safe interface for common git operations used by ProtoLake.
 */
@ApplicationScoped
public class GitCommand {
    private static final Logger LOG = Logger.getLogger(GitCommand.class);
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    /**
     * Initializes a new git repository.
     */
    public void init(Path directory) throws IOException {
        executeGit(directory, "init");
        LOG.debugf("Initialized git repository at: %s", directory);
    }

    /**
     * Sets a git configuration value.
     */
    public void config(Path directory, String key, String value) throws IOException {
        executeGit(directory, "config", key, value);
        LOG.debugf("Set git config %s=%s", key, value);
    }

    /**
     * Adds all files to the git index.
     */
    public void addAll(Path directory) throws IOException {
        executeGit(directory, "add", "-A");
        LOG.debugf("Added all files to git index");
    }

    /**
     * Adds specific file or directory to the git index.
     */
    public void add(Path directory, String path) throws IOException {
        executeGit(directory, "add", path);
        LOG.debugf("Added %s to git index", path);
    }

    /**
     * Commits changes with the given message.
     */
    public void commit(Path directory, String message) throws IOException {
        executeGit(directory, "commit", "-m", message);
        LOG.debugf("Committed with message: %s", message);
    }

    /**
     * Gets the current branch name.
     */
    public String getCurrentBranch(Path directory) throws IOException {
        String result = executeGitWithOutput(directory, "rev-parse", "--abbrev-ref", "HEAD");
        String branch = result.trim();
        LOG.debugf("Current branch: %s", branch);
        return branch;
    }

    /**
     * Creates and checks out a new branch.
     */
    public void createBranch(Path directory, String branchName) throws IOException {
        executeGit(directory, "checkout", "-b", branchName);
        LOG.debugf("Created and checked out branch: %s", branchName);
    }

    /**
     * Checks out an existing branch.
     */
    public void checkout(Path directory, String branchName) throws IOException {
        executeGit(directory, "checkout", branchName);
        LOG.debugf("Checked out branch: %s", branchName);
    }

    /**
     * Gets the list of modified files.
     */
    public List<String> getModifiedFiles(Path directory) throws IOException {
        String result = executeGitWithOutput(directory, "diff", "--name-only", "HEAD");
        List<String> files = new ArrayList<>();
        if (!result.isEmpty()) {
            for (String line : result.split("\n")) {
                if (!line.trim().isEmpty()) {
                    files.add(line.trim());
                }
            }
        }
        return files;
    }

    /**
     * Checks if the working directory is clean (no uncommitted changes).
     */
    public boolean isClean(Path directory) throws IOException {
        String result = executeGitWithOutput(directory, "status", "--porcelain");
        return result.trim().isEmpty();
    }

    /**
     * Gets the current commit hash.
     */
    public String getCurrentCommit(Path directory) throws IOException {
        String result = executeGitWithOutput(directory, "rev-parse", "HEAD");
        return result.trim();
    }

    /**
     * Executes a git command without capturing output.
     */
    private void executeGit(Path directory, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try {
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out: " + String.join(" ", command));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                // Read error output
                StringBuilder error = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        error.append(line).append("\n");
                    }
                }
                throw new IOException("Git command failed with exit code " + exitCode + 
                    ": " + String.join(" ", command) + "\n" + error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    /**
     * Executes a git command and returns the output.
     */
    private String executeGitWithOutput(Path directory, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());

        Process process = pb.start();
        try {
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out: " + String.join(" ", command));
            }

            int exitCode = process.exitValue();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (exitCode != 0) {
                // Read error output
                StringBuilder error = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        error.append(line).append("\n");
                    }
                }
                throw new IOException("Git command failed with exit code " + exitCode + 
                    ": " + String.join(" ", command) + "\n" + error);
            }

            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }
}