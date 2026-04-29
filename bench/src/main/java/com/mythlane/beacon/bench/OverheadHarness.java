package com.mythlane.beacon.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 3-run median overhead bench used as a release gate. For each run, launches
 * {@code HytaleServer.jar} once with Beacon and once without, samples CPU and
 * RSS every second after a 30s warmup, then writes the median delta to
 * {@code docs/perf-quic.md}. Exits 1 when median CPU is at least 1.5% (giving 0.5%
 * noise headroom over the 1% target) or memory overhead is at least 50 MB.
 */
public final class OverheadHarness {

    private static final double HARD_FAIL_CPU_PCT = 1.5;
    private static final long HARD_FAIL_MEM_MB = 50;

    private final String target;
    private final int players;
    private final int durationSec;
    private final int runs;
    private final Path reportPath;

    public OverheadHarness(String target, int players, int durationSec, int runs, Path reportPath) {
        this.target = target;
        this.players = players;
        this.durationSec = durationSec;
        this.runs = runs;
        this.reportPath = reportPath;
    }

    public int run() throws Exception {
        // Hytale's QUIC transport enforces mutual TLS regardless of --auth-mode, so
        // bot-driven load measurement requires Hytale-issued client credentials.
        // Default to idle overhead; set beacon.bench.live=1 to force the bot path.
        String live = System.getProperty("beacon.bench.live", System.getenv("BEACON_BENCH_LIVE"));
        boolean liveBots = "1".equals(live) || "true".equalsIgnoreCase(live);
        if (!liveBots) {
            return runIdleOverhead();
        }

        System.out.printf("[bench] overhead harness: %d players × %ds × %d runs → %s%n",
                players, durationSec, runs, reportPath);

        List<RunResult> baseline = new ArrayList<>();
        List<RunResult> beacon = new ArrayList<>();

        for (int i = 0; i < runs; i++) {
            System.out.printf("[bench] run %d/%d: baseline%n", i + 1, runs);
            baseline.add(runOnce(Mode.BASELINE));
            System.out.printf("[bench] run %d/%d: beacon%n", i + 1, runs);
            beacon.add(runOnce(Mode.BEACON));
        }

        double medCpuBaseline = median(baseline.stream().mapToDouble(r -> r.cpuPct).toArray());
        double medCpuBeacon = median(beacon.stream().mapToDouble(r -> r.cpuPct).toArray());
        long medMemBaseline = (long) median(baseline.stream().mapToDouble(r -> r.rssMb).toArray());
        long medMemBeacon = (long) median(beacon.stream().mapToDouble(r -> r.rssMb).toArray());

        double cpuDelta = medCpuBeacon - medCpuBaseline;
        long memDelta = medMemBeacon - medMemBaseline;

        boolean cpuPass = cpuDelta < 1.0;
        boolean memPass = memDelta < HARD_FAIL_MEM_MB;
        boolean hardFail = cpuDelta >= HARD_FAIL_CPU_PCT || memDelta >= HARD_FAIL_MEM_MB;

        writeReport(baseline, beacon, medCpuBaseline, medCpuBeacon, cpuDelta,
                medMemBaseline, medMemBeacon, memDelta, cpuPass, memPass);

        System.out.printf("[bench] median CPU delta = %.2f%% (target < 1%%, hard-fail >= %.1f%%)%n",
                cpuDelta, HARD_FAIL_CPU_PCT);
        System.out.printf("[bench] median MEM delta = %d MB (target < %d MB)%n", memDelta, HARD_FAIL_MEM_MB);

        return hardFail ? 1 : 0;
    }

    private int runIdleOverhead() throws Exception {
        System.out.printf("[bench] idle overhead: %ds × %d runs → %s%n", durationSec, runs, reportPath);
        List<RunResult> baseline = new ArrayList<>();
        List<RunResult> beacon = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            System.out.printf("[bench] run %d/%d: baseline (idle)%n", i + 1, runs);
            baseline.add(runOnceIdle(Mode.BASELINE));
            System.out.printf("[bench] run %d/%d: beacon (idle)%n", i + 1, runs);
            beacon.add(runOnceIdle(Mode.BEACON));
        }
        double medCpuBaseline = median(baseline.stream().mapToDouble(r -> r.cpuPct).toArray());
        double medCpuBeacon = median(beacon.stream().mapToDouble(r -> r.cpuPct).toArray());
        long medMemBaseline = (long) median(baseline.stream().mapToDouble(r -> r.rssMb).toArray());
        long medMemBeacon = (long) median(beacon.stream().mapToDouble(r -> r.rssMb).toArray());
        double cpuDelta = medCpuBeacon - medCpuBaseline;
        long memDelta = medMemBeacon - medMemBaseline;
        boolean cpuPass = cpuDelta < 1.0;
        boolean memPass = memDelta < HARD_FAIL_MEM_MB;
        boolean hardFail = cpuDelta >= HARD_FAIL_CPU_PCT || memDelta >= HARD_FAIL_MEM_MB;
        writeReport(baseline, beacon, medCpuBaseline, medCpuBeacon, cpuDelta,
                medMemBaseline, medMemBeacon, memDelta, cpuPass, memPass);
        System.out.printf("[bench] [IDLE] median CPU delta = %.2f%% (target < 1%%)%n", cpuDelta);
        System.out.printf("[bench] [IDLE] median MEM delta = %d MB (target < %d MB)%n", memDelta, HARD_FAIL_MEM_MB);
        return hardFail ? 1 : 0;
    }

    private RunResult runOnceIdle(Mode mode) throws Exception {
        String hytaleHome = System.getenv().getOrDefault("HYTALE_HOME", "Hytale/install/release/package/game/latest/Server");
        Path serverJar = Path.of(hytaleHome, "HytaleServer.jar");
        List<String> cmd = new ArrayList<>(Arrays.asList(
                "java", "-Xms4G", "-Xmx16G", "-XX:+UseG1GC"));
        if (mode == Mode.BEACON) {
            Path beaconJar = Path.of("dist/build/libs/beacon-0.1.0-alpha.jar");
            cmd.addAll(Arrays.asList("-cp", beaconJar.toAbsolutePath() + java.io.File.pathSeparator + serverJar.toAbsolutePath(),
                    "com.hypixel.hytale.Main"));
        } else {
            cmd.addAll(Arrays.asList("-jar", serverJar.toAbsolutePath().toString()));
        }
        cmd.addAll(Arrays.asList("--assets", "Assets.zip", "--bind", "0.0.0.0:5520",
                "--auth-mode=offline", "--disable-sentry"));
        Path serverWorkDir = serverJar.toAbsolutePath().getParent().getParent();
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(serverWorkDir.toFile()).redirectErrorStream(true);
        if (mode == Mode.BEACON) {
            pb.environment().putIfAbsent("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        }
        Process server = pb.start();
        startStdoutPump(server);
        try {
            waitForServerReady(server, Duration.ofSeconds(120));
            ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
            List<Double> cpuSamples = new ArrayList<>();
            List<Long> memSamples = new ArrayList<>();
            long pid = server.pid();
            sampler.scheduleAtFixedRate(() -> {
                try { cpuSamples.add(sampleCpuPct(pid)); memSamples.add(sampleRssMb(pid)); }
                catch (Exception ignore) {}
            }, 0, 1, TimeUnit.SECONDS);
            Thread.sleep(durationSec * 1000L);
            sampler.shutdownNow();
            int warmup = Math.min(30, cpuSamples.size());
            double meanCpu = cpuSamples.stream().skip(warmup).mapToDouble(Double::doubleValue).average().orElse(0);
            long peakMem = memSamples.stream().skip(warmup).mapToLong(Long::longValue).max().orElse(0);
            return new RunResult(meanCpu, peakMem);
        } finally {
            stopServer(server);
        }
    }

    private enum Mode { BASELINE, BEACON }

    private RunResult runOnce(Mode mode) throws Exception {
        String hytaleHome = System.getenv().getOrDefault("HYTALE_HOME", "Hytale/install/release/package/game/latest/Server");
        Path serverJar = Path.of(hytaleHome, "HytaleServer.jar");

        List<String> cmd = new ArrayList<>(Arrays.asList(
                "java",
                "-Xms4G", "-Xmx16G", "-XX:+UseG1GC"));
        if (mode == Mode.BEACON) {
            // beacon-agent.jar is an OTel agent extension (loaded via
            // -Dotel.javaagent.extensions=...). The plugin-only overhead path
            // skips it because wiring it requires opentelemetry-javaagent.jar.
            Path beaconJar = Path.of("dist/build/libs/beacon-0.1.0-alpha.jar");
            cmd.addAll(Arrays.asList("-cp", beaconJar.toAbsolutePath() + java.io.File.pathSeparator + serverJar.toAbsolutePath(),
                    "com.hypixel.hytale.Main"));
        } else {
            cmd.addAll(Arrays.asList("-jar", serverJar.toAbsolutePath().toString()));
        }
        cmd.addAll(Arrays.asList("--assets", "Assets.zip",
                "--bind", "0.0.0.0:5520",
                "--auth-mode=insecure",
                "--disable-sentry"));

        // Assets.zip lives one level above Server/ in the Hytale install.
        Path serverWorkDir = serverJar.toAbsolutePath().getParent().getParent();
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(serverWorkDir.toFile())
                .redirectErrorStream(true);
        if (mode == Mode.BEACON) {
            pb.environment().putIfAbsent("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        }

        Process server = pb.start();
        Thread stdoutPump = startStdoutPump(server);
        try {
            waitForServerReady(server, Duration.ofSeconds(120));

            ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
            List<Double> cpuSamples = new ArrayList<>();
            List<Long> memSamples = new ArrayList<>();
            long pid = server.pid();
            sampler.scheduleAtFixedRate(() -> {
                try {
                    cpuSamples.add(sampleCpuPct(pid));
                    memSamples.add(sampleRssMb(pid));
                } catch (Exception ignore) {}
            }, 0, 1, TimeUnit.SECONDS);

            String[] hp = target.split(":");
            String host = hp[0];
            int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5520;
            try (ExecutorService bots = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch done = new CountDownLatch(players);
                for (int i = 0; i < players; i++) {
                    bots.submit(() -> {
                        try {
                            new SessionSequence(new NettyQuicBotClient())
                                    .run(host, port, Duration.ofSeconds(durationSec));
                        } catch (Exception e) {
                            System.err.println("[bench] bot failed: " + e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                done.await(durationSec + 60L, TimeUnit.SECONDS);
            }

            sampler.shutdownNow();

            int warmup = Math.min(30, cpuSamples.size());
            double meanCpu = cpuSamples.stream().skip(warmup).mapToDouble(Double::doubleValue).average().orElse(0);
            long peakMem = memSamples.stream().skip(warmup).mapToLong(Long::longValue).max().orElse(0);
            return new RunResult(meanCpu, peakMem);
        } finally {
            stopServer(server);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean serverReadyMarker = new java.util.concurrent.atomic.AtomicBoolean(false);

    private Thread startStdoutPump(Process server) {
        serverReadyMarker.set(false);
        Thread t = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(server.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("Hytale Server Booted!")) {
                        serverReadyMarker.set(true);
                    }
                }
            } catch (IOException ignored) {}
        }, "hytale-server-stdout");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void waitForServerReady(Process server, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) throw new IllegalStateException("server exited before ready");
            if (serverReadyMarker.get()) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("server not ready within " + timeout);
    }

    private final java.util.Map<Long, long[]> cpuLastSample = new java.util.concurrent.ConcurrentHashMap<>();

    private double sampleCpuPct(long pid) {
        var info = ProcessHandle.of(pid).map(ProcessHandle::info).orElse(null);
        if (info == null) return 0;
        var dur = info.totalCpuDuration();
        if (dur.isEmpty()) return 0;
        long cpuMillis = dur.get().toMillis();
        long wallNanos = System.nanoTime();
        long[] prev = cpuLastSample.put(pid, new long[]{cpuMillis, wallNanos});
        if (prev == null) return 0;
        long deltaCpuMs = cpuMillis - prev[0];
        long deltaWallMs = (wallNanos - prev[1]) / 1_000_000L;
        if (deltaWallMs <= 0) return 0;
        int cores = Runtime.getRuntime().availableProcessors();
        return (deltaCpuMs * 100.0) / (deltaWallMs * cores);
    }

    private long sampleRssMb(long pid) throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("windows")) {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).WorkingSet64")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty() && out.chars().allMatch(Character::isDigit)) {
                return Long.parseLong(out) / (1024 * 1024);
            }
            return 0;
        }
        try {
            Path status = Path.of("/proc/" + pid + "/status");
            if (Files.exists(status)) {
                for (String line : Files.readAllLines(status)) {
                    if (line.startsWith("VmRSS:")) {
                        String kb = line.replaceAll("[^0-9]", "");
                        return Long.parseLong(kb) / 1024;
                    }
                }
            }
        } catch (Exception ignore) {}
        Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        try { return Long.parseLong(out) / 1024; } catch (NumberFormatException e) { return 0; }
    }

    private void stopServer(Process server) throws InterruptedException {
        if (!server.isAlive()) return;
        server.destroy();
        if (!server.waitFor(10, TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }

    static double median(double[] xs) {
        if (xs.length == 0) return 0;
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private void writeReport(List<RunResult> baseline, List<RunResult> beacon,
                             double medCpuB, double medCpuBe, double cpuD,
                             long medMemB, long medMemBe, long memD,
                             boolean cpuPass, boolean memPass) throws IOException {
        Files.createDirectories(reportPath.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Beacon performance report\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n");
        sb.append("Hardware: ").append(System.getProperty("os.name"))
                .append(" / ").append(System.getProperty("os.arch"))
                .append(" / ").append(Runtime.getRuntime().availableProcessors()).append(" cores")
                .append(" / Java ").append(System.getProperty("java.version")).append("\n\n");
        sb.append("## Configuration\n");
        String live = System.getProperty("beacon.bench.live", System.getenv("BEACON_BENCH_LIVE"));
        boolean liveBots = "1".equals(live) || "true".equalsIgnoreCase(live);
        sb.append("- Mode: ").append(liveBots ? "load (" + players + " simulated bots)" : "idle (no traffic)").append("\n");
        sb.append("- Duration: ").append(durationSec).append("s per run\n");
        sb.append("- Runs: ").append(runs).append(" (median reported)\n");
        sb.append("- Server: HytaleServer.jar (--bind 0.0.0.0:5520 --auth-mode=offline --disable-sentry)\n\n");
        sb.append("## Results\n");
        sb.append("| Run | Baseline CPU % | Beacon CPU % | CPU delta | Baseline RSS MB | Beacon RSS MB | MB delta |\n");
        sb.append("|-----|---------------:|-------------:|------:|----------------:|--------------:|-----:|\n");
        for (int i = 0; i < runs; i++) {
            RunResult b = baseline.get(i);
            RunResult be = beacon.get(i);
            sb.append(String.format("| %d | %.2f | %.2f | %.2f | %d | %d | %d |%n",
                    i + 1, b.cpuPct, be.cpuPct, be.cpuPct - b.cpuPct,
                    b.rssMb, be.rssMb, be.rssMb - b.rssMb));
        }
        sb.append(String.format("| **Median** | %.2f | %.2f | **%.2f%%** | %d | %d | **%d** |%n",
                medCpuB, medCpuBe, cpuD, medMemB, medMemBe, memD));
        sb.append("\n## Verdict\n");
        sb.append(String.format("- CPU overhead median: **%.2f%%** vs target < 1%%: **%s**%n",
                cpuD, cpuPass ? "PASS" : "FAIL"));
        sb.append(String.format("- Memory overhead median: **%d MB** vs target < %d MB: **%s**%n",
                memD, HARD_FAIL_MEM_MB, memPass ? "PASS" : "FAIL"));
        sb.append("\n_This file is regenerated by `./gradlew :bench:overheadBench`. Do not hand-edit._\n");
        Files.writeString(reportPath, sb.toString());
    }

    private record RunResult(double cpuPct, long rssMb) {}
}
