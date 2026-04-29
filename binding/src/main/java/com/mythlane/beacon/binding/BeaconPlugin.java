package com.mythlane.beacon.binding;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import com.mythlane.beacon.core.BeaconConfig;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Beacon's Hytale plugin entry point. Extends {@link JavaPlugin} (not {@link
 * com.hypixel.hytale.server.core.plugin.PluginBase} directly) per RESEARCH.md FND-01:
 * {@code JavaPlugin} adds asset-pack registration on top of the base lifecycle.
 *
 * <p>All non-trivial logic is delegated to {@link BeaconPluginLifecycle} so the
 * lifecycle contract can be exercised by integration tests without standing up the
 * Hytale plugin runtime.
 *
 * <p>Per ARCHITECTURE.md §step 9, exceptions thrown from {@code setup()}/{@code start()}
 * shut the server down — the lifecycle delegate catches everything internally.
 */
public final class BeaconPlugin extends JavaPlugin {

    private final BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle();

    public BeaconPlugin(JavaPluginInit init) {
        super(init);
        // Wire the plugin-scoped event registry into the lifecycle delegate so
        // EventBindings.register() can attach the player join/leave handlers.
        // PluginBase owns the registry; we expose it via getEventRegistry().
        lifecycle.setEventRegistrySource(this::getEventRegistry);
        // Use the Hytale-owned scheduler so polling joins existing JVM-thread budgeting.
        lifecycle.setSchedulerSource(() -> HytaleServer.SCHEDULED_EXECUTOR);
    }

    @Override
    protected void setup() {
        lifecycle.setup();
    }

    @Override
    protected void start() {
        lifecycle.start();
    }

    @Override
    protected void shutdown() {
        lifecycle.shutdown();
    }

    /** Public accessor used by downstream plans (per Plan 02 frontmatter contract). */
    public OpenTelemetry openTelemetry() {
        return lifecycle.openTelemetry();
    }

    /** Public accessor exposing the resolved config (read-only). */
    public BeaconConfig config() {
        return lifecycle.config();
    }
}
