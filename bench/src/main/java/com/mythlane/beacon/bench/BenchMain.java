package com.mythlane.beacon.bench;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Bench CLI. {@code --mode=overhead|smoke}, {@code --target=host:port},
 * {@code --players=N}, {@code --duration=S}, {@code --runs=R},
 * {@code --report=path}.
 */
public final class BenchMain {

    private BenchMain() {}

    public static void main(String[] args) throws Exception {
        String mode = "overhead";
        String target = "localhost:5520";
        int players = 20;
        int duration = 600;
        int runs = 3;
        Path report = Paths.get("docs", "perf-quic.md");

        for (String a : args) {
            if (a.startsWith("--mode=")) mode = a.substring(7);
            else if (a.startsWith("--target=")) target = a.substring(9);
            else if (a.startsWith("--players=")) players = Integer.parseInt(a.substring(10));
            else if (a.startsWith("--duration=")) duration = Integer.parseInt(a.substring(11));
            else if (a.startsWith("--runs=")) runs = Integer.parseInt(a.substring(7));
            else if (a.startsWith("--report=")) report = Paths.get(a.substring(9));
        }

        if ("smoke".equalsIgnoreCase(mode)) {
            SmokeTest.main(new String[]{"--target=" + target});
            return;
        }

        OverheadHarness harness = new OverheadHarness(target, players, duration, runs, report);
        int code = harness.run();
        System.exit(code);
    }
}
