package com.mythlane.beacon.binding;

import java.util.UUID;

import com.mythlane.beacon.instrum.PlayerCountRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

        r.playerLeft(world, player);
        assertThat(r.snapshot(world)).isEqualTo(0L);

        r.playerJoined(world, player);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }

    @Test
    void doubleJoinIsIdempotentAcrossEventReentry() {
        PlayerCountRegistry r = new PlayerCountRegistry();
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        r.playerJoined(world, player);
        r.playerJoined(world, player);
        assertThat(r.snapshot(world)).isEqualTo(1L);
    }
}
