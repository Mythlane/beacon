package com.mythlane.beacon.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;

/**
 * Builds or adopts the OpenTelemetry SDK using a defensive probe of the global
 * registration so Beacon coexists with Sentry's bundled SDK without double
 * registering exporters. If {@code GlobalOpenTelemetry.get()} is anything other
 * than a Noop/Default placeholder, that instance is adopted; otherwise the SDK
 * is built via AutoConfigure.
 */
public final class OpenTelemetryFactory {

    private OpenTelemetryFactory() {}

    public static OpenTelemetry createOrAdopt(BeaconConfig config,
                                              Supplier<OpenTelemetry> globalProbe,
                                              boolean registerAsGlobal) {
        return createOrAdopt(config, globalProbe, registerAsGlobal, b -> b);
    }

    public static OpenTelemetry createOrAdopt(BeaconConfig config,
                                              Supplier<OpenTelemetry> globalProbe,
                                              boolean registerAsGlobal,
                                              UnaryOperator<SdkMeterProviderBuilder> meterProviderCustomizer) {
        if (System.getProperty("otel.java.global-autoconfigure.enabled") == null) {
            System.setProperty("otel.java.global-autoconfigure.enabled", "true");
        }
        OpenTelemetry global = globalProbe.get();
        if (global != null) {
            String className = global.getClass().getName();
            if (!className.contains("Noop") && !className.contains("Default")) {
                return global;
            }
        }

        Map<String, String> propMap = new HashMap<>();
        propMap.put("otel.exporter.otlp.endpoint", config.endpoint());
        propMap.put("otel.service.name", config.serviceName());
        propMap.put("otel.exporter.otlp.protocol", config.protocol());
        propMap.put("otel.metrics.exporter", "otlp");
        propMap.put("otel.traces.exporter", "otlp");
        propMap.put("otel.logs.exporter", "none");

        var builder = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> propMap)
                .addMeterProviderCustomizer((mpb, props) -> meterProviderCustomizer.apply(mpb));
        if (registerAsGlobal) {
            builder = builder.setResultAsGlobal();
        }
        return builder.build().getOpenTelemetrySdk();
    }
}
