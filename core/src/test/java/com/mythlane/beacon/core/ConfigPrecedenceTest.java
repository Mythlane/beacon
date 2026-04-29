package com.mythlane.beacon.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers FND-02 (config precedence: env > file > default) per Plan 02 Task 1 behaviors.
 */
class ConfigPrecedenceTest {

    private static Path writeToml(Path dir, String content) throws IOException {
        Path f = dir.resolve("config.toml");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void envOverridesFile(@TempDir Path tmp) throws IOException {
        Path file = writeToml(tmp, "[otel.exporter.otlp]\nendpoint = \"http://file\"\n");
        Map<String, String> env = new HashMap<>();
        env.put(ConfigLoader.ENV_ENDPOINT, "http://env");

        BeaconConfig cfg = ConfigLoader.load(file, env);

        assertThat(cfg.endpoint()).isEqualTo("http://env");
    }

    @Test
    void fileOverridesDefaultWhenEnvUnset(@TempDir Path tmp) throws IOException {
        Path file = writeToml(tmp, "[otel.exporter.otlp]\nendpoint = \"http://file\"\n");

        BeaconConfig cfg = ConfigLoader.load(file, Map.of());

        assertThat(cfg.endpoint()).isEqualTo("http://file");
    }

    @Test
    void defaultsWhenNoFileAndNoEnv() {
        BeaconConfig cfg = ConfigLoader.load(null, Map.of());

        assertThat(cfg.endpoint()).isEqualTo("http://localhost:4317");
        assertThat(cfg.serviceName()).isEqualTo("hytale-server");
    }

    @Test
    void serviceNameRespectedFromEnvOtherwiseDefault() {
        BeaconConfig defaultCfg = ConfigLoader.load(null, Map.of());
        assertThat(defaultCfg.serviceName()).isEqualTo("hytale-server");

        BeaconConfig envCfg = ConfigLoader.load(null, Map.of(ConfigLoader.ENV_SERVICE_NAME, "my-svc"));
        assertThat(envCfg.serviceName()).isEqualTo("my-svc");
    }

    @Test
    void rejectsOversizedConfigFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("config.toml");
        // > 1 MB
        byte[] big = new byte[(int) (1L * 1024L * 1024L + 1)];
        Files.write(file, big);

        assertThatThrownBy(() -> ConfigLoader.load(file, Map.of()))
                .isInstanceOf(ConfigLoaderException.class)
                .hasMessageContaining("config too large");
    }

    @Test
    void queueDefaultAndProtocolDefault() {
        BeaconConfig cfg = ConfigLoader.load(null, Map.of());

        assertThat(cfg.queueMaxSize()).isEqualTo(16384);
        assertThat(cfg.protocol()).isEqualTo("grpc");
    }
}
