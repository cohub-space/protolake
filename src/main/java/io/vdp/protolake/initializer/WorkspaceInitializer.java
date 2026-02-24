package io.vdp.protolake.initializer;

import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.template.TemplateEngine;
import protolake.v1.Lake;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.LakeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Initializes Bazel workspace for a Proto Lake.
 *
 * This includes generating MODULE.bazel, BUILD.bazel, .bazelrc,
 * and all necessary tools for building and publishing proto bundles.
 */
@ApplicationScoped
public class WorkspaceInitializer {
    private static final Logger LOG = Logger.getLogger(WorkspaceInitializer.class);

    @Inject
    TemplateEngine templateEngine;
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    /**
     * Initializes the Bazel workspace for a lake.
     */
    public void initializeWorkspace(Lake lake) throws IOException {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Initializing Bazel workspace for lake: %s", lakeName);

        // Create Bazel configuration files
        createModuleBazel(lake);
        createRootBuildBazel(lake);
        createBazelrc(lake);

        // Create tools directory structure
        createToolsDirectory(lake);

        // Create proto bundle rules
        createProtoBundleRules(lake);

        // Create Connect-ES codegen rule
        createEsProtoRules(lake);

        // Create npm workspace (package.json + pnpm-lock.yaml for protoc-gen-es)
        createNpmWorkspace(lake);

        // Create Buf configuration
        createBufConfiguration(lake);

        // Regenerate protolakew wrapper script (keeps it in sync with template changes)
        generateProtolakew(lake);

        LOG.infof("Bazel workspace initialized for lake: %s", lakeName);
    }

    /**
     * Creates MODULE.bazel with all necessary dependencies.
     * This is a template file that needs variable substitution.
     */
    private void createModuleBazel(Lake lake) throws IOException {
        Map<String, Object> context = buildTemplateContext(lake);
        
        // Configure protolake-gazelle source
        // Check for git repository configuration first
        String gitUrl = System.getenv("PROTOLAKE_GAZELLE_GIT_URL");
        String gitCommit = System.getenv("PROTOLAKE_GAZELLE_GIT_COMMIT");
        
        if (gitUrl != null && !gitUrl.isEmpty() && gitCommit != null && !gitCommit.isEmpty()) {
            // Use git repository (explicit env vars)
            context.put("protolake_gazelle_git_url", gitUrl);
            context.put("protolake_gazelle_git_commit", gitCommit);
            context.put("protolake_gazelle_path", "");
            LOG.debugf("Using git repository for protolake-gazelle: %s@%s", gitUrl, gitCommit);
        } else {
            // Check for explicit local path override
            String protolakeGazellePath = System.getenv("PROTOLAKE_GAZELLE_SOURCE_PATH");
            if (protolakeGazellePath != null && !protolakeGazellePath.isEmpty()) {
                // Running inside Docker or with explicit path
                context.put("protolake_gazelle_path", protolakeGazellePath);
                context.put("protolake_gazelle_git_url", "");
                context.put("protolake_gazelle_git_commit", "");
                LOG.debugf("Using local path for protolake-gazelle: %s", protolakeGazellePath);
            } else {
                // Default: fetch from public GitHub repository
                context.put("protolake_gazelle_git_url",
                        "https://github.com/cohub-space/protolake-gazelle.git");
                context.put("protolake_gazelle_git_commit",
                        "a1e97eb1a8a1a0e36bab285ac4d7c0f8ba47f9d1");
                context.put("protolake_gazelle_path", "");
                LOG.debugf("Using default GitHub remote for protolake-gazelle");
            }
        }
        
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path modulePath = lakePath.resolve("MODULE.bazel");
        templateEngine.renderToFile("bazel/MODULE.bazel.tmpl", context, modulePath);
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created MODULE.bazel for lake: %s", lakeName);
    }

    /**
     * Creates root BUILD.bazel with gazelle configuration.
     * This is a template file that needs variable substitution.
     */
    private void createRootBuildBazel(Lake lake) throws IOException {
        Map<String, Object> context = buildTemplateContext(lake);
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path buildPath = lakePath.resolve("BUILD.bazel");
        // TODO - does the root BUILD.bazel need to be a template? why not just a static file?
        templateEngine.renderToFile("bazel/BUILD.root", context, buildPath);
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created root BUILD.bazel for lake: %s", lakeName);
    }

    /**
     * Creates .bazelrc with build configuration.
     * This is a static file with no template variables.
     * Only created on first init — user may customize after creation.
     */
    private void createBazelrc(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path bazelrcPath = lakePath.resolve(".bazelrc");
        copyIfNotExists("bazel/bazelrc", bazelrcPath);
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created .bazelrc for lake: %s", lakeName);
    }

    /**
     * Creates tools directory with build and publishing scripts.
     */
    private void createToolsDirectory(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path toolsPath = lakePath.resolve("tools");
        Files.createDirectories(toolsPath);

        // Create tools BUILD.bazel - static file
        templateEngine.copyResource("bazel/BUILD.tools",
                toolsPath.resolve("BUILD.bazel"));

        // Create bundler tools
        createBundlerTools(toolsPath);

        // Create publishing tools
        createPublishingTools(toolsPath);

        // Create utility scripts
        createUtilityScripts(toolsPath);

        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created tools directory for lake: %s", lakeName);
    }

    /**
     * Creates proto bundle rule definitions.
     * This is a static Bazel rule file with no template variables.
     */
    private void createProtoBundleRules(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path toolsPath = lakePath.resolve("tools");
        templateEngine.copyResource("tools/proto_bundle.bzl",
                toolsPath.resolve("proto_bundle.bzl"));
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created proto bundle rules for lake: %s", lakeName);
    }

    /**
     * Creates Connect-ES proto compilation rule (es_proto.bzl).
     * This is a static Bazel rule file with no template variables.
     */
    private void createEsProtoRules(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path toolsPath = lakePath.resolve("tools");
        templateEngine.copyResource("tools/es_proto.bzl",
                toolsPath.resolve("es_proto.bzl"));
        // Copy the wrapper script that delegates to globally-installed protoc-gen-es
        templateEngine.copyResource("tools/protoc-gen-es-wrapper.sh",
                toolsPath.resolve("protoc-gen-es-wrapper.sh"));
        toolsPath.resolve("protoc-gen-es-wrapper.sh").toFile().setExecutable(true);
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created es_proto.bzl rules for lake: %s", lakeName);
    }

    /**
     * Creates npm workspace files (package.json + pnpm-lock.yaml) for protoc-gen-es.
     * These are needed by npm_translate_lock in MODULE.bazel.
     */
    private void createNpmWorkspace(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);

        // Determine protoc-gen-es version from lake config, fallback to default
        String protocGenEsVersion = "2.11.0";
        if (lake.getConfig() != null && lake.getConfig().hasLanguageDefaults()
                && lake.getConfig().getLanguageDefaults().hasJavascript()) {
            String configVersion = lake.getConfig().getLanguageDefaults().getJavascript().getProtocGenEsVersion();
            if (configVersion != null && !configVersion.isEmpty()) {
                protocGenEsVersion = configVersion;
            }
        }

        // Create package.json
        // pnpm.onlyBuiltDependencies is required by aspect_rules_js npm_translate_lock with pnpm v9+
        String packageJson = String.format("""
                {
                  "private": true,
                  "devDependencies": {
                    "@bufbuild/protoc-gen-es": "%s"
                  },
                  "pnpm": {
                    "onlyBuiltDependencies": []
                  }
                }
                """, protocGenEsVersion);
        Files.writeString(lakePath.resolve("package.json"), packageJson);

        // Generate pnpm-lock.yaml via pnpm install
        try {
            ProcessBuilder pb = new ProcessBuilder("pnpm", "install", "--lockfile-only")
                    .directory(lakePath.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOG.warnf("pnpm install --lockfile-only failed (exit %d): %s. " +
                        "Falling back to empty pnpm-lock.yaml", exitCode, output);
                // Write a minimal pnpm-lock.yaml so Bazel doesn't fail
                Files.writeString(lakePath.resolve("pnpm-lock.yaml"),
                        "lockfileVersion: '9.0'\n");
            } else {
                LOG.debugf("Generated pnpm-lock.yaml for lake: %s", LakeUtil.extractLakeId(lake.getName()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running pnpm install", e);
        } catch (Exception e) {
            LOG.warnf("Failed to run pnpm: %s. Writing minimal pnpm-lock.yaml", e.getMessage());
            Files.writeString(lakePath.resolve("pnpm-lock.yaml"),
                    "lockfileVersion: '9.0'\n");
        }
    }

    /**
     * Creates Buf configuration files.
     * These are template files that need variable substitution.
     */
    private void createBufConfiguration(Lake lake) throws IOException {
        Map<String, Object> context = buildTemplateContext(lake);

        // Add Buf-specific configuration with defaults
        List<String> bufBreakingUse = Arrays.asList("FILE");
        List<String> bufLintUse = Arrays.asList("STANDARD");
        List<String> bufLintExcept = Arrays.asList("PACKAGE_VERSION_SUFFIX");

        // Override with lake config if specified
        if (lake.getConfig().hasValidation()) {
            // Parse from config if needed in future
        }

        context.put("buf_breaking_use", bufBreakingUse);
        context.put("buf_lint_use", bufLintUse);
        context.put("buf_lint_except", bufLintExcept);

        // buf.yaml - only created on first init, user may customize after creation
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        writeIfNotExists("buf/buf.yaml.tmpl", context, lakePath.resolve("buf.yaml"));

        // buf.gen.yaml - only created on first init, user may customize after creation
        writeIfNotExists("buf/buf.gen.yaml.tmpl", context, lakePath.resolve("buf.gen.yaml"));

        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.debugf("Created Buf configuration for lake: %s", lakeName);
    }

    /**
     * Creates bundler tools (jar_bundler, wheel_builder, npm_bundler).
     * These are static Python scripts with no template variables.
     */
    private void createBundlerTools(Path toolsPath) throws IOException {
        Path bundlerPath = toolsPath.resolve("bundler");
        Files.createDirectories(bundlerPath);

        // Copy bundler scripts as static resources
        copyAndMakeExecutable("tools/bundler/jar_bundler.py",
                bundlerPath.resolve("jar_bundler.py"));
        copyAndMakeExecutable("tools/bundler/wheel_builder.py",
                bundlerPath.resolve("wheel_builder.py"));
        copyAndMakeExecutable("tools/bundler/npm_bundler.py",
                bundlerPath.resolve("npm_bundler.py"));
    }

    /**
     * Creates publishing tools (maven_publisher, pypi_publisher, npm_publisher).
     * These are static Python scripts with no template variables.
     */
    private void createPublishingTools(Path toolsPath) throws IOException {
        Path publishPath = toolsPath.resolve("publish");
        Files.createDirectories(publishPath);

        // Copy publisher scripts as static resources
        copyAndMakeExecutable("tools/publish/maven_publisher.py",
                publishPath.resolve("maven_publisher.py"));
        copyAndMakeExecutable("tools/publish/pypi_publisher.py",
                publishPath.resolve("pypi_publisher.py"));
        copyAndMakeExecutable("tools/publish/npm_publisher.py",
                publishPath.resolve("npm_publisher.py"));
        copyAndMakeExecutable("tools/publish/proto_loader_publisher.py",
                publishPath.resolve("proto_loader_publisher.py"));

        // Copy utility module (not executable)
        templateEngine.copyResource("tools/publish/publisher_utils.py",
                publishPath.resolve("publisher_utils.py"));
    }

    /**
     * Creates utility scripts (gazelle wrapper).
     * These are static scripts with no template variables.
     */
    private void createUtilityScripts(Path toolsPath) throws IOException {
        copyAndMakeExecutable("tools/gazelle_wrapper.py",
                toolsPath.resolve("gazelle_wrapper.py"));
    }

    /**
     * Builds template context from lake configuration.
     */
    private Map<String, Object> buildTemplateContext(Lake lake) {
        Map<String, Object> context = new HashMap<>();
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        context.put("lakeName", lakeName);

        LakeConfig config = lake.getConfig();
        if (config != null) {
            if (config.hasModuleBazel()) {
                context.put("protobuf_version", config.getModuleBazel().getProtobufVersion());
                context.put("grpc_version", config.getModuleBazel().getGrpcVersion());
                context.put("rules_proto_grpc_version", config.getModuleBazel().getRulesProtoGrpcVersion());
                context.put("grpc_java_version", config.getModuleBazel().getGrpcVersion());
            }
        }

        // Add defaults
        context.putIfAbsent("protobuf_version", "33.5");
        context.putIfAbsent("grpc_version", "1.78.0");
        context.putIfAbsent("rules_proto_grpc_version", "5.8.0");

        // Add additional dependencies needed by MODULE.bazel.tmpl
        context.putIfAbsent("protobuf_java_version", "4.33.5");
        context.putIfAbsent("grpc_java_version", "1.78.0");
        context.putIfAbsent("rules_java_version", "9.5.0");
        context.putIfAbsent("rules_python_version", "1.8.4");

        return context;
    }

    /**
     * Generates the protolakew wrapper script in the lake root.
     * Uses simple string replacement instead of Qute to avoid conflicts
     * with bash brace syntax.
     */
    private void generateProtolakew(Lake lake) throws IOException {
        String lakeId = LakeUtil.extractLakeId(lake.getName());
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);

        try (var is = getClass().getResourceAsStream("/protolakew.sh.tmpl")) {
            if (is == null) {
                throw new IOException("protolakew.sh.tmpl template not found on classpath");
            }
            String template = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String rendered = template
                    .replace("{lakeName}", lakeId)
                    .replace("{lakePrefix}", lake.getLakePrefix() != null ? lake.getLakePrefix() : "");

            Path protolakewPath = lakePath.resolve("protolakew");
            Files.writeString(protolakewPath, rendered, java.nio.charset.StandardCharsets.UTF_8);
            makeExecutable(protolakewPath);
            LOG.infof("Generated protolakew wrapper at %s", protolakewPath);
        }
    }

    /**
     * Renders a template to the target path only if the file does not already exist.
     * Used for user-configurable files that should not be overwritten on subsequent builds.
     */
    private boolean writeIfNotExists(String templateName, Map<String, Object> context, Path target) throws IOException {
        if (Files.exists(target)) {
            LOG.debugf("Skipping %s (already exists, user-configurable)", target);
            return false;
        }
        templateEngine.renderToFile(templateName, context, target);
        return true;
    }

    /**
     * Copies a resource to the target path only if the file does not already exist.
     * Used for user-configurable files that should not be overwritten on subsequent builds.
     */
    private boolean copyIfNotExists(String resourcePath, Path target) throws IOException {
        if (Files.exists(target)) {
            LOG.debugf("Skipping %s (already exists, user-configurable)", target);
            return false;
        }
        templateEngine.copyResource(resourcePath, target);
        return true;
    }

    /**
     * Copies a resource file and makes it executable.
     * Used for scripts that need execute permissions.
     */
    private void copyAndMakeExecutable(String resourcePath, Path targetPath) throws IOException {
        templateEngine.copyResource(resourcePath, targetPath);
        makeExecutable(targetPath);
    }

    /**
     * Makes a file executable.
     */
    private void makeExecutable(Path targetPath) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(targetPath);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(targetPath, perms);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system, ignore
            LOG.debugf("Cannot set executable permission on non-POSIX file system for: %s", targetPath);
        }
    }
}