package io.vdp.protolake.initializer;

import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.git.GitCommand;
import io.vdp.protolake.util.template.TemplateEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the initialization of new ProtoLakes.
 *
 * This includes creating the directory structure, initializing git,
 * setting up the Bazel workspace, and creating initial configuration files.
 */
@ApplicationScoped
public class LakeInitializer {
    private static final Logger LOG = Logger.getLogger(LakeInitializer.class);

    @ConfigProperty(name = "protolake.storage.base-path", defaultValue = "/var/protolake/lakes")
    String basePath;

    @ConfigProperty(name = "protolake.git.user.name", defaultValue = "ProtoLake")
    String gitUserName;

    @ConfigProperty(name = "protolake.git.user.email", defaultValue = "protolake@localhost")
    String gitUserEmail;

    @Inject
    GitCommand gitCommand;

    @Inject
    TemplateEngine templateEngine;

    @Inject
    WorkspaceInitializer workspaceInitializer;

    public String getBasePath() {
        return basePath;
    }

    /**
     * Initializes a new lake with all necessary setup.
     * Returns a Lake proto message with lake_prefix set.
     */
    public Lake initializeLake(String name, String displayName, String description,
                             LakeConfig config, String lakePrefix)
            throws IOException {
        LOG.infof("Initializing lake: %s", name);
        
        // Validate lake name for proto compatibility
        validateLakeName(name);

        // Build lake proto first to calculate the path
        Lake.Builder lakeBuilder = Lake.newBuilder()
            .setName(LakeUtil.toResourceName(name))
            .setDisplayName(displayName != null ? displayName : name)
            .setDescription(description != null ? description : "")
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()));
        
        // Set lake prefix if provided
        if (lakePrefix != null && !lakePrefix.isEmpty()) {
            lakeBuilder.setLakePrefix(lakePrefix);
        }
        
        // Set config (without local_path)
        if (config != null) {
            lakeBuilder.setConfig(config);
        } else {
            lakeBuilder.setConfig(LakeConfig.getDefaultInstance());
        }
        
        Lake lake = lakeBuilder.build();

        // Calculate lake path using the util method
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        
        if (Files.exists(lakePath.resolve("lake.yaml"))) {
            throw new IllegalStateException("Lake already initialized at: " + lakePath);
        }
        Files.createDirectories(lakePath);

        try {
            // Initialize git repository
            gitCommand.init(lakePath);
            gitCommand.config(lakePath, "user.name", gitUserName);
            gitCommand.config(lakePath, "user.email", gitUserEmail);

            // Generate lake.yaml configuration
            generateLakeYaml(lake);

            // Initialize Bazel workspace (also generates protolakew)
            workspaceInitializer.initializeWorkspace(lake);

            // Create initial directory structure
            createDirectoryStructure(lakePath);

            // Initial git commit
            gitCommand.addAll(lakePath);
            gitCommand.commit(lakePath, "Initialize ProtoLake: " + name);

            LOG.infof("Successfully initialized lake: %s at %s", name, lakePath);
            return lake;

        } catch (Exception e) {
            // Cleanup on failure
            LOG.errorf(e, "Failed to initialize lake: %s. Cleaning up.", name);
            try {
                deleteDirectory(lakePath);
            } catch (Exception ex) {
                LOG.errorf(ex, "Failed to cleanup after initialization failure");
            }
            throw new IOException("Failed to initialize lake: " + e.getMessage(), e);
        }
    }

    /**
     * Generates the lake.yaml configuration file.
     */
    private void generateLakeYaml(Lake lake) throws IOException {
        Map<String, Object> context = new HashMap<>();
        String lakeId = LakeUtil.extractLakeId(lake.getName());
        
        // Basic lake fields
        context.put("lakeId", lakeId);
        context.put("displayName", lake.getDisplayName());
        context.put("description", lake.getDescription());
        context.put("lakePrefix", lake.getLakePrefix());
        
        LakeConfig config = lake.getConfig();
        
        // MODULE.bazel configuration with defaults
        if (config.hasModuleBazel()) {
            context.put("protobufVersion", config.getModuleBazel().getProtobufVersion());
            context.put("grpcVersion", config.getModuleBazel().getGrpcVersion());
            context.put("rulesProtoGrpcVersion", config.getModuleBazel().getRulesProtoGrpcVersion());
        } else {
            // Defaults that match design doc
            context.put("protobufVersion", "31.1");
            context.put("grpcVersion", "1.64.0");
            context.put("rulesProtoGrpcVersion", "5.3.1");
        }
        
        // Language defaults
        if (config.hasLanguageDefaults()) {
            // Java defaults
            if (config.getLanguageDefaults().hasJava()) {
                var java = config.getLanguageDefaults().getJava();
                context.put("javaEnabled", java.getEnabled());
                context.put("javaGroupId", java.getGroupId());
                context.put("javaSourceVersion", java.getSourceVersion());
                context.put("javaTargetVersion", java.getTargetVersion());
                context.put("protobufJavaVersion", java.getProtobufJavaVersion());
                context.put("grpcJavaVersion", java.getGrpcJavaVersion());
                context.put("javaMultipleFiles", java.getJavaMultipleFiles());
                context.put("javaOuterClassnameSuffix", java.getJavaOuterClassnameSuffix());
            } else {
                setDefaultJavaConfig(context);
            }

            // Python defaults
            if (config.getLanguageDefaults().hasPython()) {
                var python = config.getLanguageDefaults().getPython();
                context.put("pythonEnabled", python.getEnabled());
                context.put("pythonPackagePrefix", python.getPackagePrefix());
                context.put("pythonVersion", python.getPythonVersion());
                context.put("pythonProtobufVersion", python.getProtobufVersion());
                context.put("pythonGrpcioVersion", python.getGrpcioVersion());
                context.put("pythonStubType", python.getStubType());
            } else {
                setDefaultPythonConfig(context);
            }

            // JavaScript defaults
            if (config.getLanguageDefaults().hasJavascript()) {
                var js = config.getLanguageDefaults().getJavascript();
                context.put("javascriptEnabled", js.getEnabled());
                context.put("javascriptPackageScope", js.getPackageScope());
                context.put("javascriptNodeVersion", js.getNodeVersion());
                context.put("javascriptProtobufVersion", js.getGoogleProtobufVersion());
                context.put("javascriptGrpcWebVersion", js.getGrpcWebVersion());
                context.put("javascriptUseTypescript", js.getUseTypescript());
                context.put("javascriptModuleType", js.getModuleType());
            } else {
                setDefaultJavaScriptConfig(context);
            }

            // Go defaults
            if (config.getLanguageDefaults().hasGo()) {
                var go = config.getLanguageDefaults().getGo();
                context.put("goEnabled", go.getEnabled());
                context.put("goModulePrefix", go.getModulePrefix());
                context.put("goVersion", go.getGoVersion());
            } else {
                setDefaultGoConfig(context);
            }
        } else {
            // Set all language defaults
            setDefaultJavaConfig(context);
            setDefaultPythonConfig(context);
            setDefaultJavaScriptConfig(context);
            setDefaultGoConfig(context);
        }

        // Validation config
        if (config.hasValidation()) {
            context.put("bufConfigPath", config.getValidation().getBufConfigPath());
        } else {
            context.put("bufConfigPath", "./buf.yaml");
        }

        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        Path configPath = lakePath.resolve("lake.yaml");
        templateEngine.renderToFile("lake.yaml.tmpl", context, configPath);
    }
    
    private void setDefaultJavaConfig(Map<String, Object> context) {
        context.put("javaEnabled", true);
        context.put("javaGroupId", "com.example.proto");
        context.put("javaSourceVersion", "21");
        context.put("javaTargetVersion", "21");
        context.put("protobufJavaVersion", "4.31.1");
        context.put("grpcJavaVersion", "1.78.0");
        context.put("javaMultipleFiles", true);
        context.put("javaOuterClassnameSuffix", "Proto");
    }

    private void setDefaultPythonConfig(Map<String, Object> context) {
        context.put("pythonEnabled", true);
        context.put("pythonPackagePrefix", "example_proto");
        context.put("pythonVersion", ">=3.8,<4.0");
        context.put("pythonProtobufVersion", "5.27.0");
        context.put("pythonGrpcioVersion", "1.78.0");
        context.put("pythonStubType", "pyi");
    }

    private void setDefaultJavaScriptConfig(Map<String, Object> context) {
        context.put("javascriptEnabled", true);
        context.put("javascriptPackageScope", "@example");
        context.put("javascriptNodeVersion", ">=18");
        context.put("javascriptProtobufVersion", "3.21.2");
        context.put("javascriptGrpcWebVersion", "1.5.0");
        context.put("javascriptUseTypescript", true);
        context.put("javascriptModuleType", "commonjs");
    }

    private void setDefaultGoConfig(Map<String, Object> context) {
        context.put("goEnabled", false);
        context.put("goModulePrefix", "github.com/example/proto");
        context.put("goVersion", "1.25");
    }

    /**
     * Creates the initial directory structure for a lake.
     */
    private void createDirectoryStructure(Path lakePath) throws IOException {
        // Create bundles directory
        Path bundlesPath = lakePath.resolve("bundles");
        Files.createDirectories(bundlesPath);

        // Create common directory for shared protos
        Path commonPath = lakePath.resolve("common");
        Files.createDirectories(commonPath);

        // Create README using template
        templateEngine.renderToFile("lake-readme.md.tmpl", new HashMap<>(),
                lakePath.resolve("README.md"));
    }

    /**
     * Recursively deletes a directory.
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOG.warnf("Failed to delete: %s", p);
                        }
                    });
        }
    }
    
    /**
     * Deletes the entire lake directory including all files.
     */
    public void deleteLakeDirectory(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        if (Files.exists(lakePath)) {
            LOG.infof("Deleting entire lake directory: %s", lakePath);
            deleteDirectory(lakePath);
        }
    }
    
    /**
     * Deletes only protolake-specific files (lake.yaml, bundle.yaml files).
     * Leaves proto files and other content intact.
     */
    public void deleteProtolakeFiles(Lake lake) throws IOException {
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        if (!Files.exists(lakePath)) {
            return;
        }
        
        LOG.infof("Deleting protolake files from: %s", lakePath);
        
        // Delete lake.yaml
        Path lakeYaml = lakePath.resolve("lake.yaml");
        Files.deleteIfExists(lakeYaml);
        
        // Delete all bundle.yaml files
        Files.walk(lakePath)
            .filter(path -> path.getFileName().toString().equals("bundle.yaml"))
            .forEach(path -> {
                try {
                    Files.delete(path);
                    LOG.debugf("Deleted bundle.yaml: %s", path);
                } catch (IOException e) {
                    LOG.warnf("Failed to delete bundle.yaml: %s", path);
                }
            });
            
        // Delete Bazel workspace files
        Files.deleteIfExists(lakePath.resolve("WORKSPACE"));
        Files.deleteIfExists(lakePath.resolve("MODULE.bazel"));
        Files.deleteIfExists(lakePath.resolve(".bazelrc"));
        Files.deleteIfExists(lakePath.resolve(".bazelversion"));
        
        // Delete BUILD.bazel files (generated by gazelle)
        Files.walk(lakePath)
            .filter(path -> path.getFileName().toString().equals("BUILD.bazel"))
            .forEach(path -> {
                try {
                    Files.delete(path);
                    LOG.debugf("Deleted BUILD.bazel: %s", path);
                } catch (IOException e) {
                    LOG.warnf("Failed to delete BUILD.bazel: %s", path);
                }
            });
    }
    
    /**
     * Validates that a lake name only contains alphanumeric characters, hyphens, and underscores.
     * This ensures compatibility with proto package naming conventions.
     * 
     * @param name The lake name to validate
     * @throws IllegalArgumentException if the name contains invalid characters
     */
    private void validateLakeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Lake name cannot be null or empty");
        }
        
        // Check if name only contains alphanumeric, hyphens, and underscores
        if (!name.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException(
                "Lake name can only contain alphanumeric characters, hyphens, and underscores: " + name);
        }
    }
}
