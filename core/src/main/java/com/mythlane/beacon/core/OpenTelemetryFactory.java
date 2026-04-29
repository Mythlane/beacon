package com.mythlane.beacon.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

/**
 * Builds or adopts the OpenTelemetry SDK using the defensive probe pattern
 * mandated by PITFALLS P1 / STACK.md §"Sentry-OTel Coexistence Strategy".
 *
 * <p>Probe rule: if {@code GlobalOpenTelemetry.get().getClass().getName()} contains
 * neither "Noop" nor "Default", a real SDK is already installed (Sentry agentless mode,
 * OTel agent, or another plugin) — adopt it. Otherwise build via AutoConfigure and
 * register globally.
 */
public final class OpenTelemetryFactory {

    private OpenTelemetryFactory() {}

    /**
     * Resolve an {@link OpenTelemetry} instance for Beacon.
     * Uses ambient {@link GlobalOpenTelemetry#get()} for probing.
     */
    public static OpenTelemetry createOrAdopt(BeaconConfig config) {
        return createOrAdopt(config, GlobalOpenTelemetry::get);
    }

    /**
     * Test-friendly entry point: caller supplies the global probe.
     *
     * @param config Beacon config (endpoint / service name / protocol)
     * @param globalProbe supplier returning the currently registered global OpenTelemetry
     *                    (or noop). Tests inject a Mockito mock here.
     */
    public static OpenTelemetry createOrAdopt(BeaconConfig config, Supplier<OpenTelemetry> globalProbe) {
        return createOrAdopt(config, globalProbe, true);
    }

    /**
     * Full-control entry point used by tests to opt out of {@code setResultAsGlobal()}.
     *
     * @param registerAsGlobal when {@code true}, the built SDK is also registered as
     *                        the JVM-wide global. Tests pass {@code false} to avoid
     *                        polluting {@link GlobalOpenTelemetry} (which is set-once).
     */
    public static OpenTelemetry createOrAdopt(BeaconConfig config,
                                              Supplier<OpenTelemetry> globalProbe,
                                              boolean registerAsGlobal) {
        try {
            OpenTelemetry global = globalProbe.get();
            if (global != null) {
                String className = global.getClass().getName();
                // Defensive probe (PITFALLS P1): adopt only real SDKs.
                if (!className.contains("Noop") && !className.contains("Default")) {
                    return global;
                }
            }

            Map<String, String> propMap = new HashMap<>();
            propMap.put("otel.exporter.otlp.endpoint", config.endpoint());
            propMap.put("otel.service.name", config.serviceName());
            propMap.put("otel.exporter.otlp.protocol", config.protocol());
            // Disable upstream noisy autoconfig log; explicit flush owned by BeaconPlugin.
            propMap.put("otel.metrics.exporter", "otlp");
            propMap.put("otel.traces.exporter", "otlp");
            propMap.put("otel.logs.exporter", "none");

            var builder = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(() -> propMap);
            if (registerAsGlobal) {
                builder = builder.setResultAsGlobal();
            }
            return builder.build().getOpenTelemetrySdk();
        } catch (RuntimeException e) {
            throw new BeaconInitException("Failed to construct OpenTelemetry SDK", e);
        }
    }

}
