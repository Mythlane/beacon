package com.mythlane.beacon.instrum;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;

/**
 * Builds Beacon's three Hytale-native instruments (FND-04, FND-05):
 *
 * <ul>
 *   <li>{@code hytale.mspt} — {@link LongHistogram}, unit "ns", recorded by
 *       {@link TpsMsptRecorder} on each polling tick.</li>
 *   <li>{@code hytale.tps} — {@link ObservableLongGauge}, unit "1/s",
 *       callback reads the latest cached TPS per world.</li>
 *   <li>{@code hytale.players.online} — {@link ObservableLongGauge}, unit "1",
 *       callback reads {@link PlayerCountRegistry#snapshot(UUID)}.</li>
 * </ul>
 *
 * <p>The two gauges run on the OTel reader thread (T-1-04-02 boundary).
 * Their callbacks must be allocation-free and thread-safe — both data sources
 * here use snapshots backed by {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>Cardinality safety (R9 / T-1-04-01) is enforced upstream by
 * {@link CardinalityGuard} on the {@link io.opentelemetry.sdk.metrics.SdkMeterProvider};
 * this class only emits {@code hytale.world.uuid} + {@code hytale.world.name}
 * attributes.
 */
public final class HytaleMetrics implements AutoCloseable {

    /** Instrumentation scope name — appears in OTel resource attributes. */
    public static final String SCOPE_NAME = "com.mythlane.beacon.instrum";

    private final LongHistogram msptHistogram;
    private final ObservableLongGauge tpsGauge;
    private final ObservableLongGauge playersGauge;

    /**
     * @param openTelemetry OTel root for instrument registration.
     * @param tpsSnapshotProvider supplies the current per-world (Attributes, latest TPS) map.
     *                            The recorder feeds this map.
     * @param playerSnapshotProvider supplies the current per-world (Attributes, online count) map.
     */
    public HytaleMetrics(OpenTelemetry openTelemetry,
                         Supplier<Map<UUID, WorldSample>> tpsSnapshotProvider,
                         Supplier<Map<UUID, WorldSample>> playerSnapshotProvider) {
        Meter meter = openTelemetry.meterBuilder(SCOPE_NAME).build();

        this.msptHistogram = meter.histogramBuilder("hytale.mspt")
                .setUnit("ns")
                .setDescription("Milliseconds-per-tick of the world tick loop, in nanoseconds.")
                .ofLongs()
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

    /** Records one MSPT data point against {@code hytale.mspt}. */
    public void recordMspt(long nanos, Attributes attributes) {
        msptHistogram.record(nanos, attributes);
    }

    @Override
    public void close() {
        try { tpsGauge.close(); } catch (Throwable ignored) {}
        try { playersGauge.close(); } catch (Throwable ignored) {}
    }

    /** Pair of pre-built {@link Attributes} and a current value, used by gauge callbacks. */
    public record WorldSample(Attributes attributes, long value) {}
}
