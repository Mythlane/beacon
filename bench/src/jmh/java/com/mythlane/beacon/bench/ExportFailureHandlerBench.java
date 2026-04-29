package com.mythlane.beacon.bench;

import java.util.concurrent.TimeUnit;

import com.mythlane.beacon.core.ExportFailureHandler;

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
public class ExportFailureHandlerBench {

    private ExportFailureHandler okHandler;
    private ExportFailureHandler failHandler;

    @Setup
    public void setup() {
        okHandler = new ExportFailureHandler(1024, () -> 0L);
        failHandler = new ExportFailureHandler(1024, () -> 0L);
    }

    @Benchmark
    public boolean submitWithSuccess() {
        boolean accepted = okHandler.submit();
        if (accepted) okHandler.onSuccess();
        return accepted;
    }

    @Benchmark
    public boolean submitWithFailure() {
        boolean accepted = failHandler.submit();
        if (accepted) failHandler.onFailure();
        return accepted;
    }
}
