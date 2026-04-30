package com.mythlane.beacon.binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hypixel.hytale.event.IEventRegistry;
import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.instrum.HytaleMetrics;
import com.mythlane.beacon.instrum.PlayerCountRegistry;
import com.mythlane.beacon.instrum.TpsMsptRecorder;

import io.opentelemetry.api.OpenTelemetry;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void bindInstrumentsSurvivesMeterBuilderFailure() {
        OpenTelemetry brokenOtel = mock(OpenTelemetry.class);
        when(brokenOtel.meterBuilder(anyString())).thenThrow(new RuntimeException("meter boom"));
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), mock(IEventRegistry.class));

        TelemetryBootstrap.InstrumentHandles handles = b.bindInstruments(brokenOtel, BeaconConfig.defaults());

        assertThat(handles.playerCountRegistry()).isNotNull();
        assertThat(handles.hytaleMetrics()).isNull();
        assertThat(handles.tpsMsptRecorder()).isNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bindInstrumentsSurvivesEventRegistryFailure() {
        IEventRegistry registry = mock(IEventRegistry.class);
        doThrow(new RuntimeException("registry boom"))
                .when(registry).registerGlobal(any(Class.class), any(java.util.function.Consumer.class));
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), registry);

        TelemetryBootstrap.InstrumentHandles handles = b.bindInstruments(OpenTelemetry.noop(), BeaconConfig.defaults());

        assertThat(handles.playerCountRegistry()).isNotNull();
        assertThat(handles.hytaleMetrics()).isNotNull();
    }

    @Test
    void bindInstrumentsSurvivesPlayerCountRegistryFailure() {
        TelemetryBootstrap b = bootstrap(OpenTelemetry::noop, List.of("-Xmx4G"), mock(IEventRegistry.class));

        try (MockedConstruction<PlayerCountRegistry> mc = Mockito.mockConstruction(
                PlayerCountRegistry.class,
                (m, ctx) -> { throw new RuntimeException("pcr boom"); })) {

            TelemetryBootstrap.InstrumentHandles handles = b.bindInstruments(OpenTelemetry.noop(), BeaconConfig.defaults());

            assertThat(handles.playerCountRegistry()).isNull();
            assertThat(handles.hytaleMetrics()).isNull();
            assertThat(handles.tpsMsptRecorder()).isNull();
        }
    }

    @Test
    void buildSurvivesJvmArgsSupplierFailure() {
        TelemetryBootstrap b = new TelemetryBootstrap(
                OpenTelemetry::noop,
                false,
                () -> { throw new RuntimeException("args boom"); },
                () -> mock(IEventRegistry.class));

        OpenTelemetry otel = b.build(BeaconConfig.defaults());

        assertThat(otel).isNotNull();
    }

    @Test
    void playerCountSnapshotReturnsWorldAttributesPerWorld() {
        PlayerCountRegistry pcr = new PlayerCountRegistry();
        UUID worldA = UUID.randomUUID();
        UUID worldB = UUID.randomUUID();
        pcr.playerJoined(worldA, UUID.randomUUID());
        pcr.playerJoined(worldA, UUID.randomUUID());
        pcr.playerJoined(worldB, UUID.randomUUID());

        HytaleMetrics hm = new HytaleMetrics(OpenTelemetry.noop(), Map::of, Map::of);
        TpsMsptRecorder tmr = new TpsMsptRecorder(hm, List::of, w -> null, w -> 0L);
        tmr.recordOnce(worldA, "world-a", 50_000_000L);
        tmr.recordOnce(worldB, "world-b", 50_000_000L);

        Map<UUID, HytaleMetrics.WorldSample> snap = TelemetryBootstrap.playerCountSnapshot(pcr, tmr);

        assertThat(snap).containsOnlyKeys(worldA, worldB);
        assertThat(snap.get(worldA).value()).isEqualTo(2L);
        assertThat(snap.get(worldB).value()).isEqualTo(1L);
        assertThat(snap.get(worldA).attributes().get(TpsMsptRecorder.ATTR_WORLD_NAME)).isEqualTo("world-a");
    }

    @Test
    void playerCountSnapshotReturnsEmptyWhenNullInputs() {
        assertThat(TelemetryBootstrap.playerCountSnapshot(null, null)).isEmpty();
        assertThat(TelemetryBootstrap.playerCountSnapshot(new PlayerCountRegistry(), null)).isEmpty();
    }

    @Test
    void javaagentWarnSilentWhenAgentArgPresent() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            TelemetryBootstrap b = bootstrap(
                    OpenTelemetry::noop,
                    java.util.Arrays.asList(null, "-javaagent:opentelemetry-javaagent.jar"),
                    mock(IEventRegistry.class));
            b.build(BeaconConfig.defaults());
        } finally {
            System.setErr(originalErr);
        }

        assertThat(captured.toString()).doesNotContain("JVM agent not attached");
    }

    @Test
    void playerCountSnapshotSkipsWorldsWithoutAttributes() {
        PlayerCountRegistry pcr = new PlayerCountRegistry();
        UUID tracked = UUID.randomUUID();
        UUID untracked = UUID.randomUUID();
        pcr.playerJoined(tracked, UUID.randomUUID());
        pcr.playerJoined(untracked, UUID.randomUUID());

        HytaleMetrics hm = new HytaleMetrics(OpenTelemetry.noop(), Map::of, Map::of);
        TpsMsptRecorder tmr = new TpsMsptRecorder(hm, List::of, w -> null, w -> 0L);
        tmr.recordOnce(tracked, "tracked", 50_000_000L);

        Map<UUID, HytaleMetrics.WorldSample> snap = TelemetryBootstrap.playerCountSnapshot(pcr, tmr);

        assertThat(snap).containsOnlyKeys(tracked);
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
