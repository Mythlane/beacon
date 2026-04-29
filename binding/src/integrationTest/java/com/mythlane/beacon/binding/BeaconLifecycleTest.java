package com.mythlane.beacon.binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.OpenTelemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link BeaconPluginLifecycle} (FND-01 + R3 + R4 + R5).
 *
 * <p>Exercises the lifecycle in isolation — does NOT stand up a real Hytale server
 * (that lives in Plan 06 manual quickstart). The plugin's own contract is verified
 * here:
 * <ul>
 *   <li>setup → start does not throw and yields a non-noop SDK</li>
 *   <li>shutdown returns within the 5s flush budget; second call is a no-op</li>
 *   <li>missing -javaagent emits a WARN log line</li>
 * </ul>
 */
class BeaconLifecycleTest {

    @Test
    void setupAndStartProduceRealSdk(@TempDir Path tmp) {
        BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle(
                tmp.resolve("nonexistent-config.toml"),
                () -> List.of("-Xmx4G"), // no -javaagent
                OpenTelemetry::noop);

        lifecycle.setup();
        lifecycle.start();

        OpenTelemetry sdk = lifecycle.openTelemetry();
        assertThat(sdk).isNotNull();
        // Real SDK class name — not the no-op fallback.
        assertThat(sdk.getClass().getName())
                .doesNotContain("Noop")
                .doesNotContain("Default");

        // Ensure shutdown leaves clean state for downstream tests.
        lifecycle.shutdown();
    }

    @Test
    void shutdownReturnsWithinBudgetAndIsIdempotent(@TempDir Path tmp) {
        BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle(
                tmp.resolve("nonexistent-config.toml"),
                () -> List.of("-Xmx4G"),
                OpenTelemetry::noop);
        lifecycle.setup();
        lifecycle.start();

        long startNs = System.nanoTime();
        CompletableFuture<Void> done = CompletableFuture.runAsync(lifecycle::shutdown);
        try {
            done.get(6L, TimeUnit.SECONDS); // 5s flush budget + 1s slack
        } catch (Exception e) {
            throw new AssertionError("shutdown did not return within 6 seconds", e);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(6_000L);

        // Second call must be a no-op (no exception).
        lifecycle.shutdown();
    }

    @Test
    void warnsWhenJavaAgentNotAttached(@TempDir Path tmp) {
        // Capture System.err — slf4j-simple writes WARN there by default.
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle(
                    tmp.resolve("nonexistent-config.toml"),
                    () -> List.of("-Xmx4G", "-XX:+UseG1GC"), // explicitly no -javaagent
                    OpenTelemetry::noop);
            lifecycle.setup();
            lifecycle.start();
            lifecycle.shutdown();
        } finally {
            System.setErr(originalErr);
        }

        String output = captured.toString();
        assertThat(output).contains("JVM agent not attached");
    }

}
