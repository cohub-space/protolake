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

        // Create Buf configuration
        createBufConfiguration(lake);

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
            // Use git repository
            context.put("protolake_gazelle_git_url", gitUrl);
            context.put("protolake_gazelle_git_commit", gitCommit);
            LOG.debugf("Using git repository for protolake-gazelle: %s@%s", gitUrl, gitCommit);
        } else {
            // Use local path
            String protolakeGazellePath = System.getenv("PROTOLAKE_GAZELLE_SOURCE_PATH");
            if (protolakeGazellePath != null && !protolakeGazellePath.isEmpty()) {
                // Running inside Docker or with explicit path
                context.put("protolake_gazelle_path", protolakeGazellePath);
            } else {
                // Default relative path for local development
                context.put("protolake_gazelle_path", "../../../protolake-gazelle");
            }
            LOG.debugf("Using local path for protolake-gazelle: %s", context.get("protolake_gazelle_path"));
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
     */
    private void createBazelrc(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path bazelrcPath = lakePath.resolve(".bazelrc");
        templateEngine.copyResource("bazel/bazelrc", bazelrcPath);
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
     * Creates Buf configuration files.
     * These are template files that need variable substitution.
     */
    private void createBufConfiguration(Lake lake) throws IOException {
        Map<String, Object> context = buildTemplateContext(lake);

        // Add Buf-specific configuration with defaults
        List<String> bufBreakingUse = Arrays.asList("FILE");
        List<String> bufLintUse = Arrays.asList("DEFAULT");
        List<String> bufLintExcept = Arrays.asList("PACKAGE_VERSION_SUFFIX");

        // Override with lake config if specified
        if (lake.getConfig().hasValidation()) {
            // Parse from config if needed in future
        }

        context.put("buf_breaking_use", bufBreakingUse);
        context.put("buf_lint_use", bufLintUse);
        context.put("buf_lint_except", bufLintExcept);

        // buf.yaml - template file
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        templateEngine.renderToFile("buf/buf.yaml.tmpl", context,
                lakePath.resolve("buf.yaml"));

        // buf.gen.yaml - template file
        templateEngine.renderToFile("buf/buf.gen.yaml.tmpl", context,
                lakePath.resolve("buf.gen.yaml"));

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

        // Copy utility module (not executable)
        templateEngine.copyResource("tools/publish/publisher_utils.py",
                publishPath.resolve("publisher_utils.py"));
    }

    /**
     * Creates utility scripts (gazelle wrapper, import fixer).
     * These are static scripts with no template variables.
     */
    private void createUtilityScripts(Path toolsPath) throws IOException {
        copyAndMakeExecutable("tools/gazelle_wrapper.py",
                toolsPath.resolve("gazelle_wrapper.py"));
        copyAndMakeExecutable("tools/fix_proto_imports.py",
                toolsPath.resolve("fix_proto_imports.py"));
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
            context.put("organization", config.getOrganization());

            if (config.hasModuleBazel()) {
                // Use the template variable names with underscores
                context.put("protobuf_version", config.getModuleBazel().getProtobufVersion());
                context.put("grpc_version", config.getModuleBazel().getGrpcVersion());
                context.put("rules_proto_grpc_version", config.getModuleBazel().getRulesProtoGrpcVersion());
                
                // Also set the Java-specific versions from the module bazel config
                context.put("grpc_java_version", config.getModuleBazel().getGrpcVersion());
                // Note: protobuf_java_version would need to be derived or set separately
            }
        }

        // Add defaults
        context.putIfAbsent("organization", "com.example");
        context.putIfAbsent("protobuf_version", "31.1");
        context.putIfAbsent("grpc_version", "1.64.0");
        context.putIfAbsent("rules_proto_grpc_version", "5.3.1");

        // Add additional dependencies needed by MODULE.bazel.tmpl
        context.putIfAbsent("protobuf_java_version", "4.28.3");
        context.putIfAbsent("grpc_java_version", "1.68.1");
        context.putIfAbsent("rules_java_version", "8.13.0");
        context.putIfAbsent("rules_python_version", "1.5.1");

        return context;
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