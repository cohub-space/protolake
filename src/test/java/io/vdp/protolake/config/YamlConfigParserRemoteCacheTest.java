package io.vdp.protolake.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import protolake.v1.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class YamlConfigParserRemoteCacheTest {

    @Inject
    YamlConfigParser parser;

    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("yaml-parser-test-");
    }

    @AfterEach
    void cleanup() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    void testRoundTripGcsRemoteCache() throws IOException {
        Lake lake = Lake.newBuilder()
                .setName("lakes/round-trip-lake")
                .setDisplayName("Round Trip Lake")
                .setDescription("Test round-trip")
                .setConfig(LakeConfig.newBuilder()
                        .setRemoteCache(RemoteCacheConfig.newBuilder()
                                .setProvider("gcs")
                                .setBucket("my-bucket")
                                .setCompression(true)
                                .build())
                        .build())
                .build();

        // Serialize to YAML
        String yaml = parser.lakesToYaml(lake);
        assertThat(yaml).contains("remote_cache");
        assertThat(yaml).contains("provider: \"gcs\"");
        assertThat(yaml).contains("bucket: \"my-bucket\"");
        assertThat(yaml).contains("compression: true");

        // Write to file and parse back
        Path yamlFile = tempDir.resolve("lake.yaml");
        Files.writeString(yamlFile, yaml);
        Lake parsed = parser.parseLakeYaml(yamlFile);

        assertThat(parsed.getConfig().hasRemoteCache()).isTrue();
        RemoteCacheConfig rc = parsed.getConfig().getRemoteCache();
        assertThat(rc.getProvider()).isEqualTo("gcs");
        assertThat(rc.getBucket()).isEqualTo("my-bucket");
        assertThat(rc.getCompression()).isTrue();
    }

    @Test
    void testParseWithoutRemoteCache() throws IOException {
        String yaml = """
                name: "no-cache-lake"
                display_name: "No Cache"
                description: ""
                config:
                  validation:
                    buf_config_path: "./buf.yaml"
                """;

        Path yamlFile = tempDir.resolve("lake.yaml");
        Files.writeString(yamlFile, yaml);
        Lake parsed = parser.parseLakeYaml(yamlFile);

        assertThat(parsed.getConfig().hasRemoteCache()).isFalse();
    }

    @Test
    void testRoundTripHttpRemoteCache() throws IOException {
        Lake lake = Lake.newBuilder()
                .setName("lakes/http-cache")
                .setDisplayName("HTTP Cache")
                .setDescription("")
                .setConfig(LakeConfig.newBuilder()
                        .setRemoteCache(RemoteCacheConfig.newBuilder()
                                .setProvider("http")
                                .setBucket("https://cache.example.com")
                                .build())
                        .build())
                .build();

        String yaml = parser.lakesToYaml(lake);
        Path yamlFile = tempDir.resolve("lake.yaml");
        Files.writeString(yamlFile, yaml);
        Lake parsed = parser.parseLakeYaml(yamlFile);

        RemoteCacheConfig rc = parsed.getConfig().getRemoteCache();
        assertThat(rc.getProvider()).isEqualTo("http");
        assertThat(rc.getBucket()).isEqualTo("https://cache.example.com");
        assertThat(rc.getCompression()).isFalse();
    }
}
