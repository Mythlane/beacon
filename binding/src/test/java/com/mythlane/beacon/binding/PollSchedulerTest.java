package com.mythlane.beacon.binding;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollSchedulerTest {

    private ScheduledExecutorService externalExecutor;

    @AfterEach
    void cleanup() {
        if (externalExecutor != null && !externalExecutor.isShutdown()) {
            externalExecutor.shutdownNow();
        }
    }

    @Test
    void startSchedulesTaskAtFixedRate() throws InterruptedException {
        externalExecutor = Executors.newSingleThreadScheduledExecutor();
        PollScheduler s = new PollScheduler(() -> externalExecutor);
        CountDownLatch latch = new CountDownLatch(2);

        s.start(latch::countDown, Duration.ofMillis(50));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        s.cancel();
    }

    @Test
    void cancelStopsTask() throws InterruptedException {
        externalExecutor = Executors.newSingleThreadScheduledExecutor();
        PollScheduler s = new PollScheduler(() -> externalExecutor);
        AtomicInteger count = new AtomicInteger();

        s.start(count::incrementAndGet, Duration.ofMillis(50));
        Thread.sleep(200);
        s.cancel();
        int afterCancel = count.get();
        Thread.sleep(200);

        assertThat(count.get()).isEqualTo(afterCancel);
    }

    @Test
    void doubleStartThrowsIllegalStateException() {
        externalExecutor = Executors.newSingleThreadScheduledExecutor();
        PollScheduler s = new PollScheduler(() -> externalExecutor);
        s.start(() -> {}, Duration.ofSeconds(1));

        assertThatThrownBy(() -> s.start(() -> {}, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        s.cancel();
    }

    @Test
    void doubleCancelIsNoOp() {
        externalExecutor = Executors.newSingleThreadScheduledExecutor();
        PollScheduler s = new PollScheduler(() -> externalExecutor);

        s.cancel();
        s.start(() -> {}, Duration.ofSeconds(1));
        s.cancel();
        s.cancel();
    }

    @Test
    void externalExecutorIsNotShutdownOnCancel() {
        externalExecutor = Executors.newSingleThreadScheduledExecutor();
        PollScheduler s = new PollScheduler(() -> externalExecutor);

        s.start(() -> {}, Duration.ofSeconds(1));
        s.cancel();

        assertThat(externalExecutor.isShutdown()).isFalse();
    }
}
