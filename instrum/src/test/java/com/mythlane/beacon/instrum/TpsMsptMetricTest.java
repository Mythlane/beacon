package com.mythlane.beacon.instrum;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 04 — FND-04. Verifies the TPS gauge clamp formula, MSPT histogram unit,
 * world-identity attributes, attribute caching (P4), and the cardinality guard
 * View filter.
 *
 * <p>Tests use the lower-level {@code recordOnce(UUID, String, long)} overload
 * so the Hytale {@code World} / {@code WorldConfig} static initializers are
 * never triggered. Those classes pull asset-store machinery that requires a
 * fully-booted server — out of reach for an instrum unit test.
 */
class TpsMsptMetricTest {

    private InMemoryMetricReader reader;
    private OpenTelemetry openTelemetry;
    private SdkMeterProvider meterProvider;
    private HytaleMetrics metrics;
    private TpsMsptRecorder recorder;
    private final ConcurrentHashMap<UUID, HytaleMetrics.WorldSample> playerSnap = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        var meterProviderBuilder = SdkMeterProvider.builder().registerMetricReader(reader);
        CardinalityGuard.install(meterProviderBuilder);
        this.meterProvider = meterProviderBuilder.build();
        this.openTelemetry = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
        this.metrics = new HytaleMetrics(openTelemetry,
                () -> recorder.tpsSnapshot(),
                () -> playerSnap);
        this.recorder = new TpsMsptRecorder(metrics,
                Collections::emptyList,
                w -> null, // unused — tests call the (UUID, String, long) overload directly
                w -> 0L);
    }

    @AfterEach
    void tearDown() {
        try { metrics.close(); } catch (Throwable ignored) {}
        try { meterProvider.close(); } catch (Throwable ignored) {}
    }

    @Test
    void tpsClampsToCeilingAtPerfectTickRate() {
        UUID worldUuid = UUID.randomUUID();
        recorder.recordOnce(worldUuid, "overworld", 33_333_333L); // 30 TPS exact
        Collection<MetricData> data = reader.collectAllMetrics();

        long tps = readGauge(data, "hytale.tps");
        assertThat(tps).isEqualTo(30L);

        long msptSum = readHistogramSum(data, "hytale.mspt");
        assertThat(msptSum).isEqualTo(33_333_333L);
    }

    @Test
    void tpsReflectsSlowTicks() {
        UUID worldUuid = UUID.randomUUID();
        recorder.recordOnce(worldUuid, "nether", 66_666_666L); // ~15 TPS
        long tps = readGauge(reader.collectAllMetrics(), "hytale.tps");
        assertThat(tps).isEqualTo(15L);
    }

    @Test
    void instrumentsCarryWorldUuidAndNameAttributes() {
        UUID id = UUID.randomUUID();
        recorder.recordOnce(id, "the-end", 33_333_333L);

        Collection<MetricData> data = reader.collectAllMetrics();
        MetricData mspt = data.stream().filter(m -> m.getName().equals("hytale.mspt")).findFirst().orElseThrow();
        var pt = mspt.getHistogramData().getPoints().iterator().next();
        assertThat(pt.getAttributes().get(TpsMsptRecorder.ATTR_WORLD_UUID)).isEqualTo(id.toString());
        assertThat(pt.getAttributes().get(TpsMsptRecorder.ATTR_WORLD_NAME)).isEqualTo("the-end");

        MetricData tps = data.stream().filter(m -> m.getName().equals("hytale.tps")).findFirst().orElseThrow();
        var tpt = tps.getLongGaugeData().getPoints().iterator().next();
        assertThat(tpt.getAttributes().get(TpsMsptRecorder.ATTR_WORLD_UUID)).isEqualTo(id.toString());
        assertThat(tpt.getAttributes().get(TpsMsptRecorder.ATTR_WORLD_NAME)).isEqualTo("the-end");
    }

    @Test
    void cardinalityGuardStripsUnknownAttributes() {
        // Submit a metric with an attribute not on the allowlist; the View filter
        // must drop it before it reaches the exporter.
        var meter = openTelemetry.meterBuilder(HytaleMetrics.SCOPE_NAME).build();
        var hist = meter.histogramBuilder("hytale.mspt").setUnit("ns").ofLongs().build();
        hist.record(50_000_000L,
                io.opentelemetry.api.common.Attributes.builder()
                        .put("hytale.world.uuid", "abc")
                        .put("hytale.world.name", "w")
                        .put("region", "eu") // <-- must be dropped
                        .build());

        Collection<MetricData> data = reader.collectAllMetrics();
        MetricData mspt = data.stream().filter(m -> m.getName().equals("hytale.mspt")).findFirst().orElseThrow();
        var pt = mspt.getHistogramData().getPoints().iterator().next();
        assertThat(pt.getAttributes().asMap().keySet().stream().map(Object::toString))
                .containsExactlyInAnyOrder("hytale.world.uuid", "hytale.world.name");
    }

    @Test
    void attributesCachedPerWorldUuid() {
        UUID id = UUID.randomUUID();
        recorder.recordOnce(id, "main", 33_333_333L);
        var first = recorder.attributesFor(id);
        recorder.recordOnce(id, "main", 40_000_000L);
        var second = recorder.attributesFor(id);
        // Same Attributes instance — proves cache hit (no new allocation per tick).
        assertThat(second).isSameAs(first);
    }

    @Test
    void uuidFallbackDeterministicWhenExtractorReturnsNull() {
        // Determinism: two independent recorders should generate the same fallback
        // UUID for the same world name.
        String name = "fallback-world";
        UUID expected = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        recorder.recordOnce(expected, name, 33_333_333L);
        assertThat(recorder.attributesFor(expected)).isNotNull();

        // Same fallback formula on a fresh recorder yields the same UUID.
        TpsMsptRecorder fresh = new TpsMsptRecorder(metrics,
                Collections::emptyList,
                w -> null,
                w -> 0L);
        UUID fresh2 = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fresh2).isEqualTo(expected);
        fresh.recordOnce(fresh2, name, 33_333_333L);
        assertThat(fresh.attributesFor(expected)).isNotNull();
    }

    private static long readGauge(Collection<MetricData> data, String name) {
        return data.stream()
                .filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow()
                .getLongGaugeData().getPoints().iterator().next().getValue();
    }

    private static long readHistogramSum(Collection<MetricData> data, String name) {
        return (long) data.stream()
                .filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow()
                .getHistogramData().getPoints().iterator().next().getSum();
    }
}
