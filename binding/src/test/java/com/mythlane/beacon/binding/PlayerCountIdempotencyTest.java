package com.mythlane.beacon.binding;

import java.util.UUID;

import com.mythlane.beacon.instrum.PlayerCountRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensive contract for the player-disconnect handler.
 *
 * <p>PlayerDisconnectEvent is single-fire per decompiled Universe.removePlayer() lines 966-968.
 * Idempotent handler is defensive insurance against future Hytale changes
 * and against any handler re-entry via world.execute() shutdown races.
 */
class PlayerCountIdempotencyTest {

    @Test
    void doubleDisconnectDoesNotUnderflow() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        r.playerJoined(world, player);
        assertThat(r.snapshot(world)).isEqualTo(1L);

        r.playerLeft(world, player);
        assertThat(r.snapshot(world)).isEqualTo(0L);

        // Defensive: a second disconnect for the same player must not push count
        // below zero or otherwise corrupt internal state.
        r.playerLeft(world, player);
        assertThat(r.snapshot(world)).isEqualTo(0L);

        // And a re-join afterwards still works.
        r.playerJoined(world, player);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }

    @Test
    void doubleJoinIsIdempotentAcrossEventReentry() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        r.playerJoined(world, player);
        r.playerJoined(world, player); // simulated re-entry / replayed event
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }
}
