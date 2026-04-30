package com.mythlane.beacon.binding;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the Beacon mod data directory naming to Hytale's underscore
 * convention (Group_Name). Hytale's runtime allocates each Java plugin
 * a data directory at mods/<group>_<name>/ via
 * PluginManager.MODS_PATH.resolve(manifest.getGroup() + "_" + manifest.getName())
 * (see decompiled com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin).
 *
 * Beacon's manifest declares Group=Mythlane, Name=Beacon, so the data dir
 * must be Mythlane_Beacon (underscore), not Mythlane.Beacon (point).
 *
 * If this test fails, you are about to ship a build that cannot find its
 * config.toml on a real Hytale server. Do NOT update this assertion to
 * match the code; fix the code instead.
 */
class BeaconPluginLifecyclePathTest {

    @Test
    void defaultConfigPathFollowsHytaleUnderscoreConvention() {
        assertThat(BeaconPluginLifecycle.DEFAULT_CONFIG_PATH)
            .isEqualTo(Path.of("mods", "Mythlane_Beacon", "config.toml"));
    }
}
