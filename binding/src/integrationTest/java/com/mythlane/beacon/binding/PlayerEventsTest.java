package com.mythlane.beacon.binding;

import java.util.UUID;
import java.util.function.Consumer;

import com.hypixel.hytale.event.IEventRegistry;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;

import com.mythlane.beacon.instrum.PlayerCountRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration test for {@link EventBindings} (FND-05 / R7).
 *
 * <p>Verifies the {@code registerGlobal} vs {@code register} distinction
 * mandated by Hytale's event registry semantics:
 *
 * <ul>
 *   <li>{@link PlayerReadyEvent} (KeyType=String) → MUST be registered via
 *       {@code registerGlobal}; calling {@code register} compiles but never fires.</li>
 *   <li>{@link PlayerDisconnectEvent} (KeyType=Void) → MUST be registered via
 *       plain {@code register}.</li>
 * </ul>
 */
class PlayerEventsTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void playerReadyRegistersGlobal_disconnectRegistersPlain() {
        IEventRegistry events = mock(IEventRegistry.class);
        EventBindings bindings = new EventBindings(new PlayerCountRegistry());

        bindings.register(events);

        // R7: PlayerReadyEvent goes through registerGlobal (KeyType=String).
        verify(events).registerGlobal(eq(PlayerReadyEvent.class), any(Consumer.class));
        // PlayerDisconnectEvent goes through plain register (KeyType=Void).
        verify(events).register(eq(PlayerDisconnectEvent.class), any(Consumer.class));

        // Cross-check: registerGlobal must NOT be used for the Void-keyed disconnect.
        verify(events, never()).registerGlobal(eq(PlayerDisconnectEvent.class), any(Consumer.class));

        // Note: we don't write a `verify(events, never()).register(PlayerReadyEvent.class, ...)`
        // assertion because the compiler ALREADY rejects that call — KeyType=String forces
        // the keyed register(Class, KeyType, Consumer) overload, while register(Class, Consumer)
        // requires KeyType=Void. The compile-time signature is itself the strongest possible
        // verification that we can't accidentally use the wrong variant.
    }

    @Test
    void registeredConsumersDriveRegistryViaPureHandler() {
        // We can't mock Hytale's Player / PlayerRef / World because their
        // <clinit>s pull in the asset-store machinery (HytaleLogger, MetricsRegistry,
        // GameplayConfig). Instead we exercise the pure UUID-level handler
        // (EventBindings.applyJoin / applyLeave) which is what the captured
        // consumers ultimately delegate to once they've extracted IDs.
        PlayerCountRegistry registry = new PlayerCountRegistry();
        EventBindings bindings = new EventBindings(registry);

        UUID worldUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        bindings.applyJoin(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(1L);

        bindings.applyLeave(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(0L);

        // R8 defensive: a second leave must be a no-op (PlayerDisconnectEvent
        // is single-fire per decompiled Universe.removePlayer() but we still
        // protect against handler re-entry).
        bindings.applyLeave(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(0L);
    }

    @Test
    void pureHandlerIsNullSafe() {
        PlayerCountRegistry registry = new PlayerCountRegistry();
        EventBindings bindings = new EventBindings(registry);

        // Null inputs must never throw.
        bindings.applyJoin(null, null);
        bindings.applyLeave(null, UUID.randomUUID());
        bindings.applyJoin(UUID.randomUUID(), null);

        assertThat(registry.trackedWorlds()).isEmpty();
    }
}
