package com.mythlane.beacon.bench;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import com.mythlane.beacon.instrum.HytaleMetrics;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class HytaleMetricsObserverBench {

    private InMemoryMetricReader reader;
    @SuppressWarnings("unused")
    private HytaleMetrics metrics;

    @Setup
    public void setup() {
        Map<UUID, HytaleMetrics.WorldSample> tpsSnap = new ConcurrentHashMap<>();
        Map<UUID, HytaleMetrics.WorldSample> playersSnap = new ConcurrentHashMap<>();
        UUID w = UUID.randomUUID();
        Attributes a = Attributes.builder()
                .put(AttributeKey.stringKey("hytale.world.uuid"), w.toString())
                .put(AttributeKey.stringKey("hytale.world.name"), "world-bench")
                .build();
        tpsSnap.put(w, new HytaleMetrics.WorldSample(a, 30L));
        playersSnap.put(w, new HytaleMetrics.WorldSample(a, 20L));

        reader = InMemoryMetricReader.create();
        SdkMeterProvider mp = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setMeterProvider(mp)
                .build();
        metrics = new HytaleMetrics(otel, () -> tpsSnap, () -> playersSnap);
    }

    @Benchmark
    public Collection<MetricData> collectAll() {
        return reader.collectAllMetrics();
    }
}
