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
            .as("Dashboard requires exactly 7 panels (TPS, MSPT, JVM Memory, GC, Threads, Players, CPU)")
            .isEqualTo(7);
    }

    @Test
    void dashboardReferencesAllRequiredMetrics() {
        assertThat(rawJson)
            .as("TPS panel must query hytale_tps")
            .contains("hytale_tps");
        assertThat(rawJson)
            .as("MSPT panel must query the hytale_mspt histogram")
            .contains("hytale_mspt_nanoseconds_bucket");
        assertThat(rawJson)
            .as("Players panel must query hytale_players_online")
            .contains("hytale_players_online");

        long jvmMetricMatches = countOccurrences(rawJson, "jvm_");
        assertThat(jvmMetricMatches)
            .as("Dashboard must reference at least 2 jvm_* metrics")
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
            if ("red".equalsIgnoreCase(color)) {
                foundRedStep = true;
            }
            if (!step.isNull("value") && step.getDouble("value") == 27.0) {
                foundYellowAt27 = true;
            }
        }
        assertThat(foundRedStep)
            .as("TPS panel thresholds must contain a red step")
            .isTrue();
        assertThat(foundYellowAt27)
            .as("TPS panel must have a threshold step at 27")
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
