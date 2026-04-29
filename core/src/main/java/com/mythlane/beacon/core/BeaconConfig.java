package com.mythlane.beacon.core;

import java.util.Objects;

/**
 * Immutable Beacon configuration data class. Built by {@link ConfigLoader}.
 *
 * <p>Contract: precedence env > config.toml > defaults (D-04). Defaults
 * mandated by Plan 02 frontmatter:
 * <ul>
 *   <li>endpoint = {@code http://localhost:4317} (OTLP gRPC default)</li>
 *   <li>serviceName = {@code hytale-server}</li>
 *   <li>protocol = {@code grpc}</li>
 *   <li>queueMaxSize = {@code 16384} (D-08, PITFALLS P2)</li>
 * </ul>
 */
public final class BeaconConfig {

    public static final String DEFAULT_ENDPOINT = "http://localhost:4317";
    public static final String DEFAULT_SERVICE_NAME = "hytale-server";
    public static final String DEFAULT_PROTOCOL = "grpc";
    public static final int DEFAULT_QUEUE_MAX_SIZE = 16384;

    private final String endpoint;
    private final String serviceName;
    private final String protocol;
    private final int queueMaxSize;

    public BeaconConfig(String endpoint, String serviceName, String protocol, int queueMaxSize) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        if (queueMaxSize <= 0) {
            throw new IllegalArgumentException("queueMaxSize must be > 0, got " + queueMaxSize);
        }
        this.queueMaxSize = queueMaxSize;
    }

    public BeaconConfig(String endpoint, String serviceName, String protocol) {
        this(endpoint, serviceName, protocol, DEFAULT_QUEUE_MAX_SIZE);
    }

    public static BeaconConfig defaults() {
        return new BeaconConfig(DEFAULT_ENDPOINT, DEFAULT_SERVICE_NAME, DEFAULT_PROTOCOL, DEFAULT_QUEUE_MAX_SIZE);
    }

    public String endpoint() { return endpoint; }
    public String serviceName() { return serviceName; }
    public String protocol() { return protocol; }
    public int queueMaxSize() { return queueMaxSize; }

    @Override
    public String toString() {
        return "BeaconConfig{endpoint=" + endpoint
                + ", serviceName=" + serviceName
                + ", protocol=" + protocol
                + ", queueMaxSize=" + queueMaxSize + "}";
    }
}
