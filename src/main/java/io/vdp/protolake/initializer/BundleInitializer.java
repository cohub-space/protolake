package io.vdp.protolake.initializer;

import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import io.vdp.protolake.util.git.GitCommand;
import io.vdp.protolake.util.template.TemplateEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.Lake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the initialization of new bundles within lakes.
 *
 * This includes creating the bundle directory structure and bundle.yaml configuration.
 */
@ApplicationScoped
public class BundleInitializer {
    private static final Logger LOG = Logger.getLogger(BundleInitializer.class);

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;
    
    @Inject
    TemplateEngine templateEngine;

    @Inject
    GitCommand gitCommand;

    /**
     * Initializes a new bundle within a lake.
     * Returns a Bundle proto message.
     */
    public Bundle initializeBundle(Lake lake, String name, String displayName,
                                   String description, String bundlePrefix,
                                   BundleConfig config) throws IOException {
        String lakeName = LakeUtil.extractLakeId(lake.getName());
        LOG.infof("Initializing bundle %s in lake %s", name, lakeName);
        
        // Validate bundle name for proto compatibility
        validateBundleName(name);

        // Build bundle proto
        Bundle.Builder bundleBuilder = Bundle.newBuilder()
            .setName(BundleUtil.toResourceName(lakeName, name))
            .setDisplayName(displayName != null ? displayName : name)
            .setDescription(description != null ? description : "")
            .setVersion("1.0.0") // Default version
            .setCreateTime(LakeUtil.toProtoTimestamp(Instant.now()))
            .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()));
        
        // Set bundle prefix if provided (using dot notation)
        if (bundlePrefix != null && !bundlePrefix.isEmpty()) {
            bundleBuilder.setBundlePrefix(bundlePrefix);
        }
        
        if (config != null) {
            bundleBuilder.setConfig(config);
        }
        
        Bundle bundle = bundleBuilder.build();

        // Create bundle directory based on bundle prefix and bundle name
        Path bundlePath = BundleUtil.calculateBundlePath(lake, bundle, basePath);
        
        if (Files.exists(bundlePath) && Files.exists(bundlePath.resolve("bundle.yaml"))) {
            throw new IllegalStateException("Bundle already exists at: " + bundlePath);
        }
        Files.createDirectories(bundlePath);

        // Generate bundle.yaml
        generateBundleYaml(bundle, lake);

        // Create initial directory structure
        createBundleStructure(bundle, bundlePath);

        // Commit changes
        Path lakePath = LakeUtil.getLocalPath(lake, basePath);
        gitCommand.add(lakePath, bundlePath.toString());
        gitCommand.commit(lakePath,
                String.format("Add bundle: %s", name));

        LOG.infof("Successfully initialized bundle: %s", name);
        return bundle;
    }

    /**
     * Generates the bundle.yaml configuration file.
     */
    private void generateBundleYaml(Bundle bundle, Lake lake) throws IOException {
        Map<String, Object> context = new HashMap<>();
        String bundleId = BundleUtil.extractBundleId(bundle.getName());
        
        // Add required template fields
        context.put("bundleId", bundleId);
        context.put("displayName", bundle.getDisplayName());
        context.put("description", bundle.getDescription());
        context.put("bundlePrefix", bundle.getBundlePrefix());
        context.put("version", bundle.getVersion());

        // Add language-specific configurations
        // Extract enabled flags and bundle-specific identifiers that can't be inherited from lake.yaml
        
        boolean javaEnabled = true;
        boolean pythonEnabled = true;
        boolean jsEnabled = true;
        String javaArtifactId = "";
        String pythonPackageName = "";
        String jsPackageName = "";
        
        // Check if bundle has config overrides
        if (bundle.hasConfig() && bundle.getConfig().hasLanguages()) {
            if (bundle.getConfig().getLanguages().hasJava()) {
                javaEnabled = bundle.getConfig().getLanguages().getJava().getEnabled();
                // Extract artifact_id if provided
                if (!bundle.getConfig().getLanguages().getJava().getArtifactId().isEmpty()) {
                    javaArtifactId = bundle.getConfig().getLanguages().getJava().getArtifactId();
                }
            }
            if (bundle.getConfig().getLanguages().hasPython()) {
                pythonEnabled = bundle.getConfig().getLanguages().getPython().getEnabled();
                // Extract package_name if provided
                if (!bundle.getConfig().getLanguages().getPython().getPackageName().isEmpty()) {
                    pythonPackageName = bundle.getConfig().getLanguages().getPython().getPackageName();
                }
            }
            if (bundle.getConfig().getLanguages().hasJavascript()) {
                jsEnabled = bundle.getConfig().getLanguages().getJavascript().getEnabled();
                // Extract package_name if provided
                if (!bundle.getConfig().getLanguages().getJavascript().getPackageName().isEmpty()) {
                    jsPackageName = bundle.getConfig().getLanguages().getJavascript().getPackageName();
                }
            }
        }
        
        context.put("javaEnabled", javaEnabled);
        context.put("pythonEnabled", pythonEnabled);
        context.put("jsEnabled", jsEnabled);
        
        // Always add bundle-specific identifiers to context for Qute template compatibility
        context.put("javaArtifactId", javaArtifactId);
        context.put("pythonPackageName", pythonPackageName);
        context.put("jsPackageName", jsPackageName);

        // Calculate bundle path
        Path bundlePath = BundleUtil.calculateBundlePath(lake, bundle, basePath);
        
        Path configPath = bundlePath.resolve("bundle.yaml");
        templateEngine.renderToFile("bundle.yaml.tmpl", context, configPath);
    }

    /**
     * Creates the initial directory structure for a bundle.
     */
    private void createBundleStructure(Bundle bundle, Path bundlePath) throws IOException {
        // The bundle path is already at the correct location based on bundle prefix

        // Create initial proto file using template
        Map<String, Object> protoContext = new HashMap<>();
        
        // Generate the full proto package name
        // This combines bundle_prefix and bundle_name with dots
        String protoPackage = BundleUtil.getFullProtoPackage(bundle);
        // Convert hyphens to underscores for proto compatibility
        String protoPackageFormatted = protoPackage.replace("-", "_");
        
        // Pass the formatted proto package to the template
        protoContext.put("protoPackage", protoPackageFormatted);
        
        // Also provide bundle-specific names for the template
        String bundleId = BundleUtil.extractBundleId(bundle.getName());
        String bundleNamePascalCase = toPascalCase(bundleId);
        protoContext.put("bundleName", bundleId);
        protoContext.put("bundleNamePascalCase", bundleNamePascalCase);

        Path protoFile = bundlePath.resolve("example.proto");
        templateEngine.renderToFile("bundle-example.proto.tmpl", protoContext, protoFile);

        // Create README for the bundle using template
        Map<String, Object> readmeContext = new HashMap<>();
        readmeContext.put("displayName", bundle.getDisplayName());
        readmeContext.put("description", bundle.getDescription());
        readmeContext.put("bundlePrefix", bundle.getBundlePrefix());
        readmeContext.put("bundleName", BundleUtil.extractBundleId(bundle.getName()));
        readmeContext.put("protoPackage", protoPackageFormatted);

        templateEngine.renderToFile("bundle-readme.md.tmpl", readmeContext,
                bundlePath.resolve("README.md"));
    }
    
    /**
     * Validates that a bundle name only contains alphanumeric characters, hyphens, and underscores.
     * This ensures compatibility with proto package naming conventions.
     * 
     * @param name The bundle name to validate
     * @throws IllegalArgumentException if the name contains invalid characters
     */
    private void validateBundleName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Bundle name cannot be null or empty");
        }
        
        // Check if name only contains alphanumeric, hyphens, and underscores
        if (!name.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException(
                "Bundle name can only contain alphanumeric characters, hyphens, and underscores: " + name);
        }
    }
    
    /**
     * Converts a hyphenated string to PascalCase.
     * Example: "user-service" -> "UserService"
     */
    private String toPascalCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        String[] parts = input.split("-");
        StringBuilder result = new StringBuilder();
        
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }
        
        return result.toString();
    }
}
