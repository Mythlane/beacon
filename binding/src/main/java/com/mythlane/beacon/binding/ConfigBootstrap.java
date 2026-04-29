package com.mythlane.beacon.binding;

import java.nio.file.Path;

import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.core.ConfigLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConfigBootstrap {

    @FunctionalInterface
    interface Loader {
        BeaconConfig load(Path path);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConfigBootstrap.class);

    private final Path configPath;
    private final Loader loader;

    ConfigBootstrap(Path configPath) {
        this(configPath, ConfigLoader::load);
    }

    ConfigBootstrap(Path configPath, Loader loader) {
        this.configPath = configPath;
        this.loader = loader;
    }

    BeaconConfig load() {
        try {
            BeaconConfig cfg = loader.load(configPath);
            LOG.info("Beacon config loaded: endpoint={} service={} protocol={}",
                    cfg.endpoint(), cfg.serviceName(), cfg.protocol());
            return cfg;
        } catch (Throwable t) {
            LOG.warn("Beacon config load failed; using defaults", t);
            return BeaconConfig.defaults();
        }
    }
}
