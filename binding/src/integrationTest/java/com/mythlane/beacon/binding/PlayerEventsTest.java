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

class PlayerEventsTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void playerReadyRegistersGlobal_disconnectRegistersPlain() {
        IEventRegistry events = mock(IEventRegistry.class);
        EventBindings bindings = new EventBindings(new PlayerCountRegistry());

        bindings.register(events);

        verify(events).registerGlobal(eq(PlayerReadyEvent.class), any(Consumer.class));
        verify(events).register(eq(PlayerDisconnectEvent.class), any(Consumer.class));
        verify(events, never()).registerGlobal(eq(PlayerDisconnectEvent.class), any(Consumer.class));
    }

    @Test
    void registeredConsumersDriveRegistryViaPureHandler() {
        // Hytale's Player / PlayerRef / World can't be mocked: their <clinit>
        // pulls in the asset store. The pure UUID-level handlers are what the
        // captured consumers delegate to once they've extracted IDs.
        PlayerCountRegistry registry = new PlayerCountRegistry();
        EventBindings bindings = new EventBindings(registry);

        UUID worldUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        bindings.applyJoin(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(1L);

        bindings.applyLeave(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(0L);

        bindings.applyLeave(worldUuid, playerUuid);
        assertThat(registry.snapshot(worldUuid)).isEqualTo(0L);
    }

    @Test
    void pureHandlerIsNullSafe() {
        PlayerCountRegistry registry = new PlayerCountRegistry();
        EventBindings bindings = new EventBindings(registry);

        bindings.applyJoin(null, null);
        bindings.applyLeave(null, UUID.randomUUID());
        bindings.applyJoin(UUID.randomUUID(), null);

        assertThat(registry.trackedWorlds()).isEmpty();
    }
}
