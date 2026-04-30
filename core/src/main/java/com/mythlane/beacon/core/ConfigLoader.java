package com.mythlane.beacon.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

public final class ConfigLoader {

    public static final String ENV_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    public static final String ENV_SERVICE_NAME = "OTEL_SERVICE_NAME";
    public static final String ENV_PROTOCOL = "OTEL_EXPORTER_OTLP_PROTOCOL";

    public static final String SYS_ENDPOINT = "otel.exporter.otlp.endpoint";
    public static final String SYS_SERVICE_NAME = "otel.service.name";
    public static final String SYS_PROTOCOL = "otel.exporter.otlp.protocol";

    private static final String FILE_KEY_ENDPOINT = "otel.exporter.otlp.endpoint";
    private static final String FILE_KEY_SERVICE_NAME = "otel.service.name";
    private static final String FILE_KEY_PROTOCOL = "otel.exporter.otlp.protocol";
    private static final String FILE_KEY_QUEUE_MAX_SIZE = "beacon.queue.max_size";

    private static final long MAX_CONFIG_SIZE_BYTES = 1024L * 1024L;

    private ConfigLoader() {}

    public static BeaconConfig load(Path configFile) {
        return load(configFile, System.getenv(), System.getProperties());
    }

    public static BeaconConfig load(Path configFile, Map<String, String> env) {
        return load(configFile, env, new Properties());
    }

    public static BeaconConfig load(Path configFile, Map<String, String> env, Properties sys) {
        String endpoint = BeaconConfig.DEFAULT_ENDPOINT;
        String serviceName = BeaconConfig.DEFAULT_SERVICE_NAME;
        String protocol = BeaconConfig.DEFAULT_PROTOCOL;
        int queueMaxSize = BeaconConfig.DEFAULT_QUEUE_MAX_SIZE;

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

        if (env != null) {
            String envEndpoint = env.get(ENV_ENDPOINT);
            if (envEndpoint != null && !envEndpoint.isEmpty()) endpoint = envEndpoint;

            String envServiceName = env.get(ENV_SERVICE_NAME);
            if (envServiceName != null && !envServiceName.isEmpty()) serviceName = envServiceName;

            String envProtocol = env.get(ENV_PROTOCOL);
            if (envProtocol != null && !envProtocol.isEmpty()) protocol = envProtocol;
        }

        if (sys != null) {
            String sysEndpoint = sys.getProperty(SYS_ENDPOINT);
            if (sysEndpoint != null && !sysEndpoint.isEmpty()) endpoint = sysEndpoint;

            String sysServiceName = sys.getProperty(SYS_SERVICE_NAME);
            if (sysServiceName != null && !sysServiceName.isEmpty()) serviceName = sysServiceName;

            String sysProtocol = sys.getProperty(SYS_PROTOCOL);
            if (sysProtocol != null && !sysProtocol.isEmpty()) protocol = sysProtocol;
        }

        return new BeaconConfig(endpoint, serviceName, protocol, queueMaxSize);
    }
}
