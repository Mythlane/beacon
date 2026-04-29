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
 * Programmatic mirror of {@code checkShading} and {@code verifyServiceFiles}
 * for {@code ./gradlew :dist:test} and IDE runners. Tests are gated on the
 * shaded JAR existing.
 */
class ShadingCheckTest {

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
                + "com/google/protobuf, io/netty, okhttp3, okio, io/perfmark")
            .isEmpty();
    }

    @Test
    @EnabledIf("jarAvailable")
    void shadedCopiesPresent() throws IOException {
        // opentelemetry-exporter-otlp uses OkHttp by default and does not pull
        // io.grpc / io.netty / com.google.protobuf transitively; the relocate
        // directives for those packages stay defensive in dist/build.gradle so
        // a future dependency switch can't accidentally ship Hytale-conflicting
        // classes. Only the currently-populated prefixes are asserted here.
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
        // The OTel SDK loads these SPI providers via ServiceLoader; missing
        // entries break exporter/resource resolution after Shadow merge.
        // AutoConfigurationCustomizerProvider is excluded because it only
        // exists when a customizer extension JAR is on the classpath.
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
            .as("All required OTel autoconfigure SPI files must be preserved post-Shadow merge")
            .isEmpty();
    }
}
