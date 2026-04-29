package com.mythlane.beacon.binding;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.core.ConfigLoader;
import com.mythlane.beacon.core.ExportFailureHandler;
import com.mythlane.beacon.core.OpenTelemetryFactory;
import com.mythlane.beacon.instrum.CardinalityGuard;
import com.mythlane.beacon.instrum.HytaleMetrics;
import com.mythlane.beacon.instrum.PlayerCountRegistry;
import com.mythlane.beacon.instrum.TpsMsptRecorder;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hytale-agnostic lifecycle core for {@link BeaconPlugin}. Holds all setup/start/shutdown
 * logic so it can be exercised by integration tests without standing up the Hytale plugin
 * runtime (which requires {@code JavaPluginInit} machinery).
 *
 * <p>Threading: setup/start/shutdown are expected to be invoked on the main server thread
 * (PluginManager). Shutdown is idempotent (AtomicBoolean guard).
 *
 * <p>Threat mitigations applied:
 * <ul>
 *   <li>T-1-02-04: log lines reference world.uuid/world.name only — no player UUIDs</li>
 *   <li>T-1-02-05: all exceptions in setup/start are caught + logged, never propagated
 *       (ARCHITECTURE.md §step 9: a thrown exception shuts down the server)</li>
 * </ul>
 */
public final class BeaconPluginLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(BeaconPluginLifecycle.class);

    /** Default config path under the Hytale CWD (D-12: {@code mods/} install path). */
    public static final Path DEFAULT_CONFIG_PATH = Path.of("mods", "Mythlane.Beacon", "config.toml");

    /** Flush budget at shutdown (PITFALLS P7). */
    public static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 5L;

    /** Polling cadence for {@link TpsMsptRecorder} (FND-04: every 30s). */
    public static final long POLL_PERIOD_SECONDS = 30L;

    private final Path configPath;
    private final Supplier<List<String>> jvmInputArgsSupplier;
    private final Supplier<OpenTelemetry> globalProbe;
    private final boolean registerAsGlobal;

    private volatile BeaconConfig config;
    private volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();
    private volatile ExportFailureHandler failureHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread shutdownHook;

    /**
     * Source of the plugin-scoped {@code IEventRegistry}. {@link BeaconPlugin}
     * wires this via {@link #setEventRegistrySource(Supplier)} after super-construction
     * (the registry is owned by {@code PluginBase}). Tests inject a Mockito mock.
     */
    private volatile Supplier<com.hypixel.hytale.event.IEventRegistry> eventRegistrySource;

    /**
     * Optional override for the polling scheduler. {@link #resolveScheduledExecutor()}
     * defaults to {@code HytaleServer.SCHEDULED_EXECUTOR}; tests inject a fake.
     */
    private volatile Supplier<ScheduledExecutorService> schedulerSource;

    // Plan 04 — metric wiring.
    private volatile HytaleMetrics hytaleMetrics;
    private volatile PlayerCountRegistry playerCountRegistry;
    private volatile TpsMsptRecorder tpsMsptRecorder;
    private volatile EventBindings eventBindings;
    private volatile ScheduledExecutorService pollerExecutor;
    private volatile ScheduledFuture<?> pollerFuture;
    private volatile boolean ownsPollerExecutor;

    public BeaconPluginLifecycle() {
        this(DEFAULT_CONFIG_PATH,
                () -> ManagementFactory.getRuntimeMXBean().getInputArguments(),
                GlobalOpenTelemetry::get,
                true);
    }

    /** Test-friendly constructor: defaults registerAsGlobal=false. */
    public BeaconPluginLifecycle(Path configPath,
                                 Supplier<List<String>> jvmInputArgsSupplier,
                                 Supplier<OpenTelemetry> globalProbe) {
        this(configPath, jvmInputArgsSupplier, globalProbe, false);
    }

    public BeaconPluginLifecycle(Path configPath,
                                 Supplier<List<String>> jvmInputArgsSupplier,
                                 Supplier<OpenTelemetry> globalProbe,
                                 boolean registerAsGlobal) {
        this.configPath = configPath;
        this.jvmInputArgsSupplier = jvmInputArgsSupplier;
        this.globalProbe = globalProbe;
        this.registerAsGlobal = registerAsGlobal;
    }

    /** Hytale {@code setup()} hook. Loads {@link BeaconConfig}. Never throws. */
    public void setup() {
        try {
            this.config = ConfigLoader.load(configPath);
            LOG.info("Beacon config loaded: endpoint={} service={} protocol={}",
                    config.endpoint(), config.serviceName(), config.protocol());
        } catch (Throwable t) {
            // T-1-02-05: never propagate — fall back to defaults.
            LOG.error("Beacon setup failed; falling back to default config", t);
            this.config = BeaconConfig.defaults();
        }
    }

    /**
     * Hytale {@code start()} hook. Builds the OTel SDK, scans for the Java agent,
     * registers a JVM shutdown hook. Never throws.
     */
    public void start() {
        try {
            if (config == null) config = BeaconConfig.defaults();
            this.openTelemetry = OpenTelemetryFactory.createOrAdopt(
                    config, globalProbe, registerAsGlobal,
                    CardinalityGuard::install);
            LOG.info("Beacon OpenTelemetry SDK ready: {}", openTelemetry.getClass().getName());

            this.failureHandler = new ExportFailureHandler(config.queueMaxSize(), System::nanoTime);
            failureHandler.bindMeter(openTelemetry);

            // --- Plan 04: hytale.tps / hytale.mspt / hytale.players.online wiring ----
            this.playerCountRegistry = new PlayerCountRegistry();
            this.hytaleMetrics = new HytaleMetrics(
                    openTelemetry,
                    () -> tpsMsptRecorder == null ? Map.of() : tpsMsptRecorder.tpsSnapshot(),
                    () -> playerCountSnapshot());
            this.tpsMsptRecorder = TpsMsptRecorder.forProduction(hytaleMetrics);
            this.eventBindings = new EventBindings(playerCountRegistry);
            try {
                eventBindings.register(getEventRegistryFromHytale());
            } catch (Throwable t) {
                LOG.warn("Beacon could not register Hytale player events; players.online will stay at 0", t);
            }

            // Resolve the scheduler — prefer HytaleServer.SCHEDULED_EXECUTOR if accessible.
            ScheduledExecutorService scheduler = resolveScheduledExecutor();
            this.pollerExecutor = scheduler;
            this.pollerFuture = scheduler.scheduleAtFixedRate(
                    () -> {
                        try { tpsMsptRecorder.recordAll(); }
                        catch (Throwable t) { LOG.warn("Beacon poller tick failed", t); }
                    },
                    POLL_PERIOD_SECONDS, POLL_PERIOD_SECONDS, TimeUnit.SECONDS);

            // PITFALLS P2 / R5: warn if -javaagent is not attached.
            try {
                List<String> args = jvmInputArgsSupplier.get();
                boolean hasAgent = args != null && args.stream().anyMatch(a -> a != null && a.contains("-javaagent"));
                if (!hasAgent) {
                    LOG.warn("JVM agent not attached — JVM auto-instrumentation metrics will be missing. "
                            + "Add -javaagent:opentelemetry-javaagent.jar to JVM flags.");
                }
            } catch (Throwable t) {
                LOG.warn("Could not inspect JVM input arguments", t);
            }

            // PITFALLS P7: belt-and-suspenders shutdown hook for SIGKILL paths.
            this.shutdownHook = new Thread(this::flushOnly, "beacon-shutdown-hook");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down; nothing to do.
            }
        } catch (Throwable t) {
            LOG.error("Beacon start failed; falling back to no-op OpenTelemetry", t);
            this.openTelemetry = OpenTelemetry.noop();
        }
    }

    /**
     * Hytale {@code shutdown()} hook. Forces flush within
     * {@link #SHUTDOWN_FLUSH_TIMEOUT_SECONDS} seconds, then closes. Idempotent.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return; // Already shut down.
        }
        // Cancel the recorder poller BEFORE the flush so no new metric points race the flush.
        ScheduledFuture<?> f = this.pollerFuture;
        if (f != null) {
            try { f.cancel(false); } catch (Throwable ignored) {}
            this.pollerFuture = null;
        }
        if (ownsPollerExecutor && pollerExecutor != null) {
            try { pollerExecutor.shutdownNow(); } catch (Throwable ignored) {}
        }
        try {
            HytaleMetrics m = this.hytaleMetrics;
            if (m != null) {
                try { m.close(); } catch (Throwable ignored) {}
            }
            if (openTelemetry instanceof OpenTelemetrySdk sdk) {
                try {
                    sdk.getSdkTracerProvider().forceFlush().join(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    LOG.warn("Beacon tracer forceFlush failed", t);
                }
                try {
                    sdk.getSdkMeterProvider().forceFlush().join(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    LOG.warn("Beacon meter forceFlush failed", t);
                }
                try {
                    sdk.close();
                } catch (Throwable t) {
                    LOG.warn("Beacon sdk close failed", t);
                }
            }
        } finally {
            // Best-effort: detach shutdown hook so the JVM can clean up references.
            Thread h = this.shutdownHook;
            if (h != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(h);
                } catch (IllegalStateException ignored) {
                    // JVM already shutting down.
                }
                this.shutdownHook = null;
            }
        }
    }

    /** Best-effort flush only — used by the JVM shutdown hook. */
    private void flushOnly() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            try {
                sdk.getSdkTracerProvider().forceFlush().join(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable ignored) { /* shutting down */ }
            try {
                sdk.getSdkMeterProvider().forceFlush().join(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable ignored) { /* shutting down */ }
        }
    }

    public OpenTelemetry openTelemetry() { return openTelemetry; }
    public BeaconConfig config() { return config; }
    public ExportFailureHandler failureHandler() { return failureHandler; }
    public PlayerCountRegistry playerCountRegistry() { return playerCountRegistry; }
    public TpsMsptRecorder tpsMsptRecorder() { return tpsMsptRecorder; }
    public EventBindings eventBindings() { return eventBindings; }
    public ScheduledFuture<?> pollerFuture() { return pollerFuture; }

    public void setEventRegistrySource(Supplier<com.hypixel.hytale.event.IEventRegistry> src) {
        this.eventRegistrySource = src;
    }

    public void setSchedulerSource(Supplier<ScheduledExecutorService> src) {
        this.schedulerSource = src;
    }

    private com.hypixel.hytale.event.IEventRegistry getEventRegistryFromHytale() {
        Supplier<com.hypixel.hytale.event.IEventRegistry> src = this.eventRegistrySource;
        if (src == null) {
            throw new IllegalStateException("No event registry source wired — BeaconPlugin.setup() should call setEventRegistrySource()");
        }
        return src.get();
    }

    private ScheduledExecutorService resolveScheduledExecutor() {
        Supplier<ScheduledExecutorService> src = this.schedulerSource;
        if (src != null) {
            ScheduledExecutorService s = src.get();
            if (s != null) {
                ownsPollerExecutor = false;
                return s;
            }
        }
        // Note: production wiring sets schedulerSource to
        // {@code () -> HytaleServer.SCHEDULED_EXECUTOR} via {@link BeaconPlugin#start()}
        // — done there (not here) so unit/integration tests that don't stand up
        // {@code HytaleServer} don't trigger its static initialization side-effects.
        ownsPollerExecutor = true;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "beacon-poller");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(tf);
    }

    /** Build the player-count snapshot map fed to {@link HytaleMetrics}'s gauge callback. */
    private Map<UUID, HytaleMetrics.WorldSample> playerCountSnapshot() {
        if (playerCountRegistry == null || tpsMsptRecorder == null) return Map.of();
        Map<UUID, HytaleMetrics.WorldSample> out = new HashMap<>();
        for (UUID worldUuid : playerCountRegistry.trackedWorlds()) {
            Attributes attrs = tpsMsptRecorder.attributesFor(worldUuid);
            if (attrs == null) continue; // world not yet polled — skip until we have stable attrs
            out.put(worldUuid, new HytaleMetrics.WorldSample(attrs, playerCountRegistry.snapshot(worldUuid)));
        }
        return out;
    }
}
