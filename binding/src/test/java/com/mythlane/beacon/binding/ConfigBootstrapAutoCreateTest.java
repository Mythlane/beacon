package com.mythlane.beacon.binding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mythlane.beacon.core.BeaconConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBootstrapAutoCreateTest {

    @Test
    void createsDefaultFileWhenAbsent(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("config.toml");
        ConfigBootstrap boot = new ConfigBootstrap(cfg);

        boot.load();

        assertThat(Files.exists(cfg)).isTrue();
        String body = Files.readString(cfg);
        assertThat(body)
                .contains("Beacon configuration")
                .contains("[otel.exporter.otlp]")
                .contains("[otel.service]")
                .contains("[beacon.queue]");
    }

    @Test
    void doesNotOverwriteExistingFile(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("config.toml");
        String userContent = "# user-modified\n[otel.exporter.otlp]\nendpoint = \"http://user:4317\"\n";
        Files.writeString(cfg, userContent);

        ConfigBootstrap boot = new ConfigBootstrap(cfg);
        boot.load();

        assertThat(Files.readString(cfg)).isEqualTo(userContent);
    }

    @Test
    void createsParentDirectoryIfMissing(@TempDir Path tmp) {
        Path cfg = tmp.resolve("mods").resolve("Mythlane_Beacon").resolve("config.toml");
        ConfigBootstrap boot = new ConfigBootstrap(cfg);

        boot.load();

        assertThat(Files.exists(cfg)).isTrue();
        assertThat(Files.isDirectory(cfg.getParent())).isTrue();
    }

    @Test
    void swallowsIOExceptionAndFallsBackToDefaults(@TempDir Path tmp) throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path cfg = blocker.resolve("config.toml");

        ConfigBootstrap boot = new ConfigBootstrap(cfg);
        BeaconConfig result = boot.load();

        assertThat(result).isNotNull();
        assertThat(result.endpoint()).isEqualTo(BeaconConfig.defaults().endpoint());
    }
}
