package com.mythlane.beacon.instrum;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-world player-count state, source of truth for the
 * {@code hytale.players.online} gauge. Mutations are idempotent by set
 * semantics so a re-delivered join or leave event is a no-op.
 */
public final class PlayerCountRegistry {

    private final ConcurrentHashMap<UUID, Set<UUID>> state = new ConcurrentHashMap<>();

    public void playerJoined(UUID worldUuid, UUID playerUuid) {
        state.computeIfAbsent(worldUuid, w -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    public void playerLeft(UUID worldUuid, UUID playerUuid) {
        Set<UUID> set = state.get(worldUuid);
        if (set != null) {
            set.remove(playerUuid);
        }
    }

    public void worldRemoved(UUID worldUuid) {
        state.remove(worldUuid);
    }

    public long snapshot(UUID worldUuid) {
        Set<UUID> set = state.get(worldUuid);
        return set == null ? 0L : (long) set.size();
    }

    public Set<UUID> trackedWorlds() {
        return state.keySet();
    }
}
