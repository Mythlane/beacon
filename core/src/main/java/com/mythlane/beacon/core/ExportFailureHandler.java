package com.mythlane.beacon.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded queue + circuit breaker shared across Beacon exporters (D-08, D-10, PITFALLS P8).
 *
 * <p>Capacity defaults to {@link BeaconConfig#queueMaxSize()} (16384). When 5 consecutive
 * export failures occur the breaker opens, subsequent {@link #submit()} calls drop, and
 * a WARN log is emitted at most once every 60 seconds. After 60 s the breaker half-opens
 * and one success closes it again.
 *
 * <p>The {@code beacon.export.dropped_total} counter (D-10) is registered against the
 * shared OpenTelemetry meter so admins still see drops once the backend recovers.
 */
public final class ExportFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExportFailureHandler.class);

    /** Failures in a row required before the breaker opens. */
    public static final int FAILURE_THRESHOLD = 5;
    /** Breaker open window before half-open probe (60 s in nanoseconds). */
    public static final long BREAKER_OPEN_NANOS = 60_000_000_000L;
    /** Minimum interval between WARN logs while breaker is open (60 s). */
    public static final long WARN_THROTTLE_NANOS = 60_000_000_000L;

    /** Public default queue capacity (D-08). */
    public static final int DEFAULT_QUEUE_MAX_SIZE = 16384;

    /** Counter instrument name (D-10). */
    public static final String DROPPED_COUNTER_NAME = "beacon.export.dropped_total";

    /** Circuit breaker states. */
    public enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    private final int capacity;
    private final LongSupplier clock;

    private final AtomicLong inFlight = new AtomicLong(0);
    private final AtomicLong droppedTotal = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private final AtomicReference<BreakerState> state = new AtomicReference<>(BreakerState.CLOSED);
    private final AtomicLong breakerOpenedAt = new AtomicLong(0);
    private final AtomicLong lastWarnNanos = new AtomicLong(Long.MIN_VALUE);

    private final AtomicReference<LongCounter> droppedCounter = new AtomicReference<>(null);

    public ExportFailureHandler() {
        this(DEFAULT_QUEUE_MAX_SIZE, System::nanoTime);
    }

    public ExportFailureHandler(int capacity, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    /**
     * Bind to a live {@link OpenTelemetry} so the dropped counter is exported.
     * Idempotent — second call replaces the counter binding.
     */
    public void bindMeter(OpenTelemetry openTelemetry) {
        if (openTelemetry == null) return;
        Meter meter = openTelemetry.getMeter("com.mythlane.beacon.export");
        LongCounter counter = meter.counterBuilder(DROPPED_COUNTER_NAME)
                .setDescription("Telemetry items dropped by Beacon (queue full or circuit breaker open).")
                .setUnit("{item}")
                .build();
        droppedCounter.set(counter);
    }

    /**
     * Attempt to enqueue an outbound telemetry item.
     *
     * @return {@code true} if accepted; {@code false} if dropped (queue full or breaker open).
     */
    public boolean submit() {
        // Re-probe breaker for half-open transition.
        maybeHalfOpen();

        if (state.get() == BreakerState.OPEN) {
            recordDrop();
            maybeWarn("circuit breaker open — dropping export");
            return false;
        }
        long current = inFlight.get();
        if (current >= capacity) {
            recordDrop();
            return false;
        }
        if (!inFlight.compareAndSet(current, current + 1)) {
            // Lost the race; treat as drop to keep semantics simple.
            recordDrop();
            return false;
        }
        return true;
    }

    /** Notify that an in-flight item completed successfully. */
    public void onSuccess() {
        inFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
        consecutiveFailures.set(0);
        // HALF_OPEN → CLOSED on a single success.
        state.compareAndSet(BreakerState.HALF_OPEN, BreakerState.CLOSED);
        // OPEN → CLOSED if for some reason we observe success while open
        // (shouldn't happen often, but defensive).
        state.compareAndSet(BreakerState.OPEN, BreakerState.CLOSED);
    }

    /** Notify that an in-flight item failed to export. */
    public void onFailure() {
        inFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
        long failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            if (state.compareAndSet(BreakerState.CLOSED, BreakerState.OPEN)
                    || state.compareAndSet(BreakerState.HALF_OPEN, BreakerState.OPEN)) {
                breakerOpenedAt.set(clock.getAsLong());
                maybeWarn("circuit breaker tripped after " + failures + " consecutive failures");
            }
        }
    }

    private void maybeHalfOpen() {
        if (state.get() != BreakerState.OPEN) return;
        long now = clock.getAsLong();
        long openedAt = breakerOpenedAt.get();
        if (now - openedAt >= BREAKER_OPEN_NANOS) {
            state.compareAndSet(BreakerState.OPEN, BreakerState.HALF_OPEN);
        }
    }

    private void recordDrop() {
        droppedTotal.incrementAndGet();
        LongCounter counter = droppedCounter.get();
        if (counter != null) counter.add(1);
    }

    private void maybeWarn(String message) {
        long now = clock.getAsLong();
        long last = lastWarnNanos.get();
        if (last == Long.MIN_VALUE || now - last >= WARN_THROTTLE_NANOS) {
            if (lastWarnNanos.compareAndSet(last, now)) {
                LOG.warn("Beacon export: {}", message);
            }
        }
    }

    public long droppedTotal() { return droppedTotal.get(); }
    public BreakerState breakerState() { return state.get(); }
    public int capacity() { return capacity; }
    public long inFlight() { return inFlight.get(); }
}
