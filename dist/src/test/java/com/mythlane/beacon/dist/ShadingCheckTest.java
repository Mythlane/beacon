package com.mythlane.beacon.dist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Programmatic mirror of the {@code checkShading} + {@code verifyServiceFiles}
 * Gradle tasks. Provides a stable JUnit entry point so the same R1/R2 gates
 * can be invoked from {@code ./gradlew :dist:test}, IDE test runners, and
 * any future integration harness.
 *
 * <p>Each test is gated on the shaded JAR existing at the expected path —
 * running {@code :dist:test} alone (without {@code :dist:shadowJar} first)
 * would otherwise fail spuriously. The Gradle task graph wires the JAR
 * production into {@code check} via the custom tasks above.
 */
class ShadingCheckTest {

    /** Set of top-level package prefixes that MUST be relocated. */
    private static final Pattern UNSHADED = Pattern.compile(
        "^(io/opentelemetry|io/grpc|com/google/protobuf|io/netty|okhttp3|okio|io/perfmark)/.*\\.class$"
    );

    private static final String SHADED_PREFIX = "com/mythlane/beacon/shaded/";

    private static Path jarPath;

    @BeforeAll
    static void resolveJar() {
        jarPath = locateJar();
    }

    static boolean jarAvailable() {
        return locateJar() != null;
    }

    private static Path locateJar() {
        // Run-from-repo-root or run-from-:dist working directories both supported.
        Path[] candidates = new Path[]{
            Paths.get("dist/build/libs/beacon-0.1.0-alpha.jar"),
            Paths.get("build/libs/beacon-0.1.0-alpha.jar")
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    @Test
    @EnabledIf("jarAvailable")
    void distJarExistsAtExpectedPath() {
        assertThat(jarPath)
            .as("dist/build/libs/beacon-0.1.0-alpha.jar must exist post-shadowJar")
            .isNotNull()
            .satisfies(p -> assertThat(Files.exists(p)).isTrue());
    }

    @Test
    @EnabledIf("jarAvailable")
    void zeroUnshadedClassesPresent() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (UNSHADED.matcher(e.getName()).matches()) {
                    offenders.add(e.getName());
                }
            }
        }
        assertThat(offenders)
            .as("No unrelocated classes may appear under io/opentelemetry, io/grpc, "
                + "com/google/protobuf, io/netty, okhttp3, okio, io/perfmark (R1, PITFALLS P3)")
            .isEmpty();
    }

    @Test
    @EnabledIf("jarAvailable")
    void shadedCopiesPresent() throws IOException {
        // The OTel exporter currently pulled by core is opentelemetry-exporter-otlp,
        // which (in 1.61.0) uses OkHttp by default for HTTP/protobuf transport — it
        // does NOT pull io.grpc / io.netty / com.google.protobuf transitively unless
        // the gRPC sub-artifact is wired explicitly. The relocate directives for
        // those packages stay in dist/build.gradle as DEFENSIVE rules so a future
        // dependency change (e.g. switching to opentelemetry-exporter-otlp-grpc)
        // can't accidentally ship Hytale-conflicting classes. We assert here only
        // on the prefixes that the current dep graph actually populates.
        List<String> requiredPopulated = List.of(
            SHADED_PREFIX + "otel/",
            SHADED_PREFIX + "okhttp3/",
            SHADED_PREFIX + "okio/"
        );
        List<String> missing = new ArrayList<>(requiredPopulated);

        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                missing.removeIf(name::startsWith);
                if (missing.isEmpty()) break;
            }
        }
        assertThat(missing)
            .as("Currently-populated shaded prefixes (otel, okhttp3, okio) must each contribute at least one class")
            .isEmpty();
    }

    @Test
    @EnabledIf("jarAvailable")
    void otelAutoconfigureSpiFilesPresent() throws IOException {
        // The OTel SDK loads these SPI providers at boot via ServiceLoader; if
        // any are missing post-Shadow merge the SDK can't resolve exporters or
        // resource providers (R2, PITFALLS P3). AutoConfigurationCustomizerProvider
        // is intentionally NOT in the list — it only exists when a customizer
        // extension JAR is on the classpath, so it's unreliable as a witness.
        List<String> requiredSuffixes = List.of(
            "sdk.autoconfigure.spi.ResourceProvider",
            "sdk.autoconfigure.spi.internal.ComponentProvider",
            "sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider",
            "sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider",
            "sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider"
        );
        String shadedRoot = "META-INF/services/com.mythlane.beacon.shaded.otel.";
        String unshadedRoot = "META-INF/services/io.opentelemetry.";

        List<String> missing = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            for (String suffix : requiredSuffixes) {
                ZipEntry shaded = zf.getEntry(shadedRoot + suffix);
                ZipEntry unshaded = zf.getEntry(unshadedRoot + suffix);
                ZipEntry e = (shaded != null) ? shaded : unshaded;
                if (e == null) {
                    missing.add(suffix);
                    continue;
                }
                String text = new String(zf.getInputStream(e).readAllBytes()).trim();
                if (text.isEmpty()) {
                    missing.add(suffix + " (empty)");
                }
            }
        }
        assertThat(missing)
            .as("All required OTel autoconfigure SPI files must be preserved post-Shadow merge (R2)")
            .isEmpty();
    }
}
