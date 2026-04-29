package com.mythlane.beacon.dist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogTest {

    private static final List<String> REQUIRED_IDS = Arrays.asList(
            "FND-01", "FND-02", "FND-03", "FND-04", "FND-05",
            "FND-06", "FND-07", "FND-08", "FND-09", "FND-10",
            "REL-01", "REL-02"
    );

    private static Path repoRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cur.resolve("settings.gradle"))
                    && Files.exists(cur.resolve("CHANGELOG.md"))) {
                return cur;
            }
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        throw new IllegalStateException(
                "Could not locate repo root (settings.gradle + CHANGELOG.md) starting from "
                        + Paths.get("").toAbsolutePath());
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p));
    }

    @Test
    void changelogFileExistsAtRepoRoot() {
        Path changelog = repoRoot().resolve("CHANGELOG.md");
        assertThat(changelog).as("CHANGELOG.md at repo root").exists().isRegularFile();
    }

    @Test
    void changelogContainsKeepAChangelogSectionHeaders() throws IOException {
        String body = read(repoRoot().resolve("CHANGELOG.md"));
        assertThat(body)
                .as("Keep-a-Changelog section headers")
                .contains("## [0.1.0-alpha]")
                .contains("### Added");
    }

    @Test
    void changelogReferencesEveryPhase1RequirementId() throws IOException {
        String body = read(repoRoot().resolve("CHANGELOG.md"));
        List<String> missing = new ArrayList<>();
        for (String id : REQUIRED_IDS) {
            if (!body.contains(id)) {
                missing.add(id);
            }
        }
        assertThat(missing)
                .as("CHANGELOG.md must mention every FND-01..FND-10 + REL-01..REL-02; missing: %s", missing)
                .isEmpty();
    }

    @Test
    void readmeReferencesLgtmStackPath() throws IOException {
        String body = read(repoRoot().resolve("README.md"));
        assertThat(body)
                .as("README.md must reference examples/lgtm-stack")
                .contains("examples/lgtm-stack");
    }

    @Test
    void licenseIsMitAndCopyrightedToMythlane() throws IOException {
        String body = read(repoRoot().resolve("LICENSE"));
        assertThat(body)
                .as("LICENSE must be MIT and copyrighted to Mythlane")
                .contains("MIT License")
                .contains("Mythlane");
    }
}
