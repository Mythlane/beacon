package com.mythlane.beacon.binding;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.mythlane.beacon.instrum.HytaleMetrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ShutdownCoordinator {

    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownCoordinator.class);

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LongSupplier nanoTimeSource;

    ShutdownCoordinator() {
        this(System::nanoTime);
    }

    ShutdownCoordinator(LongSupplier nanoTimeSource) {
        this.nanoTimeSource = nanoTimeSource;
    }

    Thread buildFlushHook(Supplier<OpenTelemetry> otelSupplier) {
        Runnable r = () -> {
            OpenTelemetry otel = otelSupplier.get();
            long deadlineNanos = nanoTimeSource.getAsLong() + SHUTDOWN_TIMEOUT.toNanos();
            flushBoth(otel, deadlineNanos);
        };
        Thread t = new Thread(r, "beacon-shutdown-flush");
        t.setDaemon(true);
        return t;
    }

    void shutdown(OpenTelemetry otel,
                  HytaleMetrics metrics,
                  PollScheduler poller,
                  Thread hookToRemove) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (poller != null) {
            poller.cancel();
        }
        if (metrics != null) {
            try {
                metrics.close();
            } catch (Throwable t) {
                LOG.warn("ShutdownCoordinator metrics close failed", t);
            }
        }
        long deadlineNanos = nanoTimeSource.getAsLong() + SHUTDOWN_TIMEOUT.toNanos();
        flushBoth(otel, deadlineNanos);
        if (otel instanceof OpenTelemetrySdk sdk) {
            try {
                sdk.close();
            } catch (Throwable t) {
                LOG.warn("ShutdownCoordinator sdk close failed", t);
            }
        }
        if (hookToRemove != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hookToRemove);
            } catch (IllegalStateException ignored) {
                // JVM is shutting down — hook removal is implicitly handled. Not an error.
            }
        }
    }

    private void flushBoth(OpenTelemetry otel, long deadlineNanos) {
        if (!(otel instanceof OpenTelemetrySdk sdk)) {
            return;
        }
        long t1 = remainingMillis(deadlineNanos);
        try {
            sdk.getSdkTracerProvider().forceFlush().join(t1, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOG.warn("ShutdownCoordinator tracer flush failed", t);
        }
        long t2 = remainingMillis(deadlineNanos);
        try {
            sdk.getSdkMeterProvider().forceFlush().join(t2, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOG.warn("ShutdownCoordinator meter flush failed", t);
        }
    }

    private long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - nanoTimeSource.getAsLong();
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
