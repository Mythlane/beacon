package com.mythlane.beacon.bench;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.mythlane.beacon.instrum.PlayerCountRegistry;

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
public class PlayerCountRegistryBench {

    private PlayerCountRegistry registry;
    private UUID worldUuid;
    private UUID[] players;
    private int idx;

    @Setup
    public void setup() {
        registry = new PlayerCountRegistry();
        worldUuid = UUID.randomUUID();
        players = new UUID[100];
        for (int i = 0; i < 100; i++) {
            players[i] = UUID.randomUUID();
        }
    }

    @Benchmark
    public void joinThenLeave() {
        UUID p = players[idx++ % 100];
        registry.playerJoined(worldUuid, p);
        registry.playerLeft(worldUuid, p);
    }
}
