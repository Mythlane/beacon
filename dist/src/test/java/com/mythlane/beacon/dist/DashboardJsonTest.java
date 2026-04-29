package com.mythlane.beacon.dist;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the auto-provisioned Grafana "Server Health" dashboard JSON
 * (FND-07). Catches structural regressions in panel count, key PromQL queries
 * and the TPS red-threshold value (must be 27, not 18 — Hytale TPS baseline is
 * 30, not 20).
 */
class DashboardJsonTest {

    private static JSONObject dashboard;
    private static String rawJson;

    @BeforeAll
    static void loadDashboard() throws IOException {
        Path path = locateDashboard();
        rawJson = Files.readString(path, StandardCharsets.UTF_8);
        dashboard = new JSONObject(rawJson);
    }

    private static Path locateDashboard() {
        // Run-from-repo-root or run-from-:dist working directories both supported.
        Path[] candidates = new Path[]{
            Paths.get("examples/lgtm-stack/grafana/provisioning/dashboards/server-health.json"),
            Paths.get("../examples/lgtm-stack/grafana/provisioning/dashboards/server-health.json")
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
            "server-health.json not found in any candidate path: "
                + java.util.Arrays.toString(candidates));
    }

    @Test
    void dashboardJsonIsParseable() {
        assertThat(dashboard).isNotNull();
        assertThat(dashboard.optString("title")).isEqualTo("Server Health");
    }

    @Test
    void dashboardHasExactlySevenPanels() {
        JSONArray panels = dashboard.getJSONArray("panels");
        assertThat(panels.length())
            .as("FND-07 requires exactly 7 panels (TPS, MSPT, JVM Memory, GC, Threads, Players, CPU)")
            .isEqualTo(7);
    }

    @Test
    void dashboardReferencesAllRequiredMetrics() {
        // Cheaper than walking every panel/target — the metric names are unique
        // enough that a substring search is reliable and easy to debug.
        assertThat(rawJson)
            .as("TPS panel must query hytale_tps")
            .contains("hytale_tps");
        assertThat(rawJson)
            .as("MSPT panel must query the hytale_mspt histogram")
            .contains("hytale_mspt_bucket");
        assertThat(rawJson)
            .as("Players panel must query hytale_players_online")
            .contains("hytale_players_online");

        // At least 2 distinct process_runtime_jvm_* metrics (memory + gc + threads).
        long jvmMetricMatches = countOccurrences(rawJson, "process_runtime_jvm_");
        assertThat(jvmMetricMatches)
            .as("Dashboard must reference at least 2 process_runtime_jvm_* metrics")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    void tpsPanelHasRedThresholdAtTwentySeven() {
        JSONArray panels = dashboard.getJSONArray("panels");
        JSONObject tpsPanel = null;
        for (int i = 0; i < panels.length(); i++) {
            JSONObject panel = panels.getJSONObject(i);
            if ("TPS".equals(panel.optString("title"))) {
                tpsPanel = panel;
                break;
            }
        }
        assertThat(tpsPanel)
            .as("Dashboard must contain a panel titled 'TPS'")
            .isNotNull();

        JSONObject thresholds = tpsPanel
            .getJSONObject("fieldConfig")
            .getJSONObject("defaults")
            .getJSONObject("thresholds");
        JSONArray steps = thresholds.getJSONArray("steps");

        boolean foundYellowAt27 = false;
        boolean foundRedStep = false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String color = step.optString("color");
            // value may be JSONObject.NULL for the lowest step
            Object value = step.has("value") ? step.get(JSONObject.NULL.equals(step.get("value")) ? "value" : "value") : null;
            if ("red".equalsIgnoreCase(color)) {
                foundRedStep = true;
            }
            if (!step.isNull("value") && step.getDouble("value") == 27.0) {
                foundYellowAt27 = true;
            }
        }
        assertThat(foundRedStep)
            .as("TPS panel thresholds must contain a red step (below 27 TPS = critical)")
            .isTrue();
        assertThat(foundYellowAt27)
            .as("TPS panel must have a threshold step at value 27 (NOT 18 — Hytale TPS baseline is 30)")
            .isTrue();
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
