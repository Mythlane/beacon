package com.mythlane.beacon.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryFactoryTest {

    @Test
    void buildsAndRegistersWhenGlobalIsNoop() {
        OpenTelemetry noop = OpenTelemetry.noop();
        BeaconConfig cfg = BeaconConfig.defaults();

        OpenTelemetry sdk = OpenTelemetryFactory.createOrAdopt(cfg, () -> noop, false);

        assertThat(sdk).isInstanceOf(OpenTelemetrySdk.class);
        assertThat(sdk.getClass().getName()).contains("OpenTelemetrySdk");
    }

    @Test
    void adoptsExistingRealSdk() {
        OpenTelemetry existing = OpenTelemetryFactory.createOrAdopt(
                BeaconConfig.defaults(), OpenTelemetry::noop, false);
        assertThat(existing.getClass().getName()).doesNotContain("Default");
        assertThat(existing.getClass().getName()).doesNotContain("Noop");

        OpenTelemetry adopted = OpenTelemetryFactory.createOrAdopt(
                BeaconConfig.defaults(), () -> existing, false);

        assertThat(adopted).isSameAs(existing);
    }

    @Test
    void httpProtobufProtocolIsHonoredByAutoconfigure() {
        BeaconConfig cfg = new BeaconConfig(
                "http://localhost:4318", "hytale-server", "http/protobuf");

        OpenTelemetry sdk = OpenTelemetryFactory.createOrAdopt(cfg, OpenTelemetry::noop, false);

        TracerProvider tp = sdk.getTracerProvider();
        assertThat(tp.getClass().getName()).doesNotContain("Noop");
        assertThat(tp.getClass().getName()).doesNotContain("Default");
    }
}
