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
 * Wires Hytale player-lifecycle events into Beacon's
 * {@link PlayerCountRegistry}, applying the
 * <strong>{@code registerGlobal} vs {@code register}</strong> rule from
 * API-REFERENCE.md §Phase 2 / mythlane learning:
 *
 * <ul>
 *   <li>{@link PlayerReadyEvent} has KeyType=String → MUST use
 *       {@link IEventRegistry#registerGlobal(Class, java.util.function.Consumer) registerGlobal}.
 *       Calling {@code register(...)} on a non-Void KeyType compiles but never fires.</li>
 *   <li>{@link PlayerDisconnectEvent} has KeyType=Void → MUST use plain
 *       {@link IEventRegistry#register(Class, java.util.function.Consumer) register}.</li>
 * </ul>
 *
 * <p>Idempotency is handled inside {@link PlayerCountRegistry} (set semantics) —
 * decompiled evidence shows {@code PlayerDisconnectEvent} is single-fire, but a
 * re-entrant world.execute() shutdown race could still re-deliver it.
 */
public final class EventBindings {

    private static final Logger LOG = LoggerFactory.getLogger(EventBindings.class);

    private final PlayerCountRegistry registry;

    public EventBindings(PlayerCountRegistry registry) {
        this.registry = registry;
    }

    /** Register both event handlers against the supplied plugin event registry. */
    public void register(IEventRegistry events) {
        // KeyType=String → registerGlobal MANDATORY (API-REFERENCE.md §Phase 2).
        Consumer<PlayerReadyEvent> readyHandler = this::onPlayerReady;
        events.registerGlobal(PlayerReadyEvent.class, readyHandler);

        // KeyType=Void → plain register (registerGlobal would also work but is
        // semantically wrong; we keep the explicit pairing for clarity).
        Consumer<PlayerDisconnectEvent> disconnectHandler = this::onPlayerDisconnect;
        events.register(PlayerDisconnectEvent.class, disconnectHandler);
    }

    /** Visible for tests: the join-side handler. */
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

    /** Visible for tests: the leave-side handler. */
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

    /**
     * Pure UUID-level join handler. Decoupled from Hytale event types so it can
     * be exercised in tests without standing up Player/World/PlayerRef
     * (their {@code <clinit>}s pull in asset-store machinery unavailable to
     * the test JVM). Null-safe.
     */
    void applyJoin(UUID worldUuid, UUID playerUuid) {
        if (worldUuid == null || playerUuid == null) return;
        registry.playerJoined(worldUuid, playerUuid);
    }

    /** Pure UUID-level leave handler. Mirror of {@link #applyJoin}. Idempotent. */
    void applyLeave(UUID worldUuid, UUID playerUuid) {
        if (worldUuid == null || playerUuid == null) return;
        registry.playerLeft(worldUuid, playerUuid);
    }
}
