package com.mythlane.beacon.instrum;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

/**
 * Polls each {@link World} on a fixed cadence, reads
 * {@code world.getBufferedTickLengthMetricSet().getLastValue()} (nanoseconds),
 * records it into the {@code hytale.mspt} histogram, derives
 * {@code tps = min(30, 1e9 / lastTickNanos)} and stores it in a per-world
 * snapshot driving the {@code hytale.tps} gauge callback. Per-world
 * {@link Attributes} are cached so polling does not allocate per cycle.
 */
public final class TpsMsptRecorder {

    public static final long TPS_CEILING = 30L;

    public static final AttributeKey<String> ATTR_WORLD_UUID =
            AttributeKey.stringKey("hytale.world.uuid");
    public static final AttributeKey<String> ATTR_WORLD_NAME =
            AttributeKey.stringKey("hytale.world.name");

    private final HytaleMetrics metrics;
    private final Supplier<Collection<World>> worldSource;
    private final UuidExtractor uuidExtractor;
    private final Function<World, Long> tickNanosExtractor;

    private final ConcurrentHashMap<UUID, Attributes> attrCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, HytaleMetrics.WorldSample> tpsByWorld = new ConcurrentHashMap<>();

    public TpsMsptRecorder(HytaleMetrics metrics,
                           Supplier<Collection<World>> worldSource,
                           UuidExtractor uuidExtractor,
                           Function<World, Long> tickNanosExtractor) {
        this.metrics = metrics;
        this.worldSource = worldSource;
        this.uuidExtractor = uuidExtractor;
        this.tickNanosExtractor = tickNanosExtractor;
    }

    public static TpsMsptRecorder forProduction(HytaleMetrics metrics) {
        return new TpsMsptRecorder(
                metrics,
                () -> Universe.get().getWorlds().values(),
                world -> {
                    try {
                        return world.getWorldConfig().getUuid();
                    } catch (Throwable t) {
                        return null;
                    }
                },
                world -> {
                    HistoricMetric hm = world.getBufferedTickLengthMetricSet();
                    return hm == null ? 0L : hm.getLastValue();
                });
    }

    public void recordOnce(World world, long lastTickNanos) {
        UUID uuid = resolveWorldUuid(world);
        recordOnce(uuid, world.getName(), lastTickNanos);
    }

    public void recordOnce(UUID worldUuid, String worldName, long lastTickNanos) {
        Attributes attrs = attrCache.computeIfAbsent(worldUuid, u -> Attributes.builder()
                .put(ATTR_WORLD_UUID, u.toString())
                .put(ATTR_WORLD_NAME, worldName == null ? "" : worldName)
                .build());

        long safeNanos = lastTickNanos <= 0L ? 1L : lastTickNanos;
        metrics.recordMspt(safeNanos, attrs);

        long tps = Math.min(TPS_CEILING, 1_000_000_000L / safeNanos);
        tpsByWorld.put(worldUuid, new HytaleMetrics.WorldSample(attrs, tps));
    }

    public void recordAll() {
        Collection<World> worlds;
        try {
            worlds = worldSource.get();
        } catch (Throwable t) {
            return;
        }
        if (worlds == null) return;
        for (World w : worlds) {
            try {
                Long nanos = tickNanosExtractor.apply(w);
                recordOnce(w, nanos == null ? 0L : nanos);
            } catch (Throwable ignored) {
            }
        }
    }

    public Attributes attributesFor(UUID worldUuid) {
        return attrCache.get(worldUuid);
    }

    public Map<UUID, HytaleMetrics.WorldSample> tpsSnapshot() {
        return tpsByWorld;
    }

    public void worldRemoved(UUID worldUuid) {
        attrCache.remove(worldUuid);
        tpsByWorld.remove(worldUuid);
    }

    private UUID resolveWorldUuid(World world) {
        UUID u = uuidExtractor.extract(world);
        if (u != null) return u;
        String name = world.getName();
        return UUID.nameUUIDFromBytes((name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    public interface UuidExtractor {
        UUID extract(World world);
    }
}
