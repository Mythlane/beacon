package com.mythlane.beacon.binding;

import java.util.UUID;
import java.util.function.Consumer;

import com.hypixel.hytale.event.IEventRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import com.mythlane.beacon.instrum.PlayerCountRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires Hytale player-lifecycle events into {@link PlayerCountRegistry}.
 * {@link PlayerReadyEvent} (KeyType=String) must use
 * {@link IEventRegistry#registerGlobal} or it never fires;
 * {@link PlayerDisconnectEvent} (KeyType=Void) must use plain
 * {@link IEventRegistry#register}.
 */
public final class EventBindings {

    private static final Logger LOG = LoggerFactory.getLogger(EventBindings.class);

    private final PlayerCountRegistry registry;

    public EventBindings(PlayerCountRegistry registry) {
        this.registry = registry;
    }

    public void register(IEventRegistry events) {
        Consumer<PlayerReadyEvent> readyHandler = this::onPlayerReady;
        events.registerGlobal(PlayerReadyEvent.class, readyHandler);

        Consumer<PlayerDisconnectEvent> disconnectHandler = this::onPlayerDisconnect;
        events.register(PlayerDisconnectEvent.class, disconnectHandler);
    }

    void onPlayerReady(PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            if (player == null) return;
            UUID playerUuid = player.getUuid();
            World world = player.getWorld();
            if (world == null) return;
            UUID worldUuid = world.getWorldConfig().getUuid();
            applyJoin(worldUuid, playerUuid);
        } catch (Throwable t) {
            LOG.warn("Beacon onPlayerReady handler threw", t);
        }
    }

    void onPlayerDisconnect(PlayerDisconnectEvent event) {
        try {
            PlayerRef ref = event.getPlayerRef();
            if (ref == null) return;
            UUID playerUuid = ref.getUuid();
            UUID worldUuid = ref.getWorldUuid();
            applyLeave(worldUuid, playerUuid);
        } catch (Throwable t) {
            LOG.warn("Beacon onPlayerDisconnect handler threw", t);
        }
    }

    void applyJoin(UUID worldUuid, UUID playerUuid) {
        if (worldUuid == null || playerUuid == null) return;
        registry.playerJoined(worldUuid, playerUuid);
    }

    void applyLeave(UUID worldUuid, UUID playerUuid) {
        if (worldUuid == null || playerUuid == null) return;
        registry.playerLeft(worldUuid, playerUuid);
    }
}
