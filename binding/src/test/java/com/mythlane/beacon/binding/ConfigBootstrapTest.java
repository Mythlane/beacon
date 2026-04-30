package com.mythlane.beacon.binding;

import java.nio.file.Path;

import com.mythlane.beacon.core.BeaconConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBootstrapTest {

    @Test
    void returnsLoadedConfig(@org.junit.jupiter.api.io.TempDir Path tmp) {
        BeaconConfig loaded = new BeaconConfig("http://example:4317", "svc", "grpc", 1024);
        ConfigBootstrap boot = new ConfigBootstrap(tmp.resolve("ignored.toml"), p -> loaded);

        assertThat(boot.load()).isSameAs(loaded);
    }

    @Test
    void fallsBackToDefaultsOnLoaderThrow(@org.junit.jupiter.api.io.TempDir Path tmp) {
        ConfigBootstrap boot = new ConfigBootstrap(tmp.resolve("ignored.toml"), p -> {
            throw new RuntimeException("boom");
        });

        BeaconConfig cfg = boot.load();
        BeaconConfig defaults = BeaconConfig.defaults();
        assertThat(cfg.endpoint()).isEqualTo(defaults.endpoint());
        assertThat(cfg.serviceName()).isEqualTo(defaults.serviceName());
        assertThat(cfg.protocol()).isEqualTo(defaults.protocol());
        assertThat(cfg.queueMaxSize()).isEqualTo(defaults.queueMaxSize());
    }

    @Test
    void productionConstructorAutoCreatesAndLoadsWhenFileMissing(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Path missing = tmp.resolve("missing.toml");
        ConfigBootstrap boot = new ConfigBootstrap(missing);

        BeaconConfig cfg = boot.load();
        assertThat(cfg).isNotNull();
        assertThat(cfg.endpoint()).isNotNull();
        assertThat(java.nio.file.Files.exists(missing)).isTrue();
    }
}
