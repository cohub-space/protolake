package io.vdp.protolake.api;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.snippet.DependencySnippetService;
import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.storage.ValidationException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;
import protolake.v1.BundleConfig;
import protolake.v1.GetDependencySnippetResponse;
import protolake.v1.JavaConfig;
import protolake.v1.JavaDefaults;
import protolake.v1.Lake;
import protolake.v1.LakeConfig;
import protolake.v1.Language;
import protolake.v1.LanguageConfig;
import protolake.v1.LanguageDefaults;
import protolake.v1.SnippetFormat;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for GetDependencySnippet flow through StorageService + DependencySnippetService.
 * Tests the same lookup+generate pattern as the RPC handler.
 */
@QuarkusTest
class GetDependencySnippetRpcTest {

    @Inject
    DependencySnippetService dependencySnippetService;

    @Inject
    StorageService storageService;

    @BeforeEach
    void setup() throws ValidationException {
        try { storageService.deleteBundle("snippet_lake", "snippet_bundle"); } catch (Exception ignored) {}
        try { storageService.deleteLake("snippet_lake"); } catch (Exception ignored) {}

        Lake lake = Lake.newBuilder()
            .setName("lakes/snippet_lake")
            .setDisplayName("Snippet Test Lake")
            .setConfig(LakeConfig.newBuilder()
                .setLanguageDefaults(LanguageDefaults.newBuilder()
                    .setJava(JavaDefaults.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.test.proto")
                        .build())
                    .build())
                .build())
            .build();
        storageService.createLake(lake);

        Bundle bundle = Bundle.newBuilder()
            .setName("lakes/snippet_lake/bundles/snippet_bundle")
            .setDisplayName("Test Bundle")
            .setVersion("1.0.0")
            .setConfig(BundleConfig.newBuilder()
                .setLanguages(LanguageConfig.newBuilder()
                    .setJava(JavaConfig.newBuilder()
                        .setEnabled(true)
                        .setGroupId("com.test.proto")
                        .setArtifactId("test-proto")
                        .build())
                    .build())
                .build())
            .build();
        storageService.createBundle(bundle);
    }

    @Test
    void getDependencySnippet_successfulRetrieval() {
        Optional<Lake> lake = storageService.getLake("snippet_lake");
        Optional<Bundle> bundle = storageService.getBundle("snippet_lake", "snippet_bundle");

        assertThat(lake).isPresent();
        assertThat(bundle).isPresent();

        GetDependencySnippetResponse response = dependencySnippetService.generateSnippets(
            lake.get(), bundle.get(), List.of(), SnippetFormat.FORMAT_UNSPECIFIED);

        assertThat(response.getSnippetsList()).hasSize(1);
        assertThat(response.getSnippetsList().get(0).getLanguage()).isEqualTo(Language.JAVA);
        assertThat(response.getSnippetsList().get(0).getSnippet())
            .contains("com.test.proto")
            .contains("test-proto")
            .contains("1.0.0");
    }

    @Test
    void getDependencySnippet_bundleNotFound() {
        Optional<Bundle> bundle = storageService.getBundle("snippet_lake", "nonexistent");
        assertThat(bundle).isEmpty();
    }

    @Test
    void getDependencySnippet_lakeNotFound() {
        Optional<Lake> lake = storageService.getLake("nonexistent");
        assertThat(lake).isEmpty();
    }
}
