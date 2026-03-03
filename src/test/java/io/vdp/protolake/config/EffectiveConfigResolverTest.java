package io.vdp.protolake.config;

import io.vdp.protolake.config.EffectiveConfigResolver.ResolvedConfig;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.GoConfig;
import protolake.v1.GoDefaults;
import protolake.v1.JavaConfig;
import protolake.v1.JavaDefaults;
import protolake.v1.JavaScriptConfig;
import protolake.v1.JavaScriptDefaults;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.LanguageConfig;
import protolake.v1.LanguageDefaults;
import protolake.v1.PythonConfig;
import protolake.v1.PythonDefaults;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveConfigResolverTest {

    private final EffectiveConfigResolver resolver = new EffectiveConfigResolver();

    @Test
    void resolve_lakeDefaultsOnly_noBundleOverrides() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJava(JavaDefaults.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.company.proto")
                        .build())
                    .setPython(PythonDefaults.newBuilder()
                        .setEnabled(true)
                        .setPackagePrefix("company_proto")
                        .build())
                    .setJavascript(JavaScriptDefaults.newBuilder()
                        .setEnabled(true)
                        .setPackageScope("@company")
                        .build())
                    .build())
                .build())
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("2.0.0")
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.javaEnabled()).isTrue();
        assertThat(config.javaGroupId()).isEqualTo("com.company.proto");
        assertThat(config.javaArtifactId()).isEmpty();
        assertThat(config.jsEnabled()).isTrue();
        assertThat(config.jsPackageName()).isEqualTo("@company");
        assertThat(config.pythonEnabled()).isTrue();
        assertThat(config.pythonPackageName()).isEqualTo("company_proto");
        assertThat(config.version()).isEqualTo("2.0.0");
    }

    @Test
    void resolve_bundleOverridesGroupIdAndArtifactId() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJava(JavaDefaults.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.default.proto")
                        .build())
                    .build())
                .build())
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJava(JavaConfig.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.override.proto")
                        .setArtifactId("user-proto")
                        .build())
                    .build())
                .build())
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.javaEnabled()).isTrue();
        assertThat(config.javaGroupId()).isEqualTo("com.override.proto");
        assertThat(config.javaArtifactId()).isEqualTo("user-proto");
    }

    @Test
    void resolve_bundleDisablesLanguageThatLakeEnables() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJava(JavaDefaults.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.company.proto")
                        .build())
                    .setPython(PythonDefaults.newBuilder()
                        .setEnabled(true)
                        .setPackagePrefix("company_proto")
                        .build())
                    .build())
                .build())
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setPython(PythonConfig.newBuilder()
                        .setEnabled(false)
                        .build())
                    .build())
                .build())
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.javaEnabled()).isTrue();
        assertThat(config.pythonEnabled()).isFalse();
    }

    @Test
    void resolve_emptyLakeAndEmptyBundle() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.javaEnabled()).isFalse();
        assertThat(config.jsEnabled()).isFalse();
        assertThat(config.pythonEnabled()).isFalse();
        assertThat(config.goEnabled()).isFalse();
        assertThat(config.version()).isEqualTo("1.0.0");
    }

    @Test
    void resolve_jsPackageNameFromBundleTakesPrecedence() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJavascript(JavaScriptDefaults.newBuilder()
                        .setEnabled(true)
                        .setPackageScope("@lake-scope")
                        .build())
                    .build())
                .build())
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJavascript(JavaScriptConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("@company/user-proto")
                        .build())
                    .build())
                .build())
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.jsPackageName()).isEqualTo("@company/user-proto");
    }

    @Test
    void resolve_versionAlwaysFromBundle() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("3.2.1")
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.version()).isEqualTo("3.2.1");
    }

    @Test
    void resolve_goConfig() {
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setGo(GoDefaults.newBuilder()
                        .setEnabled(true)
                        .setModulePrefix("github.com/company/proto")
                        .build())
                    .build())
                .build())
            .build();

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setGo(GoConfig.newBuilder()
                        .setEnabled(true)
                        .setModulePath("github.com/company/proto/user")
                        .build())
                    .build())
                .build())
            .build();

        ResolvedConfig config = resolver.resolve(lake, bundle);

        assertThat(config.goEnabled()).isTrue();
        assertThat(config.goModulePath()).isEqualTo("github.com/company/proto/user");
    }
}
