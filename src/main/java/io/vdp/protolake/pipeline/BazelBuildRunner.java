package io.vdp.protolake.pipeline;

import io.vdp.protolake.util.bazel.BazelCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Executes Bazel build commands.
 *
 * Handles incremental builds and caching through Bazel's build system.
 *
 * IMPORTANT: Architecture Notes
 * - Bazel is always run from the lake root directory (where MODULE.bazel exists)
 * - Each lake has its own Bazel workspace with isolated cache (in bazel-* directories)
 * - All target paths passed to this class should be relative to the lake root
 * - Example: "." means build everything in the lake
 * - Example: "company_a/platform" means build only that subdirectory
 *
 * Path Coordinate Systems:
 * - Service layer uses base-relative paths (e.g., "z/y/lake/bundle")
 * - Build layer uses lake-relative paths (e.g., "bundle" or "company/bundle")
 * - BuildOrchestrator handles the conversion between these coordinate systems
 * - This class expects all paths to already be lake-relative
 */
@ApplicationScoped
public class BazelBuildRunner {
    private static final Logger LOG = Logger.getLogger(BazelBuildRunner.class);

    @Inject
    BazelCommand bazelCommand;

    @ConfigProperty(name = "protolake.build.bazel-options", defaultValue = "--jobs=4")
    String defaultBazelOptions;

    // Allowed values for PROTOLAKE_BAZEL_CONFIG env var.
    // Each value maps to a --config=<name> flag in Bazel, which selects
    // a named config profile from .bazelrc / .bazelrc.remote-cache.
    private static final Set<String> ALLOWED_BAZEL_CONFIGS = Set.of("ci", "remote", "local");

    /**
     * Cleans bazel cache for a lake.
     */
    public void clean(Path lakeRoot) throws IOException {
        LOG.infof("Cleaning bazel cache at: %s", lakeRoot);

        try {
            bazelCommand.run(lakeRoot, "clean", "--expunge");
            LOG.infof("Bazel cache cleaned");
        } catch (Exception e) {
            throw new IOException("Failed to clean bazel cache: " + e.getMessage(), e);
        }
    }

    /**
     * Queries bazel for information about targets.
     */
    public List<String> query(Path lakeRoot, String query) throws IOException {
        LOG.debugf("Running bazel query: %s", query);

        try {
            String output = bazelCommand.runWithOutput(lakeRoot, "query", query);
            List<String> results = new ArrayList<>();

            for (String line : output.split("\n")) {
                if (!line.trim().isEmpty()) {
                    results.add(line.trim());
                }
            }

            return results;
        } catch (Exception e) {
            throw new IOException("Bazel query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a target within a lake's Bazel workspace.
     *
     * @param lakeRoot The lake root directory (where MODULE.bazel exists)
     * @param target The target path relative to lake root (e.g., "." for entire lake, "company_a/platform" for subdirectory)
     * @param metadata The operation metadata to update
     * @return Updated metadata with build results
     */
    public BuildOperationMetadata buildTarget(Path lakeRoot, String target, BuildOperationMetadata metadata) throws IOException {
        // Initialize build phase status
        PhaseStatus.Builder buildStatus = PhaseStatus.newBuilder()
            .setStatus(PhaseStatus.Status.RUNNING)
            .setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());

        List<String> logs = new ArrayList<>();

        try {
            // Convert path to Bazel target
            String bazelTarget = toBazelTarget(target);

            // Extract dependency versions from lake config for POM generation
            Map<String, String> actionEnvs = extractVersionActionEnvs(metadata);

            LOG.infof("Building target: %s (bazel: %s)", target, bazelTarget);
            buildStatus.setSubPhase(String.format("Building %s", bazelTarget));

            // Create target entry in metadata
            Map<String, TargetBuildInfo> targetBuilds = new HashMap<>(metadata.getTargetBuildsMap());
            TargetBuildInfo targetInfo = TargetBuildInfo.newBuilder()
                .setTarget(bazelTarget)
                .setVersion(metadata.getBranch())
                .setStatus(TargetBuildInfo.Status.BUILDING)
                .setStartTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .build();
            targetBuilds.put(bazelTarget, targetInfo);

            metadata = metadata.toBuilder()
                .putAllTargetBuilds(targetBuilds)
                .build();

            try {
                String targetLog = runBazelBuild(lakeRoot, bazelTarget, actionEnvs);

                targetInfo = targetInfo.toBuilder()
                    .setStatus(TargetBuildInfo.Status.BUILT)
                    .setEndTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                    .addBuildLogs(targetLog)
                    .build();
                targetBuilds.put(bazelTarget, targetInfo);

                logs.add(String.format("Successfully built: %s", bazelTarget));

            } catch (IOException e) {
                targetInfo = targetInfo.toBuilder()
                    .setStatus(TargetBuildInfo.Status.FAILED)
                    .setEndTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                    .setErrorMessage(e.getMessage())
                    .build();
                targetBuilds.put(bazelTarget, targetInfo);
                throw e;
            }

            // Mark build phase as successful
            buildStatus
                .setStatus(PhaseStatus.Status.SUCCEEDED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .addAllLogLines(logs);

            return metadata.toBuilder()
                .putAllTargetBuilds(targetBuilds)
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setBuild(buildStatus.build())
                    .build())
                .build();

        } catch (Exception e) {
            // Mark build as failed
            buildStatus
                .setStatus(PhaseStatus.Status.FAILED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .setErrorMessage("Build failed: " + e.getMessage())
                .addAllLogLines(logs);

            metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setBuild(buildStatus.build())
                    .build())
                .build();

            throw new IOException("Build failed", e);
        }
    }

    /**
     * Publishes built artifacts locally by running all publish_* targets.
     *
     * Queries Bazel for genrule targets matching "publish_*", then runs each
     * via {@code bazel run} to install artifacts to local package managers.
     *
     * @param lakeRoot The lake root directory (where MODULE.bazel exists)
     * @param metadata The operation metadata to update
     * @return Updated metadata with publish results
     */
    public BuildOperationMetadata publishLocal(Path lakeRoot, BuildOperationMetadata metadata,
                                               InstallLocalConfig installLocalConfig) throws IOException {
        PhaseStatus.Builder publishStatus = PhaseStatus.newBuilder()
            .setStatus(PhaseStatus.Status.RUNNING)
            .setStartTime(Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build());

        List<String> logs = new ArrayList<>();

        try {
            List<String> publishTargets = queryPublishTargets(lakeRoot);
            LOG.infof("Found %d publish targets", publishTargets.size());

            // Build env map for publish genrules
            Map<String, String> publishEnv = new HashMap<>();

            // Pass dependency versions from lake config for POM generation
            Map<String, String> versionEnvs = extractVersionActionEnvs(metadata);
            if (versionEnvs != null) {
                publishEnv.putAll(versionEnvs);
            }

            if (installLocalConfig.getJsTargetsCount() > 0) {
                publishEnv.put("NPM_PUBLISH_MODE", "workspace");
                publishEnv.put("JS_TARGETS", String.join(",", installLocalConfig.getJsTargetsList()));
            }

            if (publishTargets.isEmpty()) {
                publishStatus.setStatus(PhaseStatus.Status.SKIPPED)
                    .setEndTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                    .setSubPhase("No publish targets found");
                logs.add("No publish targets found, skipping");

                return metadata.toBuilder()
                    .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                        .setPublish(publishStatus.build())
                        .build())
                    .build();
            }

            for (int i = 0; i < publishTargets.size(); i++) {
                String target = publishTargets.get(i);
                publishStatus.setSubPhase(String.format("Publishing %s (%d/%d)", target, i + 1, publishTargets.size()));

                LOG.infof("Running publish target: %s", target);
                try {
                    String output = publishEnv.isEmpty()
                        ? bazelCommand.runWithOutput(lakeRoot, "build", target)
                        : bazelCommand.runWithOutput(lakeRoot, publishEnv, "build", target);
                    logs.add(String.format("Published: %s", target));
                    LOG.infof("Successfully published: %s", target);
                } catch (Exception e) {
                    String errorMsg = String.format("Failed to publish %s: %s", target, e.getMessage());
                    logs.add(errorMsg);
                    LOG.warnf(e, "Publish target failed: %s", target);
                    // Continue with other targets even if one fails
                }
            }

            publishStatus
                .setStatus(PhaseStatus.Status.SUCCEEDED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .addAllLogLines(logs);

            return metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setPublish(publishStatus.build())
                    .build())
                .build();

        } catch (Exception e) {
            publishStatus
                .setStatus(PhaseStatus.Status.FAILED)
                .setEndTime(Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .setErrorMessage("Publishing failed: " + e.getMessage())
                .addAllLogLines(logs);

            return metadata.toBuilder()
                .setPhaseStatuses(metadata.getPhaseStatuses().toBuilder()
                    .setPublish(publishStatus.build())
                    .build())
                .build();
        }
    }

    private List<String> queryPublishTargets(Path lakeRoot) throws IOException {
        try {
            String output = bazelCommand.runWithOutput(lakeRoot, "query",
                "kind(\"genrule\", //...)", "--output=label");
            List<String> targets = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && trimmed.contains("publish_")) {
                    targets.add(trimmed);
                }
            }
            return targets;
        } catch (Exception e) {
            throw new IOException("Failed to query publish targets: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a lake-relative path to a Bazel target.
     */
    private String toBazelTarget(String targetPath) {
        if (".".equals(targetPath)) {
            return "//...";
        }
        return "//" + targetPath + "/...";
    }

    /**
     * Runs bazel build for a specific target and returns output logs.
     * Tolerates partial build success when --keep_going is used.
     */
    private String runBazelBuild(Path lakeRoot, String bazelTarget, Map<String, String> actionEnvs) throws IOException {
        List<String> args = buildBazelArgs("build", bazelTarget, lakeRoot, actionEnvs);

        BazelCommand.CommandResult result = bazelCommand.runWithResult(lakeRoot, args.toArray(new String[0]));

        if (result.isSuccess()) {
            LOG.infof("Successfully built target: %s", bazelTarget);
            return result.output;
        }

        // With --keep_going, Bazel returns non-zero even when most targets succeed.
        // Check if this is a partial success (some targets built).
        // Bazel uses different messages depending on the failure mode:
        // - "Build succeeded for" when some targets fail but others succeed
        // - "command succeeded, but there were errors parsing the target pattern" when
        //   all targets build but some packages couldn't be loaded (e.g. //... expansion)
        if (result.output.contains("Build succeeded for") ||
            result.output.contains("command succeeded")) {
            LOG.warnf("Partial build success for target %s (exit code %d). Some targets failed but build continued with --keep_going.",
                bazelTarget, result.exitCode);
            return result.output;
        }

        throw new IOException("Bazel build failed for target " + bazelTarget +
            " with exit code " + result.exitCode + "\n" + result.output);
    }

    /**
     * Extracts protobuf/gRPC versions from lake config and returns them as action_env entries.
     * These are forwarded to Bazel actions so publish genrules can embed correct POM versions.
     */
    private Map<String, String> extractVersionActionEnvs(BuildOperationMetadata metadata) {
        Map<String, String> envs = new HashMap<>();
        if (metadata.hasLake() && metadata.getLake().hasConfig()
                && metadata.getLake().getConfig().hasLanguageDefaults()
                && metadata.getLake().getConfig().getLanguageDefaults().hasJava()) {
            var javaDefaults = metadata.getLake().getConfig().getLanguageDefaults().getJava();
            String protobufJavaVersion = javaDefaults.getProtobufJavaVersion();
            String grpcVersion = javaDefaults.getGrpcJavaVersion();
            if (protobufJavaVersion != null && !protobufJavaVersion.isEmpty()) {
                envs.put("PROTOBUF_JAVA_VERSION", protobufJavaVersion);
            }
            if (grpcVersion != null && !grpcVersion.isEmpty()) {
                envs.put("GRPC_VERSION", grpcVersion);
            }
        }
        return envs.isEmpty() ? null : envs;
    }

    /**
     * Builds bazel command arguments.
     *
     * @param command  The bazel command (e.g., "build")
     * @param target   The target pattern (e.g., "//...")
     * @param lakeRoot The lake root directory
     * @param actionEnvs Optional action_env overrides (key=value pairs forwarded to Bazel actions)
     */
    private List<String> buildBazelArgs(String command, String target, Path lakeRoot,
                                        Map<String, String> actionEnvs) {
        List<String> args = new ArrayList<>();
        args.add(command);

        // Add default options first (before target patterns)
        if (defaultBazelOptions != null && !defaultBazelOptions.isEmpty()) {
            for (String option : defaultBazelOptions.split(" ")) {
                if (!option.trim().isEmpty()) {
                    args.add(option.trim());
                }
            }
        }

        // Add --config flag from PROTOLAKE_BAZEL_CONFIG env var (e.g., "ci")
        // This activates named config profiles in .bazelrc / .bazelrc.remote-cache
        String bazelConfig = System.getenv("PROTOLAKE_BAZEL_CONFIG");
        if (bazelConfig != null && !bazelConfig.isEmpty()) {
            String normalized = bazelConfig.toLowerCase(Locale.ROOT);
            if (ALLOWED_BAZEL_CONFIGS.contains(normalized)) {
                args.add("--config=" + normalized);
            } else {
                LOG.warnf("Ignoring unknown PROTOLAKE_BAZEL_CONFIG value '%s'. Allowed: %s",
                        bazelConfig, ALLOWED_BAZEL_CONFIGS);
            }
        }

        // Forward action_env overrides (e.g., PROTOBUF_JAVA_VERSION, GRPC_VERSION)
        if (actionEnvs != null) {
            for (var entry : actionEnvs.entrySet()) {
                args.add("--action_env=" + entry.getKey() + "=" + entry.getValue());
            }
        }

        args.add("--keep_going");
        args.add("--show_progress");
        args.add("--progress_report_interval=10");

        // End of options marker — target patterns (including negative) must come after
        args.add("--");
        args.add(target);

        // If building all targets, exclude .aspect_rules_js when it exists
        // (the exclusion pattern causes Bazel to error if the directory is absent)
        if ("//...".equals(target) && Files.isDirectory(lakeRoot.resolve(".aspect_rules_js"))) {
            args.add("-//.aspect_rules_js/...");
        }

        return args;
    }
}
