package net.knightsandkings.knk.paper.freeze;

import net.knightsandkings.knk.core.freeze.FreezeFlightLedger.Source;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#155: frozen players aren't kicked for flying, without allow-flight for the whole server. */
class FreezeFlightGuardTest {
    private final UUID playerId = UUID.randomUUID();
    private final AtomicBoolean allowFlight = new AtomicBoolean(false);
    private Player player;
    private MockedStatic<Bukkit> bukkit;
    private FreezeFlightGuard guard;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getAllowFlight()).thenAnswer(inv -> allowFlight.get());
        doAnswer(inv -> {
            allowFlight.set(inv.getArgument(0));
            return null;
        }).when(player).setAllowFlight(org.mockito.ArgumentMatchers.anyBoolean());

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        guard = new FreezeFlightGuard(mock(Plugin.class));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void aFrozenPlayerMayFlyUntilTheFreezeEnds() {
        guard.frozen(playerId, Source.ENCHANTMENT);
        assertTrue(allowFlight.get());
        assertTrue(guard.isGranted(playerId));

        guard.released(playerId, Source.ENCHANTMENT);
        assertFalse(allowFlight.get());
        assertFalse(guard.isGranted(playerId));
    }

    @Test
    void anEnchantmentFreezeEndingDuringAStaffFreezeKeepsFlightAllowed() {
        guard.frozen(playerId, Source.ADMIN);
        guard.frozen(playerId, Source.ENCHANTMENT);
        guard.released(playerId, Source.ENCHANTMENT);
        assertTrue(allowFlight.get());

        guard.released(playerId, Source.ADMIN);
        assertFalse(allowFlight.get());
    }

    @Test
    void aPlayerWhoCouldFlyKeepsFlight() {
        allowFlight.set(true);
        guard.frozen(playerId, Source.ADMIN);
        guard.released(playerId, Source.ADMIN);
        assertTrue(allowFlight.get());
        verify(player, never()).setAllowFlight(false);
    }

    @Test
    void flightSwitchedOffByAnotherFeatureDuringTheFreezeStaysOff() {
        guard.frozen(playerId, Source.ENCHANTMENT);
        allowFlight.set(false); // e.g. SiegeService.prepareForMatch
        guard.released(playerId, Source.ENCHANTMENT);
        assertFalse(allowFlight.get());
    }

    @Test
    void aCreativePlayerKeepsFlightAtTheEnd() {
        guard.frozen(playerId, Source.ADMIN);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        guard.released(playerId, Source.ADMIN);
        assertTrue(allowFlight.get());
    }

    @Test
    void quittingHandsTheOwnSettingBackBeforeTheSave() {
        guard.frozen(playerId, Source.ADMIN);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        guard.onQuit(quit);
        assertFalse(allowFlight.get());
        assertFalse(guard.isGranted(playerId));
    }

    @Test
    void pluginDisableHandsEverySettingBack() {
        guard.frozen(playerId, Source.ENCHANTMENT);
        guard.releaseAll();
        assertFalse(allowFlight.get());
        assertFalse(guard.isGranted(playerId));
    }

    @Test
    void aFrozenPlayerCannotStartFlying() {
        guard.frozen(playerId, Source.ENCHANTMENT);
        PlayerToggleFlightEvent event = new PlayerToggleFlightEvent(player, true);
        guard.onToggleFlight(event);
        assertTrue(event.isCancelled());

        guard.released(playerId, Source.ENCHANTMENT);
        PlayerToggleFlightEvent later = new PlayerToggleFlightEvent(player, true);
        guard.onToggleFlight(later);
        assertFalse(later.isCancelled());
    }

    @Test
    void anOfflinePlayerIsNotTouched() {
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(null);
        guard.frozen(playerId, Source.ADMIN);
        assertFalse(guard.isGranted(playerId));
    }
}
