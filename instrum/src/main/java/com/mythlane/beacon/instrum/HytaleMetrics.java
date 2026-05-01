package com.mythlane.beacon.instrum;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;

/**
 * Builds {@code hytale.mspt} (histogram, ms), {@code hytale.tps} (observable
 * gauge, 1/s) and {@code hytale.players.online} (observable gauge). Gauge
 * callbacks run on the OTel reader thread and must stay allocation-free and
 * thread-safe; data sources are {@link java.util.concurrent.ConcurrentHashMap}
 * snapshots. Cardinality is enforced upstream by {@link CardinalityGuard} so
 * only {@code hytale.world.uuid} and {@code hytale.world.name} attributes are
 * emitted.
 */
public final class HytaleMetrics implements AutoCloseable {

    public static final String SCOPE_NAME = "com.mythlane.beacon.instrum";

    private final DoubleHistogram msptHistogram;
    private final ObservableLongGauge tpsGauge;
    private final ObservableLongGauge playersGauge;

    public HytaleMetrics(OpenTelemetry openTelemetry,
                         Supplier<Map<UUID, WorldSample>> tpsSnapshotProvider,
                         Supplier<Map<UUID, WorldSample>> playerSnapshotProvider) {
        Meter meter = openTelemetry.meterBuilder(SCOPE_NAME).build();

        this.msptHistogram = meter.histogramBuilder("hytale.mspt")
                .setUnit("ms")
                .setDescription("Milliseconds-per-tick of the world tick loop.")
                .build();

        this.tpsGauge = meter.gaugeBuilder("hytale.tps")
                .setUnit("1/s")
                .setDescription("Ticks-per-second observed for each world (clamped to the 30 TPS ceiling).")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Map<UUID, WorldSample> snap = tpsSnapshotProvider.get();
                    if (snap == null) return;
                    for (WorldSample s : snap.values()) {
                        measurement.record(s.value(), s.attributes());
                    }
                });

        this.playersGauge = meter.gaugeBuilder("hytale.players.online")
                .setUnit("1")
                .setDescription("Number of players currently online per world.")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    Map<UUID, WorldSample> snap = playerSnapshotProvider.get();
                    if (snap == null) return;
                    for (WorldSample s : snap.values()) {
                        measurement.record(s.value(), s.attributes());
                    }
                });
    }

    public void recordMspt(double millis, Attributes attributes) {
        msptHistogram.record(millis, attributes);
    }

    @Override
    public void close() {
        try { tpsGauge.close(); } catch (Throwable ignored) {}
        try { playersGauge.close(); } catch (Throwable ignored) {}
    }

    public record WorldSample(Attributes attributes, long value) {}
}
