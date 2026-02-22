package io.vdp.protolake.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.validator.LakeValidator;
import io.vdp.protolake.validator.BundleValidator;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.Lake;
import protolake.v1.Language;
import protolake.v1.TargetBuildInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite implementation of StorageService.
 * 
 * This implementation uses SQLite for persistent storage of lakes, bundles, and builds.
 * The database serves as the source of truth for lake/bundle discovery, replacing
 * the previous protolake.yaml approach.
 */
@ApplicationScoped
public class SqliteStorageService implements StorageService {
    private static final Logger LOG = Logger.getLogger(SqliteStorageService.class);
    
    @ConfigProperty(name = "protolake.storage.db-path", 
                    defaultValue = "${protolake.storage.base-path}/protolake.db")
    String dbPath;
    
    @ConfigProperty(name = "protolake.storage.max-builds-per-bundle", defaultValue = "50")
    int maxBuildsPerBundle;
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;
    
    @Inject
    LakeValidator lakeValidator;
    
    @Inject
    BundleValidator bundleValidator;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonFormat.Parser protoJsonParser = JsonFormat.parser();
    private final JsonFormat.Printer protoJsonPrinter = JsonFormat.printer();
    
    @PostConstruct
    void init() {
        try {
            // Ensure parent directory exists
            Path dbFile = Paths.get(dbPath);
            Files.createDirectories(dbFile.getParent());
            
            // Initialize database schema
            initializeDatabase();
            
            LOG.infof("Initialized SQLite database at: %s (max builds per bundle: %d)", dbPath, maxBuildsPerBundle);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        }
    }
    
    private void initializeDatabase() throws SQLException {
        try (Connection conn = getConnection()) {
            // Create schema - using the updated schema with correct column names
            try (Statement stmt = conn.createStatement()) {
                // Lakes table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lakes (
                        id TEXT PRIMARY KEY,
                        proto_json TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                
                // Bundles table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS bundles (
                        lake_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        proto_json TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (lake_id, id),
                        FOREIGN KEY (lake_id) REFERENCES lakes(id) ON DELETE CASCADE
                    )
                """);
                
                // Builds table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS builds (
                        id TEXT PRIMARY KEY,
                        lake_id TEXT NOT NULL,
                        bundle_id TEXT NOT NULL,
                        target_build_info_json TEXT NOT NULL,
                        version TEXT NOT NULL,
                        branch TEXT,
                        language TEXT,
                        status TEXT,
                        build_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (lake_id, bundle_id) 
                            REFERENCES bundles(lake_id, id) ON DELETE CASCADE
                    )
                """);
                
                // Create indexes for performance
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_lake_bundle ON builds(lake_id, bundle_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_branch ON builds(branch)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_language ON builds(language)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_time ON builds(build_time DESC)");
            }
        }
    }
    
    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        // Enable foreign key constraints in SQLite
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }
    
    // ===== Lake Operations =====
    @Override
    public Lake createLake(Lake lake) throws ValidationException {

        lakeValidator.validate(lake);

        
        try (Connection conn = getConnection()) {
            // Check if already exists
            String lakeId = LakeUtil.extractLakeId(lake.getName());
            if (getLake(lakeId).isPresent()) {
                throw new IllegalStateException("Lake already exists: " + lakeId);
            }
            
            String sql = """
                INSERT INTO lakes (id, proto_json, created_at, updated_at)
                VALUES (?, ?, ?, ?)
            """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                pstmt.setString(2, protoJsonPrinter.print(lake));
                pstmt.setTimestamp(3, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(lake.getCreateTime())));
                pstmt.setTimestamp(4, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(lake.getUpdateTime())));
                
                pstmt.executeUpdate();
            }
            
            LOG.infof("Created lake: %s", lakeId);
            return lake;
            
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to create lake: " + lake.getName(), e);
        }
    }
    
    @Override
    public Optional<Lake> getLake(String id) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT proto_json FROM lakes WHERE id = ?";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(parseLake(rs.getString(1)));
                    }
                }
            }
            
            return Optional.empty();
            
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to get lake: %s", id);
            return Optional.empty();
        }
    }
    
    @Override
    public List<Lake> listLakes() {
        List<Lake> lakes = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            String sql = "SELECT proto_json FROM lakes ORDER BY id";
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    lakes.add(parseLake(rs.getString(1)));
                }
            }
            
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to list lakes");
        }
        
        return lakes;
    }
    
    @Override
    public Lake updateLake(Lake lake) throws ValidationException {
        // Validate the lake first
        lakeValidator.validate(lake);

        try (Connection conn = getConnection()) {
            String lakeId = LakeUtil.extractLakeId(lake.getName());
            String sql = """
                UPDATE lakes 
                SET proto_json = ?, updated_at = ?
                WHERE id = ?
            """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, protoJsonPrinter.print(lake));
                pstmt.setTimestamp(2, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(lake.getUpdateTime())));
                pstmt.setString(3, lakeId);
                
                int updated = pstmt.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("Lake not found: " + lakeId);
                }
            }
            
            LOG.infof("Updated lake: %s", lakeId);
            return lake;
            
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to update lake: " + lake.getName(), e);
        }
    }
    
    @Override
    public boolean deleteLake(String id) {
        try (Connection conn = getConnection()) {
            // Bundles and builds will be cascade deleted
            String sql = "DELETE FROM lakes WHERE id = ?";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                int deleted = pstmt.executeUpdate();
                
                if (deleted > 0) {
                    LOG.infof("Deleted lake: %s", id);
                    return true;
                }
            }
            
            return false;
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete lake: " + id, e);
        }
    }
    
    // ===== Bundle Operations =====
    
    @Override
    public Bundle createBundle(Bundle bundle) throws ValidationException {
        // Validate the bundle first
        bundleValidator.validate(bundle);

        try (Connection conn = getConnection()) {
            String lakeId = BundleUtil.extractLakeIdFromBundle(bundle.getName());
            String bundleId = BundleUtil.extractBundleId(bundle.getName());
            
            String sql = """
                INSERT INTO bundles (lake_id, id, proto_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
            """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                pstmt.setString(2, bundleId);
                pstmt.setString(3, protoJsonPrinter.print(bundle));
                pstmt.setTimestamp(4, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(bundle.getCreateTime())));
                pstmt.setTimestamp(5, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(bundle.getUpdateTime())));
                
                pstmt.executeUpdate();
            }
            
            LOG.infof("Created bundle: %s/%s", lakeId, bundleId);
            return bundle;
            
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to create bundle: " + bundle.getName(), e);
        }
    }
    
    @Override
    public Optional<Bundle> getBundle(String lakeId, String bundleId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT proto_json FROM bundles WHERE lake_id = ? AND id = ?";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                pstmt.setString(2, bundleId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(parseBundle(rs.getString(1)));
                    }
                }
            }
            
            return Optional.empty();
            
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to get bundle: %s/%s", lakeId, bundleId);
            return Optional.empty();
        }
    }
    
    @Override
    public List<Bundle> listBundles(String lakeId) {
        List<Bundle> bundles = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            String sql = "SELECT proto_json FROM bundles WHERE lake_id = ? ORDER BY id";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        bundles.add(parseBundle(rs.getString(1)));
                    }
                }
            }
            
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to list bundles for lake: %s", lakeId);
        }
        
        return bundles;
    }
    
    @Override
    public Bundle updateBundle(Bundle bundle) throws ValidationException {
        // Validate the bundle first
        bundleValidator.validate(bundle);

        try (Connection conn = getConnection()) {
            String lakeId = BundleUtil.extractLakeIdFromBundle(bundle.getName());
            String bundleId = BundleUtil.extractBundleId(bundle.getName());
            
            String sql = """
                UPDATE bundles 
                SET proto_json = ?, updated_at = ?
                WHERE lake_id = ? AND id = ?
            """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, protoJsonPrinter.print(bundle));
                pstmt.setTimestamp(2, Timestamp.from(
                    LakeUtil.fromProtoTimestamp(bundle.getUpdateTime())));
                pstmt.setString(3, lakeId);
                pstmt.setString(4, bundleId);
                
                int updated = pstmt.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("Bundle not found: " + bundle.getName());
                }
            }
            
            LOG.infof("Updated bundle: %s/%s", lakeId, bundleId);
            return bundle;
            
        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to update bundle: " + bundle.getName(), e);
        }
    }
    
    @Override
    public boolean deleteBundle(String lakeId, String bundleId) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM bundles WHERE lake_id = ? AND id = ?";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                pstmt.setString(2, bundleId);
                int deleted = pstmt.executeUpdate();
                
                if (deleted > 0) {
                    LOG.infof("Deleted bundle: %s/%s", lakeId, bundleId);
                    return true;
                }
            }
            
            return false;
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete bundle: " + lakeId + "/" + bundleId, e);
        }
    }
    
    // ===== Build Operations =====
    
    @Override
    public TargetBuildInfo recordTargetBuild(String lakeId, String bundleId, TargetBuildInfo buildInfo) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                String buildId = UUID.randomUUID().toString();
                String language = extractLanguage(buildInfo);

                String sql = """
                    INSERT INTO builds (id, lake_id, bundle_id, target_build_info_json,
                                      version, language, status, build_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, buildId);
                    pstmt.setString(2, lakeId);
                    pstmt.setString(3, bundleId);
                    pstmt.setString(4, protoJsonPrinter.print(buildInfo));
                    pstmt.setString(5, buildInfo.getVersion());
                    pstmt.setString(6, language);
                    pstmt.setString(7, buildInfo.getStatus().name());
                    pstmt.setTimestamp(8, Timestamp.from(Instant.now()));

                    pstmt.executeUpdate();
                }

                // Prune old builds
                pruneOldBuildsInternal(conn, lakeId, bundleId);

                conn.commit();
                LOG.infof("Recorded build for %s/%s", lakeId, bundleId);

                return buildInfo;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException | InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to record build", e);
        }
    }
    
    @Override
    public List<TargetBuildInfo> listBuilds(String lakeId, String bundleId) {
        List<TargetBuildInfo> builds = new ArrayList<>();
        
        try (Connection conn = getConnection()) {
            String sql = """
                SELECT target_build_info_json FROM builds 
                WHERE lake_id = ? AND bundle_id = ? 
                ORDER BY build_time DESC, id DESC
            """;
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, lakeId);
                pstmt.setString(2, bundleId);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        builds.add(parseTargetBuildInfo(rs.getString(1)));
                    }
                }
            }
            
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to list builds");
        }
        
        return builds;
    }
    
    /**
     * Internal method to prune old builds within a transaction.
     */
    private int pruneOldBuildsInternal(Connection conn, String lakeId, String bundleId)
            throws SQLException {
        // Delete builds beyond the limit, keeping the most recent ones
        // Use both build_time and id for deterministic ordering
        LOG.debugf("Pruning builds for %s/%s with limit %d", lakeId, bundleId, maxBuildsPerBundle);
        
        String sql = """
            DELETE FROM builds 
            WHERE lake_id = ? AND bundle_id = ? 
            AND id NOT IN (
                SELECT id FROM builds 
                WHERE lake_id = ? AND bundle_id = ?
                ORDER BY build_time DESC, id DESC
                LIMIT ?
            )
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lakeId);
            pstmt.setString(2, bundleId);
            pstmt.setString(3, lakeId);
            pstmt.setString(4, bundleId);
            pstmt.setInt(5, maxBuildsPerBundle);

            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                LOG.infof("Pruned %d old builds for %s/%s (keeping %d)", deleted, lakeId, bundleId, maxBuildsPerBundle);
            }
            return deleted;
        }
    }


    
    // ===== Helper Methods =====
    
    private Lake parseLake(String json) {
        try {
            Lake.Builder builder = Lake.newBuilder();
            protoJsonParser.merge(json, builder);
            return builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse Lake proto", e);
        }
    }
    
    private Bundle parseBundle(String json) {
        try {
            Bundle.Builder builder = Bundle.newBuilder();
            protoJsonParser.merge(json, builder);
            return builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse Bundle proto", e);
        }
    }
    
    private TargetBuildInfo parseTargetBuildInfo(String json) {
        try {
            TargetBuildInfo.Builder builder = TargetBuildInfo.newBuilder();
            protoJsonParser.merge(json, builder);
            return builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse TargetBuildInfo", e);
        }
    }
    
    private String extractLanguage(TargetBuildInfo buildInfo) {
        // Try to extract from artifacts
        if (buildInfo.getArtifactsCount() > 0) {
            return buildInfo.getArtifactsMap().keySet().iterator().next();
        }
        
        // Try to parse from target name
        String target = buildInfo.getTarget();
        if (target.contains("java")) return Language.JAVA.name();
        if (target.contains("python") || target.contains("py")) return Language.PYTHON.name();
        if (target.contains("javascript") || target.contains("js")) return Language.JAVASCRIPT.name();
        if (target.contains("go")) return Language.GO.name();
        
        return Language.LANGUAGE_UNSPECIFIED.name();
    }
}
