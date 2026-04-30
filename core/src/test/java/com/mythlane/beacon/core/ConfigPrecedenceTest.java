package com.mythlane.beacon.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        byte[] big = new byte[(int) (1L * 1024L * 1024L + 1)];
        Files.write(file, big);

        assertThatThrownBy(() -> ConfigLoader.load(file, Map.of()))
                .isInstanceOf(ConfigLoaderException.class)
                .hasMessageContaining("config too large");
    }

    @Test
    void sysPropertyOverridesEnvVar() {
        Map<String, String> env = Map.of(ConfigLoader.ENV_SERVICE_NAME, "from-env");
        Properties sys = new Properties();
        sys.setProperty(ConfigLoader.SYS_SERVICE_NAME, "from-sys");

        BeaconConfig cfg = ConfigLoader.load(null, env, sys);

        assertThat(cfg.serviceName()).isEqualTo("from-sys");
    }

    @Test
    void envVarUsedWhenSysPropertyAbsent() {
        Map<String, String> env = Map.of(ConfigLoader.ENV_SERVICE_NAME, "from-env");

        BeaconConfig cfg = ConfigLoader.load(null, env, new Properties());

        assertThat(cfg.serviceName()).isEqualTo("from-env");
    }

    @Test
    void tomlUsedWhenNeitherSysNorEnv(@TempDir Path tmp) throws IOException {
        Path file = writeToml(tmp, "[otel.service]\nname = \"from-toml\"\n");

        BeaconConfig cfg = ConfigLoader.load(file, Map.of(), new Properties());

        assertThat(cfg.serviceName()).isEqualTo("from-toml");
    }

    @Test
    void sysPropertyAppliesToEndpointAndProtocol() {
        Properties sys = new Properties();
        sys.setProperty(ConfigLoader.SYS_ENDPOINT, "http://sys-endpoint:4317");
        sys.setProperty(ConfigLoader.SYS_PROTOCOL, "http/protobuf");

        BeaconConfig cfg = ConfigLoader.load(null, Map.of(), sys);

        assertThat(cfg.endpoint()).isEqualTo("http://sys-endpoint:4317");
        assertThat(cfg.protocol()).isEqualTo("http/protobuf");
    }

    @Test
    void queueDefaultAndProtocolDefault() {
        BeaconConfig cfg = ConfigLoader.load(null, Map.of());

        assertThat(cfg.queueMaxSize()).isEqualTo(16384);
        assertThat(cfg.protocol()).isEqualTo("grpc");
    }
}
