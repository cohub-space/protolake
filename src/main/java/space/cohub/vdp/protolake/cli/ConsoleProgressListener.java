package space.cohub.vdp.protolake.cli;

import protolake.v1.BuildOperationMetadata;
import protolake.v1.TargetBuildInfo;

/**
 * Console-based progress listener that prints build progress to stdout.
 */
public class ConsoleProgressListener implements BuildProgressListener {

    @Override
    public void onPhaseStart(String phase) {
        ConsoleLine.print(System.out, "[protolake] %s...", phase);
    }

    @Override
    public void onPhaseComplete(String phase, boolean success, String message) {
        if (success && message != null && !message.isEmpty()) {
            ConsoleLine.print(System.out, "[protolake] %s: OK - %s", phase, message);
        } else if (success) {
            ConsoleLine.print(System.out, "[protolake] %s: OK", phase);
        } else {
            ConsoleLine.print(System.out, "[protolake] %s: FAILED - %s", phase, message);
        }
    }

    @Override
    public void onWarning(String warning) {
        ConsoleLine.print(System.out, "[protolake] WARNING: %s", warning);
    }

    @Override
    public void onMetadataUpdate(BuildOperationMetadata metadata) {
        // Print target build status updates
        for (var entry : metadata.getTargetBuildsMap().entrySet()) {
            TargetBuildInfo info = entry.getValue();
            if (info.getStatus() == TargetBuildInfo.Status.BUILT ||
                    info.getStatus() == TargetBuildInfo.Status.PUBLISHED) {
                ConsoleLine.print(System.out, "[protolake]   %s: %s", entry.getKey(), info.getStatus());
            } else if (info.getStatus() == TargetBuildInfo.Status.FAILED) {
                ConsoleLine.print(System.out, "[protolake]   %s: FAILED - %s", entry.getKey(), info.getErrorMessage());
            }
        }
    }

    @Override
    public void onBuildComplete(BuildOperationMetadata metadata) {
        System.out.println("[protolake] Build completed successfully.");
    }

    @Override
    public void onBuildFailed(String error) {
        ConsoleLine.print(System.err, "[protolake] Build failed: %s", error);
    }
}
