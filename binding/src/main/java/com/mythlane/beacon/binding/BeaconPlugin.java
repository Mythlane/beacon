package com.mythlane.beacon.binding;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import com.mythlane.beacon.core.BeaconConfig;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Hytale plugin entry point. Delegates all lifecycle logic to
 * {@link BeaconPluginLifecycle} so the contract can be exercised in tests
 * without booting the Hytale plugin runtime. All exceptions are swallowed by
 * the delegate; a thrown exception in {@code setup()}/{@code start()} would
 * shut the server down.
 */
public final class BeaconPlugin extends JavaPlugin {

    private final BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle();

    public BeaconPlugin(JavaPluginInit init) {
        super(init);
        lifecycle.setEventRegistrySource(this::getEventRegistry);
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

    public OpenTelemetry openTelemetry() {
        return lifecycle.openTelemetry();
    }

    public BeaconConfig config() {
        return lifecycle.config();
    }
}
