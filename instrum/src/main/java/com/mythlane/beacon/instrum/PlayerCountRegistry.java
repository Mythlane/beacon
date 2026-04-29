package com.mythlane.beacon.instrum;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-world player-count state, source of truth for the
 * {@code hytale.players.online} gauge.
 *
 * <p>Storage: {@code ConcurrentHashMap<UUID worldUuid, KeySetView<UUID, Boolean>>}.
 * Membership in the per-world set represents "currently online" for that world.
 * {@link #playerJoined(UUID, UUID)} and {@link #playerLeft(UUID, UUID)} are
 * <strong>idempotent</strong> by construction (set semantics) — covers R8 even
 * though decompiled Hytale evidence shows {@code PlayerDisconnectEvent} is
 * single-fire.
 *
 * <p>Performance: O(1) amortized, lock-free for the hot paths. Compatible with
 * PITFALLS P4 (no allocation per call once the set exists).
 *
 * <p>Threat T-1-04-03 mitigation: every mutation goes through
 * {@code ConcurrentHashMap}'s atomic primitives; no compound check-then-act.
 */
public final class PlayerCountRegistry {

    private final ConcurrentHashMap<UUID, Set<UUID>> state = new ConcurrentHashMap<>();

    /**
     * Mark {@code playerUuid} as online in {@code worldUuid}. Idempotent: a
     * second call with the same pair is a no-op (set semantics).
     */
    public void playerJoined(UUID worldUuid, UUID playerUuid) {
        state.computeIfAbsent(worldUuid, w -> ConcurrentHashMap.newKeySet()).add(playerUuid);
    }

    /**
     * Mark {@code playerUuid} as offline in {@code worldUuid}. Idempotent: a
     * second call with the same pair is a no-op (the underlying remove returns
     * {@code false} on the second invocation, so the visible count is unchanged).
     */
    public void playerLeft(UUID worldUuid, UUID playerUuid) {
        Set<UUID> set = state.get(worldUuid);
        if (set != null) {
            set.remove(playerUuid);
        }
    }

    /**
     * Drop all bookkeeping for {@code worldUuid} (e.g. on {@code RemoveWorldEvent}).
     * The next {@link #snapshot(UUID)} returns 0. Avoids "frozen ghost world"
     * readings per RESEARCH.md.
     */
    public void worldRemoved(UUID worldUuid) {
        state.remove(worldUuid);
    }

    /** Online player count for {@code worldUuid}; 0 if the world is unknown. */
    public long snapshot(UUID worldUuid) {
        Set<UUID> set = state.get(worldUuid);
        return set == null ? 0L : (long) set.size();
    }

    /** Set of worlds with at least one tracked player. Used by gauge callbacks. */
    public Set<UUID> trackedWorlds() {
        return state.keySet();
    }
}
