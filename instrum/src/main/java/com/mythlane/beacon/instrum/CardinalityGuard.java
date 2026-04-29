package com.mythlane.beacon.instrum;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;

/**
 * Cardinality safety net for {@code hytale.*} instruments (R9 / PITFALLS P5 /
 * threat T-1-04-01). Registers OTel {@link View}s that ALLOW only the known
 * world-identity attribute keys ({@code hytale.world.uuid} and
 * {@code hytale.world.name}); any other attribute submitted by a misbehaving
 * caller is dropped before reaching the exporter.
 *
 * <p>Beacon registers explicit per-instrument views (one per metric name)
 * because OTel's wildcard {@link InstrumentSelector} only supports {@code *}
 * across all positions — a name pattern like {@code hytale.*} is what the SDK
 * documents as permitted. We use it here.
 */
public final class CardinalityGuard {

    /** The two world-identity keys allowed on every {@code hytale.*} instrument (D-06). */
    public static final Set<String> ALLOWED_WORLD_ATTRIBUTES;
    static {
        Set<String> s = new HashSet<>();
        s.add("hytale.world.uuid");
        s.add("hytale.world.name");
        ALLOWED_WORLD_ATTRIBUTES = Set.copyOf(s);
    }

    /** Instrument names this guard is responsible for (matches {@link HytaleMetrics}). */
    public static final List<String> GUARDED_INSTRUMENTS = List.of(
            "hytale.tps",
            "hytale.mspt",
            "hytale.players.online");

    private CardinalityGuard() {}

    /**
     * Register the world-attribute allowlist views on the supplied
     * {@link SdkMeterProviderBuilder}. Idempotent in spirit — calling twice will
     * register two equivalent views, harmless but wasteful.
     */
    public static SdkMeterProviderBuilder install(SdkMeterProviderBuilder builder) {
        for (String name : GUARDED_INSTRUMENTS) {
            InstrumentSelector selector = InstrumentSelector.builder()
                    .setName(name)
                    .build();
            View view = View.builder()
                    .setAttributeFilter(ALLOWED_WORLD_ATTRIBUTES)
                    .build();
            builder.registerView(selector, view);
        }
        return builder;
    }
}
