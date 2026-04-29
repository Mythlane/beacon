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
 * Polls each {@link World} on a fixed cadence (30s, owned by
 * {@code BeaconPlugin}), reads {@code world.getBufferedTickLengthMetricSet()
 * .getLastValue()} (long nanoseconds), and:
 *
 * <ul>
 *   <li>records the value into the {@code hytale.mspt} histogram, and</li>
 *   <li>computes {@code tps = min(30, 1e9 / lastTickNanos)} and stores it in
 *       a per-world snapshot map driving the {@code hytale.tps} observable
 *       gauge callback.</li>
 * </ul>
 *
 * <p>Per-world {@link Attributes} are cached in a {@link ConcurrentHashMap}
 * keyed by world UUID so polling does not allocate a new {@code Attributes}
 * object on every cycle (PITFALLS P4 / threat T-1-04-02).
 *
 * <p>UUID source per D-13 (locked):
 * {@code world.getWorldConfig().getUuid().toString()} — see
 * {@code spike-results/B3-world-uuid.md}. A fallback
 * {@code UUID.nameUUIDFromBytes(world.getName().getBytes(UTF-8))} is used only
 * if the {@link UuidExtractor} returns {@code null} (defensive — current
 * Hytale guarantees non-null per {@code @Nonnull} on both methods).
 */
public final class TpsMsptRecorder {

    /** TPS hard ceiling (Hytale tick budget = 1/30 s — see FEATURES.md "Tick Model"). */
    public static final long TPS_CEILING = 30L;

    /** OTel attribute key for the canonical world UUID (D-06 / D-13). */
    public static final AttributeKey<String> ATTR_WORLD_UUID =
            AttributeKey.stringKey("hytale.world.uuid");

    /** OTel attribute key for the human-readable world name (D-06). */
    public static final AttributeKey<String> ATTR_WORLD_NAME =
            AttributeKey.stringKey("hytale.world.name");

    private final HytaleMetrics metrics;
    private final Supplier<Collection<World>> worldSource;
    private final UuidExtractor uuidExtractor;
    private final Function<World, Long> tickNanosExtractor;

    /** Pre-built Attributes per world UUID (P4 — no per-tick allocation). */
    private final ConcurrentHashMap<UUID, Attributes> attrCache = new ConcurrentHashMap<>();

    /** Per-world latest TPS snapshot (drives the hytale.tps gauge callback). */
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

    /**
     * Production-grade convenience constructor. Reads worlds from
     * {@code Universe.get().getWorlds().values()} and tick nanos from
     * {@code world.getBufferedTickLengthMetricSet().getLastValue()}.
     */
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

    /**
     * Record a single world synchronously — exposed so unit tests can drive
     * the recorder deterministically without standing up a scheduled executor.
     */
    public void recordOnce(World world, long lastTickNanos) {
        UUID uuid = resolveWorldUuid(world);
        recordOnce(uuid, world.getName(), lastTickNanos);
    }

    /**
     * Lower-level overload taking the resolved identity directly. Avoids static
     * initialization of {@code World}/{@code WorldConfig} during unit tests
     * (those classes pull asset-store machinery into their {@code <clinit>}).
     */
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

    /** Poll all worlds returned by {@link #worldSource} and record one sample each. */
    public void recordAll() {
        Collection<World> worlds;
        try {
            worlds = worldSource.get();
        } catch (Throwable t) {
            return; // No Universe yet (boot/test) — skip silently.
        }
        if (worlds == null) return;
        for (World w : worlds) {
            try {
                Long nanos = tickNanosExtractor.apply(w);
                recordOnce(w, nanos == null ? 0L : nanos);
            } catch (Throwable ignored) {
                // Never let one misbehaving world break polling for the rest.
            }
        }
    }

    /** Cached attributes for {@code worldUuid}, or {@code null} if never seen. */
    public Attributes attributesFor(UUID worldUuid) {
        return attrCache.get(worldUuid);
    }

    /** Snapshot map driving {@code hytale.tps}. Read by gauge callback. */
    public Map<UUID, HytaleMetrics.WorldSample> tpsSnapshot() {
        return tpsByWorld;
    }

    /** Drop bookkeeping for a world (e.g. {@code RemoveWorldEvent}). */
    public void worldRemoved(UUID worldUuid) {
        attrCache.remove(worldUuid);
        tpsByWorld.remove(worldUuid);
    }

    private UUID resolveWorldUuid(World world) {
        UUID u = uuidExtractor.extract(world);
        if (u != null) return u;
        // D-13 fallback (UNUSED in v0.1 — safety net only).
        String name = world.getName();
        return UUID.nameUUIDFromBytes((name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Strategy that pulls a stable {@link UUID} from a {@link World}; returns {@code null} to trigger fallback. */
    @FunctionalInterface
    public interface UuidExtractor {
        UUID extract(World world);
    }
}
