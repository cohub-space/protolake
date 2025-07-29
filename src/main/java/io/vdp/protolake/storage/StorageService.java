package io.vdp.protolake.storage;

import protolake.v1.Bundle;
import protolake.v1.Lake;
import protolake.v1.Language;
import protolake.v1.TargetBuildInfo;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for ProtoLake metadata.
 * 
 * This interface provides lake and bundle storage operations using protobuf messages,
 * while bundle information is discovered dynamically from the filesystem via bundle.yaml files.
 * 
 * The workspace configuration (protolake.yaml) is managed separately to track
 * lake references and workspace-level settings.
 */
public interface StorageService {

    // ===== Lake Operations =====

    /**
     * Creates a new lake record.
     * 
     * @param lake The lake to create
     * @return The created lake with any generated fields populated
     * @throws IllegalStateException if a lake with the same name already exists
     */
    Lake createLake(Lake lake) throws ValidationException;

    /**
     * Retrieves a lake by id.
     * 
     * @param id The lake id
     * @return The lake if found, empty otherwise
     */
    Optional<Lake> getLake(String id);

    /**
     * Lists all lakes.
     * 
     * @return List of all lakes
     */
    List<Lake> listLakes();

    /**
     * Updates an existing lake.
     * 
     * @param lake The lake to update
     * @return The updated lake
     * @throws IllegalArgumentException if the lake doesn't exist
     */
    Lake updateLake(Lake lake) throws ValidationException;

    /**
     * Deletes a lake by id.
     * 
     * @param id The lake id
     * @return true if deleted, false if not found
     */
    boolean deleteLake(String id);

    // ===== Bundle Operations =====
    // Note: Bundles are now discovered dynamically from filesystem
    
    /**
     * Creates a new bundle record.
     * 
     * @param bundle The bundle to create
     * @return The created bundle
     */
    Bundle createBundle(Bundle bundle) throws ValidationException;

    /**
     * Retrieves a bundle by lake and bundle id.
     * Uses dynamic discovery to find bundle.yaml files.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @return The bundle if found, empty otherwise
     */
    Optional<Bundle> getBundle(String lakeId, String bundleId);

    /**
     * Lists all bundles in a lake.
     * Discovers bundles dynamically by scanning for bundle.yaml files.
     * 
     * @param lakeId The lake id
     * @return List of discovered bundles in the lake
     */
    List<Bundle> listBundles(String lakeId);
    
    /**
     * Updates an existing bundle.
     * 
     * @param bundle The bundle to update
     * @return The updated bundle
     * @throws IllegalArgumentException if the bundle doesn't exist
     */
    Bundle updateBundle(Bundle bundle) throws ValidationException;
    
    /**
     * Deletes a bundle by lake and bundle name.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @return true if deleted, false if not found
     */
    boolean deleteBundle(String lakeId, String bundleId);

    // ===== Build Operations =====
    
    /**
     * Records a build from TargetBuildInfo.
     * Automatically prunes old builds to maintain storage limits.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @param branch The git branch
     * @param buildInfo The target build info
     * @return The recorded build info
     */
    TargetBuildInfo recordTargetBuild(String lakeId, String bundleId,
                                      String branch, TargetBuildInfo buildInfo);
    
    /**
     * Gets the latest TargetBuildInfo for a bundle.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @param branch The git branch
     * @param language The target language (optional, null for any)
     * @return The latest build info if found
     */
    Optional<TargetBuildInfo> getLatestTargetBuild(String lakeId, String bundleId,
                                                  String branch, Language language);

    /**
     * Lists all builds for a bundle.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @return List of all builds for the bundle
     */
    List<TargetBuildInfo> listBuilds(String lakeId, String bundleId);

    /**
     * Lists builds for a bundle filtered by branch.
     * 
     * @param lakeId The lake id
     * @param bundleId The bundle id
     * @param branch The git branch
     * @return List of builds on the specified branch
     */
    List<TargetBuildInfo> listBuildsByBranch(String lakeId, String bundleId, String branch);
}
