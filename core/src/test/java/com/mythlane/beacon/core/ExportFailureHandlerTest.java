package com.mythlane.beacon.core;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Plan 02 Task 2 behaviors 4-6 (bounded queue + circuit breaker + counter).
 */
class ExportFailureHandlerTest {

    @Test
    void queueOverflowDropsAndIncrementsCounter() {
        ExportFailureHandler h = new ExportFailureHandler();
        // Fill to capacity.
        for (int i = 0; i < 16384; i++) {
            assertThat(h.submit()).as("submit #%d", i).isTrue();
        }
        // 16385th must drop.
        assertThat(h.submit()).isFalse();
        assertThat(h.droppedTotal()).isEqualTo(1L);
    }

    @Test
    void breakerOpensAfterFiveFailuresAndDropsSubsequentSubmits() {
        AtomicLong now = new AtomicLong(0L);
        ExportFailureHandler h = new ExportFailureHandler(8, now::get);

        // Each submit -> failure cycle increments consecutive failures.
        for (int i = 0; i < 5; i++) {
            assertThat(h.submit()).isTrue();
            h.onFailure();
        }
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.OPEN);

        // Subsequent submits drop while breaker is open.
        long droppedBefore = h.droppedTotal();
        assertThat(h.submit()).isFalse();
        assertThat(h.submit()).isFalse();
        assertThat(h.droppedTotal()).isEqualTo(droppedBefore + 2);
    }

    @Test
    void breakerHalfOpensThenClosesOnSuccess() {
        AtomicLong now = new AtomicLong(0L);
        ExportFailureHandler h = new ExportFailureHandler(8, now::get);

        for (int i = 0; i < 5; i++) {
            assertThat(h.submit()).isTrue();
            h.onFailure();
        }
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.OPEN);

        // Advance the clock past 60 s.
        now.set(ExportFailureHandler.BREAKER_OPEN_NANOS + 1L);

        // Submitting probes the breaker → half-open.
        assertThat(h.submit()).isTrue();
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.HALF_OPEN);

        // A success closes the breaker.
        h.onSuccess();
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.CLOSED);
    }
}
