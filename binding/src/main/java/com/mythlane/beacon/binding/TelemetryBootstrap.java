package com.mythlane.beacon.binding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.mythlane.beacon.core.BeaconConfig;
import com.mythlane.beacon.core.ExportFailureHandler;
import com.mythlane.beacon.core.OpenTelemetryFactory;
import com.mythlane.beacon.instrum.CardinalityGuard;
import com.mythlane.beacon.instrum.HytaleMetrics;
import com.mythlane.beacon.instrum.PlayerCountRegistry;
import com.mythlane.beacon.instrum.TpsMsptRecorder;

import com.hypixel.hytale.event.IEventRegistry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TelemetryBootstrap {

    record InstrumentHandles(
            PlayerCountRegistry playerCountRegistry,
            HytaleMetrics hytaleMetrics,
            TpsMsptRecorder tpsMsptRecorder
    ) {}

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryBootstrap.class);

    private final Supplier<OpenTelemetry> globalProbe;
    private final boolean registerAsGlobal;
    private final Supplier<List<String>> jvmInputArgsSupplier;
    private final Supplier<IEventRegistry> eventRegistrySource;

    TelemetryBootstrap(Supplier<OpenTelemetry> globalProbe,
                       boolean registerAsGlobal,
                       Supplier<List<String>> jvmInputArgsSupplier,
                       Supplier<IEventRegistry> eventRegistrySource) {
        this.globalProbe = globalProbe;
        this.registerAsGlobal = registerAsGlobal;
        this.jvmInputArgsSupplier = jvmInputArgsSupplier;
        this.eventRegistrySource = eventRegistrySource;
    }

    OpenTelemetry build(BeaconConfig config) {
        try {
            OpenTelemetry otel = OpenTelemetryFactory.createOrAdopt(
                    config, globalProbe, registerAsGlobal, CardinalityGuard::install);
            LOG.info("Beacon OpenTelemetry SDK ready: sdk={} endpoint={} protocol={}",
                    otel.getClass().getName(), config.endpoint(), config.protocol());
            warnIfJavaagentMissing();
            return otel;
        } catch (Throwable t) {
            LOG.warn("Beacon telemetry build failed; using no-op OpenTelemetry", t);
            return OpenTelemetry.noop();
        }
    }

    InstrumentHandles bindInstruments(OpenTelemetry otel, BeaconConfig config) {
        try {
            ExportFailureHandler failureHandler = new ExportFailureHandler(config.queueMaxSize(), System::nanoTime);
            failureHandler.bindMeter(otel);
        } catch (Throwable t) {
            LOG.warn("Beacon failure handler init failed", t);
        }

        try {
            if (jvmAgentDetected()) {
                LOG.info("OTel Java Agent detected; skipping Beacon library-mode JVM runtime metrics "
                        + "to avoid double-publication. Agent provides JVM metrics under "
                        + "io.opentelemetry.runtime-telemetry-java8 scope.");
            } else {
                io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics.create(otel);
                LOG.info("Beacon library-mode JVM runtime metrics enabled (memory, GC, threads, classes, CPU). "
                        + "No OTel Java Agent detected.");
            }
        } catch (Throwable t) {
            LOG.warn("Beacon could not enable JVM runtime metrics", t);
        }

        PlayerCountRegistry pcr = null;
        try {
            pcr = new PlayerCountRegistry();
        } catch (Throwable t) {
            LOG.warn("Beacon player count registry init failed", t);
        }

        HytaleMetrics hm = null;
        TpsMsptRecorder tmr = null;
        if (pcr != null) {
            // Forward-reference idiom: HytaleMetrics needs lambdas reading
            // TpsMsptRecorder, but TpsMsptRecorder is constructed AFTER HytaleMetrics
            // (it depends on hm). The single-element array holds the deferred
            // reference so the lambdas resolve it at call-time, not construction-time.
            // Do not "simplify" — both objects are mutually dependent at construction.
            final TpsMsptRecorder[] tmrRef = new TpsMsptRecorder[1];
            final PlayerCountRegistry pcrFinal = pcr;
            try {
                hm = new HytaleMetrics(
                        otel,
                        () -> tmrRef[0] == null ? Map.of() : tmrRef[0].tpsSnapshot(),
                        () -> playerCountSnapshot(pcrFinal, tmrRef[0]));
                tmr = TpsMsptRecorder.forProduction(hm);
                tmrRef[0] = tmr;
            } catch (Throwable t) {
                LOG.warn("Beacon metrics init failed", t);
                hm = null;
                tmr = null;
            }
        }

        if (pcr != null) {
            registerEventBindings(pcr);
        }

        return new InstrumentHandles(pcr, hm, tmr);
    }

    private void registerEventBindings(PlayerCountRegistry pcr) {
        try {
            EventBindings eb = new EventBindings(pcr);
            eb.register(eventRegistrySource.get());
        } catch (Throwable t) {
            LOG.warn("Beacon could not register Hytale player events; players.online will stay at 0", t);
        }
    }

    private void warnIfJavaagentMissing() {
        try {
            if (!jvmAgentDetected()) {
                LOG.warn("OTel Java Agent not detected. Beacon will emit JVM runtime metrics in "
                        + "library mode (heap, GC, threads, classes, CPU). HTTP/JDBC/Netty "
                        + "auto-instrumentation requires the agent.");
            }
        } catch (Throwable t) {
            LOG.warn("Could not inspect JVM input arguments", t);
        }
    }

    private boolean jvmAgentDetected() {
        List<String> args = jvmInputArgsSupplier.get();
        return args != null && args.stream().anyMatch(a -> a != null && a.contains("-javaagent"));
    }

    static Map<UUID, HytaleMetrics.WorldSample> playerCountSnapshot(PlayerCountRegistry pcr, TpsMsptRecorder tmr) {
        if (pcr == null || tmr == null) return Map.of();
        Map<UUID, HytaleMetrics.WorldSample> out = new HashMap<>();
        for (UUID worldUuid : pcr.trackedWorlds()) {
            Attributes attrs = tmr.attributesFor(worldUuid);
            if (attrs == null) continue;
            out.put(worldUuid, new HytaleMetrics.WorldSample(attrs, pcr.snapshot(worldUuid)));
        }
        return out;
    }
}
