package io.vdp.protolake.cli;

import io.vdp.protolake.initializer.LakeInitializer;
import io.vdp.protolake.util.LakeUtil;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine;
import protolake.v1.*;

@Dependent
@CommandLine.Command(name = "init", description = "Initialize a new proto lake")
public class InitCommand implements Runnable {

    @CommandLine.Option(names = "--name", required = true, description = "Lake name")
    String name;

    @CommandLine.Option(names = "--display-name", description = "Human-readable display name")
    String displayName;

    @CommandLine.Option(names = "--description", description = "Lake description")
    String description;

    @CommandLine.Option(names = "--lake-prefix", description = "Lake prefix (directory path prefix)", defaultValue = "")
    String lakePrefix;

    @CommandLine.Option(names = "--java-group-id", description = "Default Java group ID", defaultValue = "com.example.proto")
    String javaGroupId;

    @CommandLine.Option(names = "--python-package-prefix", description = "Default Python package prefix", defaultValue = "example_proto")
    String pythonPackagePrefix;

    @CommandLine.Option(names = "--js-package-scope", description = "Default JS package scope", defaultValue = "@example")
    String jsPackageScope;

    @CommandLine.Option(names = "--protobuf-version", description = "Protobuf version", defaultValue = "31.1")
    String protobufVersion;

    @CommandLine.Option(names = "--grpc-version", description = "gRPC version", defaultValue = "1.78.0")
    String grpcVersion;

    @Inject
    LakeInitializer lakeInitializer;

    @Override
    public void run() {
        try {
            System.out.printf("[protolake] Initializing lake: %s%n", name);

            // Build LakeConfig from CLI options
            LakeConfig config = LakeConfig.newBuilder()
                    .setModuleBazel(ModuleBazelConfig.newBuilder()
                            .setProtobufVersion(protobufVersion)
                            .setGrpcVersion(grpcVersion)
                            .setRulesProtoGrpcVersion("5.8.0")
                            .build())
                    .setLanguageDefaults(LanguageDefaults.newBuilder()
                            .setJava(JavaDefaults.newBuilder()
                                    .setEnabled(true)
                                    .setGroupId(javaGroupId)
                                    .build())
                            .setPython(PythonDefaults.newBuilder()
                                    .setEnabled(true)
                                    .setPackagePrefix(pythonPackagePrefix)
                                    .build())
                            .setJavascript(JavaScriptDefaults.newBuilder()
                                    .setEnabled(true)
                                    .setPackageScope(jsPackageScope)
                                    .build())
                            .setGo(GoDefaults.newBuilder()
                                    .setEnabled(false)
                                    .build())
                            .build())
                    .build();

            Lake lake = lakeInitializer.initializeLake(name, displayName, description, config, lakePrefix);

            System.out.printf("[protolake] Lake initialized at: %s%n",
                    LakeUtil.getLocalPath(lake, lakeInitializer.getBasePath()));
            System.out.println("[protolake] Generated protolakew wrapper script.");
            System.out.println("[protolake] Next steps:");
            System.out.printf("  ./protolakew create-bundle <name> --bundle-prefix <prefix>%n");
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "Init failed: " + e.getMessage(), e);
        }
    }
}
