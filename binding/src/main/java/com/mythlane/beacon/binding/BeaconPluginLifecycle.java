package com.mythlane.beacon.binding;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.core.ConfigLoader;
import com.mythlane.beacon.core.ExportFailureHandler;
import com.mythlane.beacon.core.OpenTelemetryFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
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

    private final Path configPath;
    private final Supplier<List<String>> jvmInputArgsSupplier;
    private final Supplier<OpenTelemetry> globalProbe;
    private final boolean registerAsGlobal;

    private volatile BeaconConfig config;
    private volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();
    private volatile ExportFailureHandler failureHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread shutdownHook;

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
            this.openTelemetry = OpenTelemetryFactory.createOrAdopt(config, globalProbe, registerAsGlobal);
            LOG.info("Beacon OpenTelemetry SDK ready: {}", openTelemetry.getClass().getName());

            this.failureHandler = new ExportFailureHandler(config.queueMaxSize(), System::nanoTime);
            failureHandler.bindMeter(openTelemetry);

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
        try {
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
}
