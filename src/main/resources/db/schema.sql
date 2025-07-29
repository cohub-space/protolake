-- ProtoLake SQLite Database Schema
-- This schema stores lake metadata, bundle information, and build history

-- Lakes table: stores proto lake (repository) information
CREATE TABLE IF NOT EXISTS lakes (
    id TEXT PRIMARY KEY,                    -- Unique lake identifier
    display_name TEXT,                        -- Human-readable name
    description TEXT,                         -- Optional description
    config_json TEXT,                         -- Serialized LakeConfig proto
    lake_prefix TEXT,                         -- Path prefix from base workspace
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Bundles table: stores proto bundle information
-- Bundles are discovered from bundle.yaml files in the filesystem
CREATE TABLE IF NOT EXISTS bundles (
    lake_id TEXT NOT NULL,                    -- Parent lake
    id TEXT PRIMARY KEY,                      -- Bundle identifier
    display_name TEXT,                        -- Human-readable name
    description TEXT,                         -- Optional description
    bundle_prefix TEXT,                       -- Path prefix from lake root
    config_json TEXT,                         -- Serialized BundleConfig proto
    base_version TEXT,                        -- Base version for builds
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (lake_id) REFERENCES lakes(id) ON DELETE CASCADE,
    UNIQUE(lake_id, id)                       -- Each bundle name must be unique within a lake
);

-- Builds table: stores build history with TargetBuildInfo
CREATE TABLE IF NOT EXISTS builds (
    id TEXT PRIMARY KEY,                      -- Unique build identifier (UUID)
    lake_id TEXT NOT NULL,                    -- Lake containing the bundle
    bundle_id TEXT NOT NULL,                  -- Bundle that was built
    target_build_info_json TEXT NOT NULL,     -- Serialized TargetBuildInfo proto
    version TEXT NOT NULL,                    -- Version string (e.g., "1.0.0-feature-x")
    branch TEXT,                              -- Git branch at build time
    language TEXT,                            -- Language (JAVA, PYTHON, etc.)
    status TEXT,                              -- Build status (PENDING, BUILDING, PUBLISHED, FAILED)
    build_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (lake_id, bundle_id) 
        REFERENCES bundles(lake_id, id) ON DELETE CASCADE
);

-- Indexes for query performance
CREATE INDEX IF NOT EXISTS idx_builds_lake_bundle ON builds(lake_id, bundle_id);
CREATE INDEX IF NOT EXISTS idx_builds_branch ON builds(branch);
CREATE INDEX IF NOT EXISTS idx_builds_language ON builds(language);
CREATE INDEX IF NOT EXISTS idx_builds_time ON builds(build_time DESC);

-- Trigger to update the updated_at timestamp on lakes
CREATE TRIGGER IF NOT EXISTS update_lake_timestamp 
AFTER UPDATE ON lakes
BEGIN
    UPDATE lakes SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

-- Trigger to update the updated_at timestamp on bundles
CREATE TRIGGER IF NOT EXISTS update_bundle_timestamp 
AFTER UPDATE ON bundles
BEGIN
    UPDATE bundles SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
