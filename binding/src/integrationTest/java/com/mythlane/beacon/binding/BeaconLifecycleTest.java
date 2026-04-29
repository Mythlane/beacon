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

class BeaconLifecycleTest {

    @Test
    void setupAndStartProduceRealSdk(@TempDir Path tmp) {
        BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle(
                tmp.resolve("nonexistent-config.toml"),
                () -> List.of("-Xmx4G"),
                OpenTelemetry::noop);

        lifecycle.setup();
        lifecycle.start();

        OpenTelemetry sdk = lifecycle.openTelemetry();
        assertThat(sdk).isNotNull();
        assertThat(sdk.getClass().getName())
                .doesNotContain("Noop")
                .doesNotContain("Default");

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
            done.get(6L, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("shutdown did not return within 6 seconds", e);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(6_000L);

        lifecycle.shutdown();
    }

    @Test
    void warnsWhenJavaAgentNotAttached(@TempDir Path tmp) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            BeaconPluginLifecycle lifecycle = new BeaconPluginLifecycle(
                    tmp.resolve("nonexistent-config.toml"),
                    () -> List.of("-Xmx4G", "-XX:+UseG1GC"),
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
