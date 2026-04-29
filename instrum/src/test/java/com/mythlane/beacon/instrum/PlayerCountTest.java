package com.mythlane.beacon.instrum;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerCountTest {

    @Test
    void joinsAccumulate() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        r.playerJoined(world, UUID.randomUUID());
        r.playerJoined(world, UUID.randomUUID());
        assertThat(r.snapshot(world)).isEqualTo(2L);
    }

    @Test
    void singleLeaveDecrements() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        r.playerJoined(world, p1);
        r.playerJoined(world, p2);
        r.playerLeft(world, p1);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }

    @Test
    void doubleLeaveIsIdempotent() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        r.playerJoined(world, p1);
        r.playerJoined(world, p2);
        r.playerLeft(world, p1);
        r.playerLeft(world, p1);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }

    @Test
    void doubleJoinCountsOnce() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        r.playerJoined(world, p1);
        r.playerJoined(world, p1);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }

    @Test
    void concurrentJoinLeaveStaysConsistent() throws Exception {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        int threads = 8;
        int opsPerThread = 1000;
        UUID[] players = new UUID[threads];
        for (int i = 0; i < threads; i++) players[i] = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(threads);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final UUID p = players[i];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int op = 0; op < opsPerThread; op++) {
                        r.playerJoined(world, p);
                        r.playerLeft(world, p);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    remaining.decrementAndGet();
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(r.snapshot(world)).isEqualTo(0L);
    }

    @Test
    void worldRemovedZeroesSnapshot() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        r.playerJoined(world, UUID.randomUUID());
        r.playerJoined(world, UUID.randomUUID());
        assertThat(r.snapshot(world)).isEqualTo(2L);
        r.worldRemoved(world);
        assertThat(r.snapshot(world)).isEqualTo(0L);
    }
}
