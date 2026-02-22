package io.vdp.protolake.cli;

import protolake.v1.BuildOperationMetadata;

/**
 * Listener for build progress events in CLI mode.
 * Replaces the InMemoryOperationManager for synchronous CLI builds.
 */
public interface BuildProgressListener {

    void onPhaseStart(String phase);

    void onPhaseComplete(String phase, boolean success, String message);

    void onMetadataUpdate(BuildOperationMetadata metadata);

    void onBuildComplete(BuildOperationMetadata metadata);

    void onBuildFailed(String error);
}
