package net.knightsandkings.knk.paper.privacy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.privacy.ErasedAccount;

/** PandiO/knk-workspace#184: an online player of an erased account is kicked with the neutral message. */
class BukkitErasureKickerTest {

    private static final UUID ONLINE = UUID.randomUUID();
    private static final UUID OFFLINE = UUID.randomUUID();

    @Test
    void kicksOnlyAnOnlinePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        Map<UUID, Player> online = Map.of(ONLINE, player);
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, online::get, "Your account data has been deleted.");

        assertTrue(kicker.kick(new ErasedAccount(1, 42, ONLINE)).join());
        verify(player).kick(Component.text("Your account data has been deleted."));

        assertFalse(kicker.kick(new ErasedAccount(2, 43, OFFLINE)).join());
        assertFalse(kicker.kick(new ErasedAccount(3, 44, null)).join());
    }

    @Test
    void aPlayerAlreadyLeaving_IsNotKicked() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(false);
        BukkitErasureKicker kicker = new BukkitErasureKicker(Runnable::run, uuid -> player, "x");

        assertFalse(kicker.kick(new ErasedAccount(1, 42, ONLINE)).join());
        verify(player, never()).kick(any(Component.class));
    }
}
