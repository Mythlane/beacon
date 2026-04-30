package com.mythlane.beacon.binding;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.instrum.HytaleMetrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hytale-agnostic lifecycle core for {@link BeaconPlugin}. Holds setup, start
 * and shutdown so the contract can be exercised without booting the Hytale
 * plugin runtime. Hooks are expected on the main server thread; shutdown is
 * idempotent. Exceptions never escape: {@code start()} would otherwise shut
 * the server down. Log lines reference {@code world.uuid}/{@code world.name}
 * only, never player UUIDs.
 */
public final class BeaconPluginLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(BeaconPluginLifecycle.class);

    public static final Path DEFAULT_CONFIG_PATH = Path.of("mods", "Mythlane_Beacon", "config.toml");
    public static final long POLL_PERIOD_SECONDS = 30L;

    private final ConfigBootstrap configBootstrap;
    private final ShutdownCoordinator shutdownCoordinator = new ShutdownCoordinator();
    private final Supplier<List<String>> jvmInputArgsSupplier;
    private final Supplier<OpenTelemetry> globalProbe;
    private final boolean registerAsGlobal;

    private volatile BeaconConfig config;
    private volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();
    private volatile Thread shutdownHook;

    private volatile Supplier<com.hypixel.hytale.event.IEventRegistry> eventRegistrySource;
    private volatile Supplier<ScheduledExecutorService> schedulerSource;

    private volatile TelemetryBootstrap telemetryBootstrap;
    private volatile TelemetryBootstrap.InstrumentHandles handles;
    private volatile PollScheduler pollScheduler;

    public BeaconPluginLifecycle() {
        this(DEFAULT_CONFIG_PATH,
                () -> ManagementFactory.getRuntimeMXBean().getInputArguments(),
                GlobalOpenTelemetry::get,
                true);
    }

    public BeaconPluginLifecycle(Path configPath,
                                 Supplier<List<String>> jvmInputArgsSupplier,
                                 Supplier<OpenTelemetry> globalProbe) {
        this(configPath, jvmInputArgsSupplier, globalProbe, false);
    }

    public BeaconPluginLifecycle(Path configPath,
                                 Supplier<List<String>> jvmInputArgsSupplier,
                                 Supplier<OpenTelemetry> globalProbe,
                                 boolean registerAsGlobal) {
        this.configBootstrap = new ConfigBootstrap(configPath);
        this.jvmInputArgsSupplier = jvmInputArgsSupplier;
        this.globalProbe = globalProbe;
        this.registerAsGlobal = registerAsGlobal;
    }

    public void setup() {
        this.config = configBootstrap.load();
    }

    public void start() {
        if (config == null) config = BeaconConfig.defaults();

        this.telemetryBootstrap = new TelemetryBootstrap(
                globalProbe, registerAsGlobal, jvmInputArgsSupplier,
                this::getEventRegistryFromHytale);

        this.openTelemetry = telemetryBootstrap.build(config);
        this.handles = telemetryBootstrap.bindInstruments(openTelemetry, config);

        try {
            this.pollScheduler = new PollScheduler(schedulerSource);
            this.pollScheduler.start(this::runPollerTick, Duration.ofSeconds(POLL_PERIOD_SECONDS));
        } catch (Throwable t) {
            LOG.warn("Beacon poller wiring failed", t);
        }

        try {
            this.shutdownHook = shutdownCoordinator.buildFlushHook(() -> openTelemetry);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
        } catch (Throwable t) {
            LOG.warn("Beacon shutdown hook install failed", t);
        }
    }

    public void shutdown() {
        HytaleMetrics metrics = handles != null ? handles.hytaleMetrics() : null;
        shutdownCoordinator.shutdown(openTelemetry, metrics, pollScheduler, shutdownHook);
        this.shutdownHook = null;
    }

    public OpenTelemetry openTelemetry() { return openTelemetry; }
    public BeaconConfig config() { return config; }

    public void setEventRegistrySource(Supplier<com.hypixel.hytale.event.IEventRegistry> src) {
        this.eventRegistrySource = src;
    }

    public void setSchedulerSource(Supplier<ScheduledExecutorService> src) {
        this.schedulerSource = src;
    }

    private com.hypixel.hytale.event.IEventRegistry getEventRegistryFromHytale() {
        Supplier<com.hypixel.hytale.event.IEventRegistry> src = this.eventRegistrySource;
        if (src == null) {
            throw new IllegalStateException("No event registry source wired; BeaconPlugin.setup() should call setEventRegistrySource()");
        }
        return src.get();
    }

    private void runPollerTick() {
        if (handles == null || handles.tpsMsptRecorder() == null) return;
        try {
            handles.tpsMsptRecorder().recordAll();
        } catch (Throwable t) {
            LOG.warn("Beacon poller tick failed", t);
        }
    }
}
