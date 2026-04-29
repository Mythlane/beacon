package com.mythlane.beacon.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Loads {@link BeaconConfig} with documented precedence env > config.toml > defaults
 * (CONTEXT.md D-04, FND-02).
 *
 * <p>Threat T-1-02-01: rejects config files larger than 1 MB before parsing
 * (DoS via TOML parser).
 */
public final class ConfigLoader {

    /** Standard OTel env var names. */
    public static final String ENV_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    public static final String ENV_SERVICE_NAME = "OTEL_SERVICE_NAME";
    public static final String ENV_PROTOCOL = "OTEL_EXPORTER_OTLP_PROTOCOL";

    /** TOML keys (mirror env semantics, lower-case dotless under [otel.exporter.otlp]). */
    private static final String FILE_KEY_ENDPOINT = "otel.exporter.otlp.endpoint";
    private static final String FILE_KEY_SERVICE_NAME = "otel.service.name";
    private static final String FILE_KEY_PROTOCOL = "otel.exporter.otlp.protocol";
    private static final String FILE_KEY_QUEUE_MAX_SIZE = "beacon.queue.max_size";

    private static final long MAX_CONFIG_SIZE_BYTES = 1L * 1024L * 1024L; // 1 MB

    private ConfigLoader() {}

    /** Load using ambient process env. */
    public static BeaconConfig load(Path configFile) {
        return load(configFile, System.getenv());
    }

    /**
     * Load with explicit env map (preferred for tests).
     *
     * @param configFile path to {@code config.toml}, may be {@code null} or non-existent
     * @param env environment variable map (typically {@link System#getenv()})
     */
    public static BeaconConfig load(Path configFile, Map<String, String> env) {
        // 1. Defaults
        String endpoint = BeaconConfig.DEFAULT_ENDPOINT;
        String serviceName = BeaconConfig.DEFAULT_SERVICE_NAME;
        String protocol = BeaconConfig.DEFAULT_PROTOCOL;
        int queueMaxSize = BeaconConfig.DEFAULT_QUEUE_MAX_SIZE;

        // 2. Overlay from config.toml (if present and small enough)
        if (configFile != null && Files.isRegularFile(configFile)) {
            try {
                long size = Files.size(configFile);
                if (size > MAX_CONFIG_SIZE_BYTES) {
                    throw new ConfigLoaderException("config too large: " + size + " bytes (max " + MAX_CONFIG_SIZE_BYTES + ")");
                }
            } catch (IOException e) {
                throw new ConfigLoaderException("failed to stat config file " + configFile, e);
            }

            TomlParseResult parsed;
            try {
                parsed = Toml.parse(configFile);
            } catch (IOException e) {
                throw new ConfigLoaderException("failed to read config file " + configFile, e);
            }
            if (parsed.hasErrors()) {
                throw new ConfigLoaderException("config.toml parse errors: " + parsed.errors());
            }

            String fileEndpoint = parsed.getString(FILE_KEY_ENDPOINT);
            if (fileEndpoint != null) endpoint = fileEndpoint;

            String fileServiceName = parsed.getString(FILE_KEY_SERVICE_NAME);
            if (fileServiceName != null) serviceName = fileServiceName;

            String fileProtocol = parsed.getString(FILE_KEY_PROTOCOL);
            if (fileProtocol != null) protocol = fileProtocol;

            Long fileQueueMaxSize = parsed.getLong(FILE_KEY_QUEUE_MAX_SIZE);
            if (fileQueueMaxSize != null) {
                if (fileQueueMaxSize <= 0 || fileQueueMaxSize > Integer.MAX_VALUE) {
                    throw new ConfigLoaderException("invalid queue.max_size: " + fileQueueMaxSize);
                }
                queueMaxSize = fileQueueMaxSize.intValue();
            }
        }

        // 3. Overlay from env (highest precedence)
        if (env != null) {
            String envEndpoint = env.get(ENV_ENDPOINT);
            if (envEndpoint != null && !envEndpoint.isEmpty()) endpoint = envEndpoint;

            String envServiceName = env.get(ENV_SERVICE_NAME);
            if (envServiceName != null && !envServiceName.isEmpty()) serviceName = envServiceName;

            String envProtocol = env.get(ENV_PROTOCOL);
            if (envProtocol != null && !envProtocol.isEmpty()) protocol = envProtocol;
        }

        return new BeaconConfig(endpoint, serviceName, protocol, queueMaxSize);
    }
}
