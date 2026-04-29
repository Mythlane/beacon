package com.mythlane.beacon.instrum;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;

/**
 * Cardinality safety net for {@code hytale.*} instruments. Registers OTel
 * {@link View}s that allow only {@code hytale.world.uuid} and
 * {@code hytale.world.name}; any other attribute is dropped before reaching
 * the exporter.
 */
public final class CardinalityGuard {

    public static final Set<String> ALLOWED_WORLD_ATTRIBUTES;
    static {
        Set<String> s = new HashSet<>();
        s.add("hytale.world.uuid");
        s.add("hytale.world.name");
        ALLOWED_WORLD_ATTRIBUTES = Set.copyOf(s);
    }

    public static final List<String> GUARDED_INSTRUMENTS = List.of(
            "hytale.tps",
            "hytale.mspt",
            "hytale.players.online");

    private CardinalityGuard() {}

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
