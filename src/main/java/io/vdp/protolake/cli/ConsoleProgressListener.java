package io.vdp.protolake.cli;

import protolake.v1.BuildOperationMetadata;
import protolake.v1.TargetBuildInfo;

/**
 * Console-based progress listener that prints build progress to stdout.
 */
public class ConsoleProgressListener implements BuildProgressListener {

    @Override
    public void onPhaseStart(String phase) {
        System.out.printf("[protolake] %s...%n", phase);
    }

    @Override
    public void onPhaseComplete(String phase, boolean success, String message) {
        if (success) {
            System.out.printf("[protolake] %s: OK%n", phase);
        } else {
            System.out.printf("[protolake] %s: FAILED - %s%n", phase, message);
        }
    }

    @Override
    public void onMetadataUpdate(BuildOperationMetadata metadata) {
        // Print target build status updates
        for (var entry : metadata.getTargetBuildsMap().entrySet()) {
            TargetBuildInfo info = entry.getValue();
            if (info.getStatus() == TargetBuildInfo.Status.BUILT ||
                    info.getStatus() == TargetBuildInfo.Status.PUBLISHED) {
                System.out.printf("[protolake]   %s: %s%n", entry.getKey(), info.getStatus());
            } else if (info.getStatus() == TargetBuildInfo.Status.FAILED) {
                System.out.printf("[protolake]   %s: FAILED - %s%n", entry.getKey(), info.getErrorMessage());
            }
        }
    }

    @Override
    public void onBuildComplete(BuildOperationMetadata metadata) {
        System.out.println("[protolake] Build completed successfully.");
    }

    @Override
    public void onBuildFailed(String error) {
        System.err.printf("[protolake] Build failed: %s%n", error);
    }
}
