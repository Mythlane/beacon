package com.mythlane.beacon.binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import com.hypixel.hytale.event.IEventRegistry;
import com.mythlane.beacon.core.BeaconConfig;

import io.opentelemetry.api.OpenTelemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TelemetryBootstrapTest {

    private static TelemetryBootstrap bootstrap(Supplier_OT globalProbe,
                                                List<String> jvmArgs,
                                                IEventRegistry registry) {
        return new TelemetryBootstrap(
                globalProbe::get,
                false,
                () -> jvmArgs,
                () -> registry
        );
    }

    @FunctionalInterface
    interface Supplier_OT { OpenTelemetry get(); }

    @Test
    void buildReturnsRealSdkWhenConfigValid() {
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), mock(IEventRegistry.class));

        OpenTelemetry otel = b.build(BeaconConfig.defaults());

        assertThat(otel.getClass().getName())
                .doesNotContain("Noop")
                .doesNotContain("Default");
    }

    @Test
    void buildFallsBackToNoopWhenFactoryFails() {
        TelemetryBootstrap b = bootstrap(
                () -> { throw new RuntimeException("global probe boom"); },
                List.of("-Xmx4G"),
                mock(IEventRegistry.class));

        OpenTelemetry otel = b.build(BeaconConfig.defaults());

        assertThat(otel.getClass()).isEqualTo(OpenTelemetry.noop().getClass());
    }

    @Test
    void bindInstrumentsReturnsAllThreeHandles() {
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), mock(IEventRegistry.class));

        TelemetryBootstrap.InstrumentHandles handles = b.bindInstruments(OpenTelemetry.noop(), BeaconConfig.defaults());

        assertThat(handles.playerCountRegistry()).isNotNull();
        assertThat(handles.hytaleMetrics()).isNotNull();
        assertThat(handles.tpsMsptRecorder()).isNotNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bindInstrumentsRegistersEventsViaSupplier() {
        IEventRegistry registry = mock(IEventRegistry.class);
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), registry);

        b.bindInstruments(OpenTelemetry.noop(), BeaconConfig.defaults());

        verify(registry, atLeastOnce()).registerGlobal(any(Class.class), any(java.util.function.Consumer.class));
    }

    @Test
    void javaagentWarnTriggeredWhenAbsent() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G", "-XX:+UseG1GC"), mock(IEventRegistry.class));
            b.build(BeaconConfig.defaults());
        } finally {
            System.setErr(originalErr);
        }

        assertThat(captured.toString()).contains("JVM agent not attached");
    }
}
