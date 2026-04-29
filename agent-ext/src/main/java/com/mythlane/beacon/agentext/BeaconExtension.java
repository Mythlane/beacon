package com.mythlane.beacon.agentext;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Beacon's OpenTelemetry Java Agent extension entry point.
 *
 * <p>Registered via {@link AutoService} so the agent's autoconfigure pipeline discovers it
 * through the {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 * service file generated at compile time.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Augment the resource with Beacon-distro identification attributes.</li>
 *   <li>Honor {@code BEACON_DEPLOYMENT_ENVIRONMENT} env var when present.</li>
 * </ul>
 *
 * <p>JVM auto-instrumentation (process.runtime.jvm.*, gc.*, threads.count, classes.loaded,
 * process.cpu.utilization) is provided by the standard OTel Java Agent — this extension does
 * NOT re-implement those metrics.
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public final class BeaconExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addResourceCustomizer((resource, configProperties) ->
            BeaconResourceProvider.augment(resource));
    }
}
