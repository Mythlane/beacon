package com.mythlane.beacon.bench;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.OpenTelemetry;

import com.mythlane.beacon.instrum.HytaleMetrics;
import com.mythlane.beacon.instrum.TpsMsptRecorder;

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
public class TpsMsptRecorderBench {

    private TpsMsptRecorder recorder;
    private UUID worldUuid;

    @Setup
    public void setup() {
        OpenTelemetry otel = OpenTelemetry.noop();
        HytaleMetrics metrics = new HytaleMetrics(otel,
                ConcurrentHashMap::new, ConcurrentHashMap::new);
        recorder = new TpsMsptRecorder(metrics, null, null, null);
        worldUuid = UUID.randomUUID();
        recorder.recordOnce(worldUuid, "world-bench", 33_333_333L);
    }

    @Benchmark
    public void recordOnce() {
        recorder.recordOnce(worldUuid, "world-bench", 33_333_333L);
    }
}
