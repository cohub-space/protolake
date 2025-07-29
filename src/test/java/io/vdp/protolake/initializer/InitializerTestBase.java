package io.vdp.protolake.initializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for initializer integration tests.
 * 
 * Provides common utilities for testing file generation and validation.
 */
// Base class for initializer tests - each test class should have @QuarkusTest
public abstract class InitializerTestBase {
    
    protected Path basePath;
    
    // Note: Subclasses should set basePath in their @BeforeEach method
    // to use the configured test directory from application.properties
    
    @AfterEach
    void cleanupBase() throws IOException {
        // Clean up the test directory if it exists
        if (basePath != null && Files.exists(basePath)) {
            deleteRecursively(basePath);
        }
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     */
    protected void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Best effort deletion
                    }
                });
        }
    }
    
    /**
     * Asserts that files exist in the given directory.
     */
    protected void assertFileExists(Path dir, String... files) {
        for (String file : files) {
            assertThat(dir.resolve(file))
                .as("File should exist: " + file)
                .exists()
                .isRegularFile();
        }
    }
    
    /**
     * Asserts that a file exists and is executable.
     */
    protected void assertExecutableFile(Path file) {
        assertThat(file)
            .as("File should exist: " + file)
            .exists()
            .isRegularFile();
            
        // Check if file system supports POSIX permissions
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            assertThat(perms)
                .as("File should be executable: " + file)
                .containsAnyOf(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE
                );
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system, skip permission check
        }
    }
    
    /**
     * Asserts that a file contains all expected content strings.
     */
    protected void assertFileContains(Path file, String... expectedContent) throws IOException {
        assertThat(file).exists();
        String content = Files.readString(file);
        
        for (String expected : expectedContent) {
            assertThat(content)
                .as("File " + file + " should contain: " + expected)
                .contains(expected);
        }
    }
    
    /**
     * Asserts that a file does not contain any of the given strings.
     */
    protected void assertFileDoesNotContain(Path file, String... unexpectedContent) throws IOException {
        assertThat(file).exists();
        String content = Files.readString(file);
        
        for (String unexpected : unexpectedContent) {
            assertThat(content)
                .as("File " + file + " should not contain: " + unexpected)
                .doesNotContain(unexpected);
        }
    }
    
    /**
     * Asserts that directories exist at the given paths relative to root.
     */
    protected void assertDirectoryStructure(Path root, String... expectedPaths) {
        for (String path : expectedPaths) {
            assertThat(root.resolve(path))
                .as("Directory should exist: " + path)
                .exists()
                .isDirectory();
        }
    }
    
    /**
     * Asserts that a directory exists.
     */
    protected void assertDirectoryExists(Path dir) {
        assertThat(dir)
            .as("Directory should exist: " + dir)
            .exists()
            .isDirectory();
    }
    
    /**
     * Gets the content of a file as a string.
     */
    protected String readFile(Path file) throws IOException {
        return Files.readString(file);
    }
}
