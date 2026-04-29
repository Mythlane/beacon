package com.mythlane.beacon.agentext;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * OpenTelemetry Java Agent extension entry point. Registered via
 * {@link AutoService} so the agent's autoconfigure pipeline discovers it
 * through {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}.
 * Only adds Beacon distro resource attributes; JVM auto-instrumentation is
 * left to the standard OTel Java Agent.
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public final class BeaconExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addResourceCustomizer((resource, configProperties) ->
            BeaconResourceProvider.augment(resource));
    }
}
