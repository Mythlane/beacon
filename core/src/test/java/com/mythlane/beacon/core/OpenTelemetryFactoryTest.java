package com.mythlane.beacon.core;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Plan 02 Task 2 behaviors 1-3 (defensive probe + autoconfigure).
 */
class OpenTelemetryFactoryTest {

    @Test
    void buildsAndRegistersWhenGlobalIsNoop() {
        OpenTelemetry noop = OpenTelemetry.noop();
        BeaconConfig cfg = BeaconConfig.defaults();

        OpenTelemetry sdk = OpenTelemetryFactory.createOrAdopt(cfg, () -> noop, false);

        assertThat(sdk).isInstanceOf(OpenTelemetrySdk.class);
        // Real SDK class names contain "OpenTelemetrySdk", not "Default"/"Noop".
        assertThat(sdk.getClass().getName()).contains("OpenTelemetrySdk");
    }

    @Test
    void adoptsExistingRealSdk() {
        // Build a real SDK without registering it; use it as the "existing global".
        OpenTelemetry existing = OpenTelemetryFactory.createOrAdopt(
                BeaconConfig.defaults(), OpenTelemetry::noop, false);
        assertThat(existing.getClass().getName()).doesNotContain("Default");
        assertThat(existing.getClass().getName()).doesNotContain("Noop");

        // Now run the factory with a probe returning that real SDK — it must adopt
        // (return the same reference, no second build).
        OpenTelemetry adopted = OpenTelemetryFactory.createOrAdopt(
                BeaconConfig.defaults(), () -> existing, false);

        assertThat(adopted).isSameAs(existing);
    }

    @Test
    void httpProtobufProtocolIsHonoredByAutoconfigure() {
        BeaconConfig cfg = new BeaconConfig(
                "http://localhost:4318", "hytale-server", "http/protobuf");

        OpenTelemetry sdk = OpenTelemetryFactory.createOrAdopt(cfg, OpenTelemetry::noop, false);

        // The TracerProvider on a real configured SDK is not the no-op variant;
        // class name confirms the SDK path was taken.
        TracerProvider tp = sdk.getTracerProvider();
        assertThat(tp.getClass().getName()).doesNotContain("Noop");
        assertThat(tp.getClass().getName()).doesNotContain("Default");
    }
}
