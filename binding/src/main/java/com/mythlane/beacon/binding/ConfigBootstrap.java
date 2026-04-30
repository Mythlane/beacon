package com.mythlane.beacon.binding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
    private static final String DEFAULT_TEMPLATE_RESOURCE = "/config-default.toml";

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
        ensureConfigFile(configPath);
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

    private static void ensureConfigFile(Path path) {
        if (path == null || Files.exists(path)) return;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (InputStream in = ConfigBootstrap.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
                if (in == null) {
                    LOG.warn("Beacon default config template missing on classpath ({}); skipping auto-create", DEFAULT_TEMPLATE_RESOURCE);
                    return;
                }
                Files.copy(in, path);
            }
            LOG.info("Created default Beacon config at {}", path);
        } catch (IOException e) {
            LOG.warn("Beacon config auto-create failed at {}; falling back to defaults", path, e);
        }
    }
}
