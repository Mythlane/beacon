package com.mythlane.beacon.binding;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import com.mythlane.beacon.instrum.HytaleMetrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ShutdownCoordinatorTest {

    private static final class CountingSpanProcessor implements SpanProcessor {
        final AtomicInteger flushes = new AtomicInteger();
        @Override public void onStart(Context ctx, ReadWriteSpan span) {}
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(ReadableSpan span) {}
        @Override public boolean isEndRequired() { return false; }
        @Override public CompletableResultCode forceFlush() {
            flushes.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    private static OpenTelemetrySdk buildFastSdk(SpanProcessor proc) {
        SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(proc).build();
        SdkMeterProvider mp = SdkMeterProvider.builder().build();
        return OpenTelemetrySdk.builder().setTracerProvider(tp).setMeterProvider(mp).build();
    }

    @Test
    void shutdownIsIdempotent() {
        ShutdownCoordinator coord = new ShutdownCoordinator();
        HytaleMetrics metrics = mock(HytaleMetrics.class);
        CountingSpanProcessor proc = new CountingSpanProcessor();
        OpenTelemetrySdk sdk = buildFastSdk(proc);

        coord.shutdown(sdk, metrics, null, null);
        coord.shutdown(sdk, metrics, null, null);

        verify(metrics, times(1)).close();
        assertThat(proc.flushes.get()).isEqualTo(1);
    }

    @Test
    void shutdownContinuesWhenMetricsCloseThrows() {
        ShutdownCoordinator coord = new ShutdownCoordinator();
        HytaleMetrics metrics = mock(HytaleMetrics.class);
        doThrow(new RuntimeException("boom")).when(metrics).close();
        CountingSpanProcessor proc = new CountingSpanProcessor();
        OpenTelemetrySdk sdk = buildFastSdk(proc);

        coord.shutdown(sdk, metrics, null, null);

        assertThat(proc.flushes.get()).isEqualTo(1);
        assertThat(sdk.getSdkTracerProvider()).isNotNull();
    }

    /**
     * Wall-clock regression guard. Proves the worst-case shutdown is bounded
     * by the SHARED 5s deadline (tracer flush + meter flush combined), not
     * 5s per flush. The custom span processor and metric exporter both
     * return CompletableResultCode instances that never complete, so each
     * forceFlush().join(timeout) waits its full timeout. With shared
     * deadline → ~5000ms total. Without sharing (regression) → ~10000ms.
     * Asserts ≤ 5500ms (5s deadline + ~500ms slack for sdk.close +
     * removeShutdownHook). Test is intentionally slow (~5s); it is the
     * only proof of the deadline-sharing contract.
     */
    @Test
    void shutdownRespectsGlobalDeadline() {
        SpanProcessor neverFlushSpan = new SpanProcessor() {
            @Override public void onStart(Context ctx, ReadWriteSpan span) {}
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) {}
            @Override public boolean isEndRequired() { return false; }
            @Override public CompletableResultCode forceFlush() { return new CompletableResultCode(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        MetricExporter neverFlushExporter = new MetricExporter() {
            @Override public AggregationTemporality getAggregationTemporality(InstrumentType t) {
                return AggregationTemporality.CUMULATIVE;
            }
            @Override public CompletableResultCode export(Collection<MetricData> metrics) {
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return new CompletableResultCode(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };

        SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(neverFlushSpan).build();
        SdkMeterProvider mp = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(neverFlushExporter).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tp)
                .setMeterProvider(mp)
                .build();

        ShutdownCoordinator coord = new ShutdownCoordinator();
        long startNs = System.nanoTime();
        coord.shutdown(sdk, null, null, null);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertThat(elapsedMs).isLessThanOrEqualTo(5500L);
    }

    @Test
    void removeShutdownHookSwallowsIllegalStateException() {
        ShutdownCoordinator coord = new ShutdownCoordinator();
        Thread unregistered = new Thread(() -> {}, "test-not-registered");

        coord.shutdown(OpenTelemetry.noop(), null, null, unregistered);
    }
}
