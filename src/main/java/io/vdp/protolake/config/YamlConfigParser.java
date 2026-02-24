package io.vdp.protolake.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import protolake.v1.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles bidirectional conversion between YAML configuration files and Proto objects.
 * This ensures that lake.yaml and bundle.yaml files can be parsed back into their
 * corresponding proto messages, maintaining consistency between file and API representations.
 */
@ApplicationScoped
public class YamlConfigParser {
    private static final Logger LOG = Logger.getLogger(YamlConfigParser.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Parses a lake.yaml file into a Lake proto object.
     */
    public Lake parseLakeYaml(Path yamlPath) throws IOException {
        String content = Files.readString(yamlPath);
        JsonNode root = yamlMapper.readTree(content);
        
        Lake.Builder lakeBuilder = Lake.newBuilder();
        
        // Parse basic fields
        if (root.has("name")) {
            lakeBuilder.setName("lakes/" + root.get("name").asText());
        }
        if (root.has("display_name")) {
            lakeBuilder.setDisplayName(root.get("display_name").asText());
        }
        if (root.has("description")) {
            lakeBuilder.setDescription(root.get("description").asText());
        }
        if (root.has("lake_prefix")) {
            lakeBuilder.setLakePrefix(root.get("lake_prefix").asText());
        }
        
        // Parse config
        if (root.has("config")) {
            lakeBuilder.setConfig(parseLakeConfig(root.get("config")));
        }
        
        return lakeBuilder.build();
    }
    
    /**
     * Parses a bundle.yaml file into a Bundle proto object.
     */
    public Bundle parseBundleYaml(Path yamlPath, String lakeId) throws IOException {
        String content = Files.readString(yamlPath);
        JsonNode root = yamlMapper.readTree(content);
        
        Bundle.Builder bundleBuilder = Bundle.newBuilder();
        
        // Parse basic fields
        if (root.has("name")) {
            bundleBuilder.setName("lakes/" + lakeId + "/bundles/" + root.get("name").asText());
        }
        if (root.has("display_name")) {
            bundleBuilder.setDisplayName(root.get("display_name").asText());
        }
        if (root.has("description")) {
            bundleBuilder.setDescription(root.get("description").asText());
        }
        if (root.has("bundle_prefix")) {
            bundleBuilder.setBundlePrefix(root.get("bundle_prefix").asText());
        }
        if (root.has("version")) {
            bundleBuilder.setVersion(root.get("version").asText());
        }
        
        // Parse config
        if (root.has("config")) {
            bundleBuilder.setConfig(parseBundleConfig(root.get("config")));
        }
        
        return bundleBuilder.build();
    }
    
    /**
     * Serializes a Lake proto object to YAML format.
     */
    public String lakesToYaml(Lake lake) throws IOException {
        ObjectNode root = yamlMapper.createObjectNode();
        
        // Extract lake ID from resource name
        String lakeId = lake.getName().substring(lake.getName().lastIndexOf('/') + 1);
        root.put("name", lakeId);
        root.put("display_name", lake.getDisplayName());
        root.put("description", lake.getDescription());
        root.put("lake_prefix", lake.getLakePrefix());
        
        // Serialize config
        root.set("config", serializeLakeConfig(lake.getConfig()));
        
        return yamlMapper.writeValueAsString(root);
    }
    
    /**
     * Serializes a Bundle proto object to YAML format.
     */
    public String bundleToYaml(Bundle bundle) throws IOException {
        ObjectNode root = yamlMapper.createObjectNode();
        
        // Extract bundle ID from resource name
        String bundleId = bundle.getName().substring(bundle.getName().lastIndexOf('/') + 1);
        root.put("name", bundleId);
        root.put("display_name", bundle.getDisplayName());
        root.put("description", bundle.getDescription());
        root.put("bundle_prefix", bundle.getBundlePrefix());
        root.put("version", bundle.getVersion());
        
        // Serialize config
        root.set("config", serializeBundleConfig(bundle.getConfig()));
        
        return yamlMapper.writeValueAsString(root);
    }
    
    private LakeConfig parseLakeConfig(JsonNode configNode) {
        LakeConfig.Builder builder = LakeConfig.newBuilder();

        if (configNode.has("local_path")) {
            builder.setLocalPath(configNode.get("local_path").asText());
        }

        if (configNode.has("module_bazel")) {
            builder.setModuleBazel(parseModuleBazelConfig(configNode.get("module_bazel")));
        }

        if (configNode.has("language_defaults")) {
            builder.setLanguageDefaults(parseLanguageDefaults(configNode.get("language_defaults")));
        }

        if (configNode.has("validation")) {
            builder.setValidation(parseValidationConfig(configNode.get("validation")));
        }

        return builder.build();
    }
    
    private BundleConfig parseBundleConfig(JsonNode configNode) {
        BundleConfig.Builder builder = BundleConfig.newBuilder();

        if (configNode.has("languages")) {
            builder.setLanguages(parseLanguageConfig(configNode.get("languages")));
        }
        if (configNode.has("generate_descriptor_set")) {
            builder.setGenerateDescriptorSet(configNode.get("generate_descriptor_set").asBoolean());
        }

        return builder.build();
    }
    
    private ModuleBazelConfig parseModuleBazelConfig(JsonNode node) {
        ModuleBazelConfig.Builder builder = ModuleBazelConfig.newBuilder();
        
        if (node.has("protobuf_version")) {
            builder.setProtobufVersion(node.get("protobuf_version").asText());
        }
        if (node.has("grpc_version")) {
            builder.setGrpcVersion(node.get("grpc_version").asText());
        }
        if (node.has("rules_proto_grpc_version")) {
            builder.setRulesProtoGrpcVersion(node.get("rules_proto_grpc_version").asText());
        }
        
        if (node.has("additional_modules")) {
            for (JsonNode moduleNode : node.get("additional_modules")) {
                builder.addAdditionalModules(parseBazelModule(moduleNode));
            }
        }
        
        return builder.build();
    }
    
    private BazelModule parseBazelModule(JsonNode node) {
        BazelModule.Builder builder = BazelModule.newBuilder();
        
        if (node.has("name")) {
            builder.setName(node.get("name").asText());
        }
        if (node.has("version")) {
            builder.setVersion(node.get("version").asText());
        }
        if (node.has("registry_url")) {
            builder.setRegistryUrl(node.get("registry_url").asText());
        }
        
        return builder.build();
    }
    
    private LanguageDefaults parseLanguageDefaults(JsonNode node) {
        LanguageDefaults.Builder builder = LanguageDefaults.newBuilder();
        
        if (node.has("java")) {
            builder.setJava(parseJavaDefaults(node.get("java")));
        }
        if (node.has("python")) {
            builder.setPython(parsePythonDefaults(node.get("python")));
        }
        if (node.has("javascript")) {
            builder.setJavascript(parseJavaScriptDefaults(node.get("javascript")));
        }
        if (node.has("go")) {
            builder.setGo(parseGoDefaults(node.get("go")));
        }
        
        return builder.build();
    }
    
    private LanguageConfig parseLanguageConfig(JsonNode node) {
        LanguageConfig.Builder builder = LanguageConfig.newBuilder();
        
        if (node.has("java")) {
            builder.setJava(parseJavaConfig(node.get("java")));
        }
        if (node.has("python")) {
            builder.setPython(parsePythonConfig(node.get("python")));
        }
        if (node.has("javascript")) {
            builder.setJavascript(parseJavaScriptConfig(node.get("javascript")));
        }
        if (node.has("go")) {
            builder.setGo(parseGoConfig(node.get("go")));
        }
        
        return builder.build();
    }
    
    private JavaDefaults parseJavaDefaults(JsonNode node) {
        JavaDefaults.Builder builder = JavaDefaults.newBuilder();
        
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("group_id")) {
            builder.setGroupId(node.get("group_id").asText());
        }
        if (node.has("source_version")) {
            builder.setSourceVersion(node.get("source_version").asText());
        }
        if (node.has("target_version")) {
            builder.setTargetVersion(node.get("target_version").asText());
        }
        if (node.has("protobuf_java_version")) {
            builder.setProtobufJavaVersion(node.get("protobuf_java_version").asText());
        }
        if (node.has("grpc_java_version")) {
            builder.setGrpcJavaVersion(node.get("grpc_java_version").asText());
        }
        if (node.has("java_multiple_files")) {
            builder.setJavaMultipleFiles(node.get("java_multiple_files").asBoolean());
        }
        if (node.has("java_outer_classname_suffix")) {
            builder.setJavaOuterClassnameSuffix(node.get("java_outer_classname_suffix").asText());
        }
        
        if (node.has("additional_dependencies")) {
            for (JsonNode depNode : node.get("additional_dependencies")) {
                builder.addAdditionalDependencies(parseMavenDependency(depNode));
            }
        }
        
        return builder.build();
    }
    
    private JavaConfig parseJavaConfig(JsonNode node) {
        JavaConfig.Builder builder = JavaConfig.newBuilder();
        
        // Parse all the same fields as JavaDefaults
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("group_id")) {
            builder.setGroupId(node.get("group_id").asText());
        }
        if (node.has("artifact_id")) {
            builder.setArtifactId(node.get("artifact_id").asText());
        }
        if (node.has("source_version")) {
            builder.setSourceVersion(node.get("source_version").asText());
        }
        if (node.has("target_version")) {
            builder.setTargetVersion(node.get("target_version").asText());
        }
        if (node.has("protobuf_java_version")) {
            builder.setProtobufJavaVersion(node.get("protobuf_java_version").asText());
        }
        if (node.has("grpc_java_version")) {
            builder.setGrpcJavaVersion(node.get("grpc_java_version").asText());
        }
        if (node.has("java_multiple_files")) {
            builder.setJavaMultipleFiles(node.get("java_multiple_files").asBoolean());
        }
        if (node.has("java_outer_classname_suffix")) {
            builder.setJavaOuterClassnameSuffix(node.get("java_outer_classname_suffix").asText());
        }
        
        if (node.has("dependencies")) {
            for (JsonNode depNode : node.get("dependencies")) {
                builder.addDependencies(parseMavenDependency(depNode));
            }
        }
        
        return builder.build();
    }
    
    private MavenDependency parseMavenDependency(JsonNode node) {
        MavenDependency.Builder builder = MavenDependency.newBuilder();
        
        if (node.has("group_id")) {
            builder.setGroupId(node.get("group_id").asText());
        }
        if (node.has("artifact_id")) {
            builder.setArtifactId(node.get("artifact_id").asText());
        }
        if (node.has("version")) {
            builder.setVersion(node.get("version").asText());
        }
        if (node.has("scope")) {
            builder.setScope(node.get("scope").asText());
        }
        
        return builder.build();
    }
    
    private PythonDefaults parsePythonDefaults(JsonNode node) {
        PythonDefaults.Builder builder = PythonDefaults.newBuilder();
        
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("package_prefix")) {
            builder.setPackagePrefix(node.get("package_prefix").asText());
        }
        if (node.has("python_version")) {
            builder.setPythonVersion(node.get("python_version").asText());
        }
        if (node.has("protobuf_version")) {
            builder.setProtobufVersion(node.get("protobuf_version").asText());
        }
        if (node.has("grpcio_version")) {
            builder.setGrpcioVersion(node.get("grpcio_version").asText());
        }
        if (node.has("stub_type")) {
            builder.setStubType(node.get("stub_type").asText());
        }
        
        if (node.has("additional_dependencies")) {
            for (JsonNode depNode : node.get("additional_dependencies")) {
                builder.addAdditionalDependencies(depNode.asText());
            }
        }
        
        return builder.build();
    }
    
    private PythonConfig parsePythonConfig(JsonNode node) {
        PythonConfig.Builder builder = PythonConfig.newBuilder();
        
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("package_name")) {
            builder.setPackageName(node.get("package_name").asText());
        }
        if (node.has("python_version")) {
            builder.setPythonVersion(node.get("python_version").asText());
        }
        if (node.has("protobuf_version")) {
            builder.setProtobufVersion(node.get("protobuf_version").asText());
        }
        if (node.has("grpcio_version")) {
            builder.setGrpcioVersion(node.get("grpcio_version").asText());
        }
        if (node.has("stub_type")) {
            builder.setStubType(node.get("stub_type").asText());
        }
        
        if (node.has("dependencies")) {
            for (JsonNode depNode : node.get("dependencies")) {
                builder.addDependencies(depNode.asText());
            }
        }
        
        return builder.build();
    }
    
    private JavaScriptDefaults parseJavaScriptDefaults(JsonNode node) {
        JavaScriptDefaults.Builder builder = JavaScriptDefaults.newBuilder();

        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("package_scope")) {
            builder.setPackageScope(node.get("package_scope").asText());
        }
        if (node.has("node_version")) {
            builder.setNodeVersion(node.get("node_version").asText());
        }
        if (node.has("bufbuild_protobuf_version")) {
            builder.setBufbuildProtobufVersion(node.get("bufbuild_protobuf_version").asText());
        }
        if (node.has("protoc_gen_es_version")) {
            builder.setProtocGenEsVersion(node.get("protoc_gen_es_version").asText());
        }
        if (node.has("connectrpc_connect_version")) {
            builder.setConnectrpcConnectVersion(node.get("connectrpc_connect_version").asText());
        }
        if (node.has("use_typescript")) {
            builder.setUseTypescript(node.get("use_typescript").asBoolean());
        }
        if (node.has("module_type")) {
            builder.setModuleType(node.get("module_type").asText());
        }
        if (node.has("proto_loader")) {
            builder.setProtoLoader(node.get("proto_loader").asBoolean());
        }

        if (node.has("additional_dependencies")) {
            for (JsonNode depNode : node.get("additional_dependencies")) {
                builder.addAdditionalDependencies(parseNpmDependency(depNode));
            }
        }

        return builder.build();
    }
    
    private JavaScriptConfig parseJavaScriptConfig(JsonNode node) {
        JavaScriptConfig.Builder builder = JavaScriptConfig.newBuilder();

        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("package_name")) {
            builder.setPackageName(node.get("package_name").asText());
        }
        if (node.has("node_version")) {
            builder.setNodeVersion(node.get("node_version").asText());
        }
        if (node.has("bufbuild_protobuf_version")) {
            builder.setBufbuildProtobufVersion(node.get("bufbuild_protobuf_version").asText());
        }
        if (node.has("protoc_gen_es_version")) {
            builder.setProtocGenEsVersion(node.get("protoc_gen_es_version").asText());
        }
        if (node.has("connectrpc_connect_version")) {
            builder.setConnectrpcConnectVersion(node.get("connectrpc_connect_version").asText());
        }
        if (node.has("use_typescript")) {
            builder.setUseTypescript(node.get("use_typescript").asBoolean());
        }
        if (node.has("module_type")) {
            builder.setModuleType(node.get("module_type").asText());
        }
        if (node.has("proto_loader")) {
            builder.setProtoLoader(node.get("proto_loader").asBoolean());
        }

        if (node.has("dependencies")) {
            for (JsonNode depNode : node.get("dependencies")) {
                builder.addDependencies(parseNpmDependency(depNode));
            }
        }

        return builder.build();
    }
    
    private NpmDependency parseNpmDependency(JsonNode node) {
        NpmDependency.Builder builder = NpmDependency.newBuilder();
        
        if (node.has("name")) {
            builder.setName(node.get("name").asText());
        }
        if (node.has("version")) {
            builder.setVersion(node.get("version").asText());
        }
        if (node.has("dev_dependency")) {
            builder.setDevDependency(node.get("dev_dependency").asBoolean());
        }
        
        return builder.build();
    }
    
    private GoDefaults parseGoDefaults(JsonNode node) {
        GoDefaults.Builder builder = GoDefaults.newBuilder();
        
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("module_prefix")) {
            builder.setModulePrefix(node.get("module_prefix").asText());
        }
        if (node.has("go_version")) {
            builder.setGoVersion(node.get("go_version").asText());
        }
        
        return builder.build();
    }
    
    private GoConfig parseGoConfig(JsonNode node) {
        GoConfig.Builder builder = GoConfig.newBuilder();
        
        if (node.has("enabled")) {
            builder.setEnabled(node.get("enabled").asBoolean());
        }
        if (node.has("module_path")) {
            builder.setModulePath(node.get("module_path").asText());
        }
        if (node.has("go_version")) {
            builder.setGoVersion(node.get("go_version").asText());
        }
        
        return builder.build();
    }
    
    private ValidationConfig parseValidationConfig(JsonNode node) {
        ValidationConfig.Builder builder = ValidationConfig.newBuilder();
        
        if (node.has("buf_config_path")) {
            builder.setBufConfigPath(node.get("buf_config_path").asText());
        }
        
        return builder.build();
    }
    
    // Serialization methods
    
    private ObjectNode serializeLakeConfig(LakeConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();

        if (!config.getLocalPath().isEmpty()) {
            node.put("local_path", config.getLocalPath());
        }

        if (config.hasModuleBazel()) {
            node.set("module_bazel", serializeModuleBazelConfig(config.getModuleBazel()));
        }

        if (config.hasLanguageDefaults()) {
            node.set("language_defaults", serializeLanguageDefaults(config.getLanguageDefaults()));
        }

        if (config.hasValidation()) {
            node.set("validation", serializeValidationConfig(config.getValidation()));
        }

        return node;
    }
    
    private ObjectNode serializeBundleConfig(BundleConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();

        if (config.hasLanguages()) {
            node.set("languages", serializeLanguageConfig(config.getLanguages()));
        }
        if (config.getGenerateDescriptorSet()) {
            node.put("generate_descriptor_set", true);
        }

        return node;
    }
    
    private ObjectNode serializeModuleBazelConfig(ModuleBazelConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("protobuf_version", config.getProtobufVersion());
        node.put("grpc_version", config.getGrpcVersion());
        node.put("rules_proto_grpc_version", config.getRulesProtoGrpcVersion());
        
        if (config.getAdditionalModulesCount() > 0) {
            List<ObjectNode> modules = new ArrayList<>();
            for (BazelModule module : config.getAdditionalModulesList()) {
                ObjectNode moduleNode = yamlMapper.createObjectNode();
                moduleNode.put("name", module.getName());
                moduleNode.put("version", module.getVersion());
                if (!module.getRegistryUrl().isEmpty()) {
                    moduleNode.put("registry_url", module.getRegistryUrl());
                }
                modules.add(moduleNode);
            }
            node.set("additional_modules", yamlMapper.valueToTree(modules));
        }
        
        return node;
    }
    
    private ObjectNode serializeLanguageDefaults(LanguageDefaults defaults) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        if (defaults.hasJava()) {
            node.set("java", serializeJavaDefaults(defaults.getJava()));
        }
        if (defaults.hasPython()) {
            node.set("python", serializePythonDefaults(defaults.getPython()));
        }
        if (defaults.hasJavascript()) {
            node.set("javascript", serializeJavaScriptDefaults(defaults.getJavascript()));
        }
        if (defaults.hasGo()) {
            node.set("go", serializeGoDefaults(defaults.getGo()));
        }
        
        return node;
    }
    
    private ObjectNode serializeLanguageConfig(LanguageConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        if (config.hasJava()) {
            node.set("java", serializeJavaConfig(config.getJava()));
        }
        if (config.hasPython()) {
            node.set("python", serializePythonConfig(config.getPython()));
        }
        if (config.hasJavascript()) {
            node.set("javascript", serializeJavaScriptConfig(config.getJavascript()));
        }
        if (config.hasGo()) {
            node.set("go", serializeGoConfig(config.getGo()));
        }
        
        return node;
    }
    
    private ObjectNode serializeJavaDefaults(JavaDefaults defaults) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", defaults.getEnabled());
        node.put("group_id", defaults.getGroupId());
        node.put("source_version", defaults.getSourceVersion());
        node.put("target_version", defaults.getTargetVersion());
        node.put("protobuf_java_version", defaults.getProtobufJavaVersion());
        node.put("grpc_java_version", defaults.getGrpcJavaVersion());
        node.put("java_multiple_files", defaults.getJavaMultipleFiles());
        node.put("java_outer_classname_suffix", defaults.getJavaOuterClassnameSuffix());
        
        return node;
    }
    
    private ObjectNode serializeJavaConfig(JavaConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", config.getEnabled());
        if (!config.getGroupId().isEmpty()) {
            node.put("group_id", config.getGroupId());
        }
        if (!config.getArtifactId().isEmpty()) {
            node.put("artifact_id", config.getArtifactId());
        }
        
        // Add other fields as needed
        
        return node;
    }
    
    private ObjectNode serializePythonDefaults(PythonDefaults defaults) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", defaults.getEnabled());
        node.put("package_prefix", defaults.getPackagePrefix());
        node.put("python_version", defaults.getPythonVersion());
        node.put("protobuf_version", defaults.getProtobufVersion());
        node.put("grpcio_version", defaults.getGrpcioVersion());
        node.put("stub_type", defaults.getStubType());
        
        return node;
    }
    
    private ObjectNode serializePythonConfig(PythonConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", config.getEnabled());
        if (!config.getPackageName().isEmpty()) {
            node.put("package_name", config.getPackageName());
        }
        
        // Add other fields as needed
        
        return node;
    }
    
    private ObjectNode serializeJavaScriptDefaults(JavaScriptDefaults defaults) {
        ObjectNode node = yamlMapper.createObjectNode();

        node.put("enabled", defaults.getEnabled());
        node.put("package_scope", defaults.getPackageScope());
        node.put("node_version", defaults.getNodeVersion());
        if (!defaults.getBufbuildProtobufVersion().isEmpty()) {
            node.put("bufbuild_protobuf_version", defaults.getBufbuildProtobufVersion());
        }
        if (!defaults.getProtocGenEsVersion().isEmpty()) {
            node.put("protoc_gen_es_version", defaults.getProtocGenEsVersion());
        }
        if (!defaults.getConnectrpcConnectVersion().isEmpty()) {
            node.put("connectrpc_connect_version", defaults.getConnectrpcConnectVersion());
        }
        node.put("use_typescript", defaults.getUseTypescript());
        node.put("module_type", defaults.getModuleType());
        if (defaults.getProtoLoader()) {
            node.put("proto_loader", true);
        }

        return node;
    }
    
    private ObjectNode serializeJavaScriptConfig(JavaScriptConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();

        node.put("enabled", config.getEnabled());
        if (!config.getPackageName().isEmpty()) {
            node.put("package_name", config.getPackageName());
        }
        if (!config.getNodeVersion().isEmpty()) {
            node.put("node_version", config.getNodeVersion());
        }
        if (!config.getBufbuildProtobufVersion().isEmpty()) {
            node.put("bufbuild_protobuf_version", config.getBufbuildProtobufVersion());
        }
        if (!config.getProtocGenEsVersion().isEmpty()) {
            node.put("protoc_gen_es_version", config.getProtocGenEsVersion());
        }
        if (!config.getConnectrpcConnectVersion().isEmpty()) {
            node.put("connectrpc_connect_version", config.getConnectrpcConnectVersion());
        }
        if (config.getUseTypescript()) {
            node.put("use_typescript", true);
        }
        if (!config.getModuleType().isEmpty()) {
            node.put("module_type", config.getModuleType());
        }
        if (config.getProtoLoader()) {
            node.put("proto_loader", true);
        }

        return node;
    }
    
    private ObjectNode serializeGoDefaults(GoDefaults defaults) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", defaults.getEnabled());
        node.put("module_prefix", defaults.getModulePrefix());
        node.put("go_version", defaults.getGoVersion());
        
        return node;
    }
    
    private ObjectNode serializeGoConfig(GoConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        node.put("enabled", config.getEnabled());
        if (!config.getModulePath().isEmpty()) {
            node.put("module_path", config.getModulePath());
        }
        
        return node;
    }
    
    private ObjectNode serializeValidationConfig(ValidationConfig config) {
        ObjectNode node = yamlMapper.createObjectNode();
        
        if (!config.getBufConfigPath().isEmpty()) {
            node.put("buf_config_path", config.getBufConfigPath());
        }
        
        return node;
    }
}
