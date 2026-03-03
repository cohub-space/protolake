package io.vdp.protolake.snippet;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.DependencySnippet;
import protolake.v1.GetDependencySnippetResponse;
import protolake.v1.JavaConfig;
import protolake.v1.JavaDefaults;
import protolake.v1.JavaScriptConfig;
import protolake.v1.JavaScriptDefaults;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.Language;
import protolake.v1.LanguageConfig;
import protolake.v1.LanguageDefaults;
import protolake.v1.PythonConfig;
import protolake.v1.PythonDefaults;
import protolake.v1.SnippetFormat;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DependencySnippetServiceTest {

    @Inject
    DependencySnippetService service;

    private Lake testLake() {
        return Lake.newBuilder()
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
    }

    private Bundle testBundle() {
        return Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJava(JavaConfig.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.company.proto")
                        .setArtifactId("user-proto")
                        .build())
                    .setJavascript(JavaScriptConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("@company/user-proto")
                        .build())
                    .setPython(PythonConfig.newBuilder()
                        .setEnabled(true)
                        .setPackageName("company_user_proto")
                        .build())
                    .build())
                .build())
            .build();
    }

    @Test
    void generateSnippets_allLanguagesEnabled_canonicalOrder() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(), SnippetFormat.FORMAT_UNSPECIFIED);

        assertThat(response.getSnippetsList()).hasSize(3);
        assertThat(response.getSnippetsList().get(0).getLanguage()).isEqualTo(Language.JAVA);
        assertThat(response.getSnippetsList().get(1).getLanguage()).isEqualTo(Language.JAVASCRIPT);
        assertThat(response.getSnippetsList().get(2).getLanguage()).isEqualTo(Language.PYTHON);
        assertThat(response.getWarningsList()).isEmpty();
    }

    @Test
    void generateSnippets_languageFilter_onlyJava() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.FORMAT_UNSPECIFIED);

        assertThat(response.getSnippetsList()).hasSize(1);
        assertThat(response.getSnippetsList().get(0).getLanguage()).isEqualTo(Language.JAVA);
    }

    @Test
    void generateSnippets_formatOverride_gradleKotlin() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.GRADLE_KOTLIN);

        assertThat(response.getSnippetsList()).hasSize(1);
        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.GRADLE_KOTLIN);
        assertThat(snippet.getSnippet()).isEqualTo(
            "implementation(\"com.company.proto:user-proto:1.0.0\")");
    }

    @Test
    void generateSnippets_formatMismatch_gradleKotlinForJs_fallsBackWithWarning() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(),
            List.of(Language.JAVASCRIPT), SnippetFormat.GRADLE_KOTLIN);

        assertThat(response.getSnippetsList()).hasSize(1);
        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.NPM);
        assertThat(response.getWarningsList()).anyMatch(
            w -> w.contains("JAVASCRIPT") && w.contains("GRADLE_KOTLIN"));
    }

    @Test
    void generateSnippets_missingConfig_jsWithNoPackageName() {
        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/test/bundles/user")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJavascript(JavaScriptConfig.newBuilder()
                        .setEnabled(true)
                        // no package_name
                        .build())
                    .build())
                .build())
            .build();

        // Lake enables JS with scope but no package_name in bundle
        Lake lake = Lake.newBuilder()
            .setName("lakes/test")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJavascript(JavaScriptDefaults.newBuilder()
                        .setEnabled(true)
                        // packageScope is set but we don't derive package_name from it
                        .build())
                    .build())
                .build())
            .build();

        GetDependencySnippetResponse response = service.generateSnippets(
            lake, bundle, List.of(Language.JAVASCRIPT), SnippetFormat.FORMAT_UNSPECIFIED);

        assertThat(response.getSnippetsList()).isEmpty();
        assertThat(response.getWarningsList()).anyMatch(
            w -> w.contains("JAVASCRIPT") && w.contains("missing package_name"));
    }

    @Test
    void generateSnippets_disabledLanguageExplicitlyRequested_warning() {
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

        Lake lake = Lake.newBuilder().setName("lakes/test").build();

        GetDependencySnippetResponse response = service.generateSnippets(
            lake, bundle, List.of(Language.PYTHON), SnippetFormat.FORMAT_UNSPECIFIED);

        assertThat(response.getSnippetsList()).isEmpty();
        assertThat(response.getWarningsList()).anyMatch(
            w -> w.contains("PYTHON") && w.contains("not enabled"));
    }

    @Test
    void generateSnippets_mavenSnippetCorrectness() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.MAVEN);

        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getSnippet()).contains("<groupId>com.company.proto</groupId>");
        assertThat(snippet.getSnippet()).contains("<artifactId>user-proto</artifactId>");
        assertThat(snippet.getSnippet()).contains("<version>1.0.0</version>");
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.MAVEN);
        assertThat(snippet.getInstallCommand()).isEmpty();
    }

    @Test
    void generateSnippets_gradleGroovySnippetCorrectness() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.GRADLE_GROOVY);

        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getSnippet()).isEqualTo(
            "implementation 'com.company.proto:user-proto:1.0.0'");
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.GRADLE_GROOVY);
    }

    @Test
    void generateSnippets_npmSnippetCorrectness() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVASCRIPT), SnippetFormat.NPM);

        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getSnippet()).isEqualTo("\"@company/user-proto\": \"1.0.0\"");
        assertThat(snippet.getInstallCommand()).isEqualTo("npm install @company/user-proto@1.0.0");
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.NPM);
    }

    @Test
    void generateSnippets_yarnSnippetCorrectness() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVASCRIPT), SnippetFormat.YARN);

        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getSnippet()).isEqualTo("\"@company/user-proto\": \"1.0.0\"");
        assertThat(snippet.getInstallCommand()).isEqualTo("yarn add @company/user-proto@1.0.0");
        assertThat(snippet.getFormat()).isEqualTo(SnippetFormat.YARN);
    }

    @Test
    void generateSnippets_installCommandEmptyForMavenAndGradle() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.MAVEN);

        assertThat(response.getSnippetsList().get(0).getInstallCommand()).isEmpty();

        response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.JAVA), SnippetFormat.GRADLE_KOTLIN);

        assertThat(response.getSnippetsList().get(0).getInstallCommand()).isEmpty();
    }

    @Test
    void generateSnippets_pythonSnippetCorrectness() {
        GetDependencySnippetResponse response = service.generateSnippets(
            testLake(), testBundle(), List.of(Language.PYTHON), SnippetFormat.FORMAT_UNSPECIFIED);

        DependencySnippet snippet = response.getSnippetsList().get(0);
        assertThat(snippet.getSnippet()).isEqualTo("company_user_proto==1.0.0");
        assertThat(snippet.getInstallCommand()).isEqualTo("pip install company_user_proto==1.0.0");
    }
}
