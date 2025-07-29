package io.vdp.protolake.util.git;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/**
 * Manages branch-aware versioning for ProtoLake artifacts.
 * 
 * Appends branch names to versions to enable parallel development
 * without version conflicts.
 */
@ApplicationScoped
public class BranchVersionManager {
    private static final Logger LOG = Logger.getLogger(BranchVersionManager.class);
    
    // Pattern for valid version characters
    private static final Pattern INVALID_VERSION_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    
    // Common branch prefixes to shorten
    private static final String[] BRANCH_PREFIXES = {
        "feature/", "bugfix/", "hotfix/", "release/", "develop/"
    };

    /**
     * Gets a version string with branch suffix appended.
     * 
     * @param baseVersion The base version (e.g., "1.0.0")
     * @param branch The git branch name
     * @return Version with branch suffix (e.g., "1.0.0-feature-x")
     */
    public String getVersionWithBranch(String baseVersion, String branch) {
        if (baseVersion == null || baseVersion.isEmpty()) {
            throw new IllegalArgumentException("Base version cannot be null or empty");
        }
        
        if (branch == null || branch.isEmpty()) {
            branch = "main";
        }
        
        // Sanitize branch name for version string
        String branchSuffix = sanitizeBranchName(branch);
        
        // Combine base version with branch suffix
        String version = baseVersion + "-" + branchSuffix;
        
        LOG.debugf("Generated version %s from base %s and branch %s", 
            version, baseVersion, branch);
        
        return version;
    }

    /**
     * Extracts the base version from a branch-aware version.
     * 
     * @param versionWithBranch Version that may include branch suffix
     * @return Base version without branch suffix
     */
    public String extractBaseVersion(String versionWithBranch) {
        if (versionWithBranch == null || versionWithBranch.isEmpty()) {
            return versionWithBranch;
        }
        
        // Find the last hyphen that's likely to be the branch separator
        int lastHyphen = versionWithBranch.lastIndexOf('-');
        if (lastHyphen > 0) {
            String possibleBase = versionWithBranch.substring(0, lastHyphen);
            // Check if this looks like a version (contains dots)
            if (possibleBase.contains(".")) {
                return possibleBase;
            }
        }
        
        // If no branch suffix found, return as-is
        return versionWithBranch;
    }

    /**
     * Extracts the branch name from a branch-aware version.
     * 
     * @param versionWithBranch Version that includes branch suffix
     * @return Branch name or null if no branch suffix found
     */
    public String extractBranch(String versionWithBranch) {
        if (versionWithBranch == null || versionWithBranch.isEmpty()) {
            return null;
        }
        
        int lastHyphen = versionWithBranch.lastIndexOf('-');
        if (lastHyphen > 0 && lastHyphen < versionWithBranch.length() - 1) {
            String possibleBase = versionWithBranch.substring(0, lastHyphen);
            // Check if this looks like a version (contains dots)
            if (possibleBase.contains(".")) {
                return versionWithBranch.substring(lastHyphen + 1);
            }
        }
        
        return null;
    }

    /**
     * Sanitizes a branch name to be valid in a version string.
     * 
     * @param branch Raw branch name from git
     * @return Sanitized branch name suitable for version strings
     */
    public String sanitizeBranchName(String branch) {
        if (branch == null || branch.isEmpty()) {
            return "unknown";
        }
        
        // Remove common prefixes to keep versions shorter
        String sanitized = branch;
        for (String prefix : BRANCH_PREFIXES) {
            if (sanitized.startsWith(prefix)) {
                sanitized = sanitized.substring(prefix.length());
                break;
            }
        }
        
        // Replace invalid characters with hyphens
        sanitized = INVALID_VERSION_CHARS.matcher(sanitized).replaceAll("-");
        
        // Remove leading/trailing hyphens
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        
        // Collapse multiple hyphens into one
        sanitized = sanitized.replaceAll("-+", "-");
        
        // Limit length to keep versions reasonable
        if (sanitized.length() > 30) {
            sanitized = sanitized.substring(0, 30);
            // Remove trailing hyphen if we cut in the middle
            sanitized = sanitized.replaceAll("-$", "");
        }
        
        // Default to "unknown" if we end up with empty string
        if (sanitized.isEmpty()) {
            sanitized = "unknown";
        }
        
        return sanitized.toLowerCase();
    }

    /**
     * Checks if a version string includes a branch suffix.
     * 
     * @param version Version string to check
     * @return true if version includes a branch suffix
     */
    public boolean hasBranchSuffix(String version) {
        return extractBranch(version) != null;
    }

    /**
     * Validates that a version string is valid for package managers.
     * 
     * @param version Version string to validate
     * @return true if version is valid
     */
    public boolean isValidVersion(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        
        // Basic semantic version pattern with optional branch suffix
        // Matches: 1.0.0, 1.0.0-beta, 1.0.0-feature-x
        Pattern versionPattern = Pattern.compile(
            "^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9._-]+)?$"
        );
        
        return versionPattern.matcher(version).matches();
    }
}