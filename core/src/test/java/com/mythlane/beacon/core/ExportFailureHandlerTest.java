package com.mythlane.beacon.core;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFailureHandlerTest {

    @Test
    void queueOverflowDropsAndIncrementsCounter() {
        ExportFailureHandler h = new ExportFailureHandler();
        for (int i = 0; i < 16384; i++) {
            assertThat(h.submit()).as("submit #%d", i).isTrue();
        }
        assertThat(h.submit()).isFalse();
        assertThat(h.droppedTotal()).isEqualTo(1L);
    }

    @Test
    void breakerOpensAfterFiveFailuresAndDropsSubsequentSubmits() {
        AtomicLong now = new AtomicLong(0L);
        ExportFailureHandler h = new ExportFailureHandler(8, now::get);

        for (int i = 0; i < 5; i++) {
            assertThat(h.submit()).isTrue();
            h.onFailure();
        }
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.OPEN);

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

        now.set(ExportFailureHandler.BREAKER_OPEN_NANOS + 1L);

        assertThat(h.submit()).isTrue();
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.HALF_OPEN);

        h.onSuccess();
        assertThat(h.breakerState()).isEqualTo(ExportFailureHandler.BreakerState.CLOSED);
    }
}
