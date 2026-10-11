package net.knightsandkings.knk.paper.user;

import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The join-loading hold must never leak into a player's saved playerdata: an invulnerable flag
 * left behind there used to be read back as the "previous" value on the next join and kept for good.
 */
class JoinLoadingGuardTest {

    private final Plugin plugin = mock(Plugin.class);
    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final BukkitTask reminder = mock(BukkitTask.class);
    private final JoinLoadingGuard guard = new JoinLoadingGuard(plugin, permissible);
    private final Player player = mock(Player.class);
    private final UUID uuid = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(reminder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("JoinLoadingGuardTest"));
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Steve");
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    /** Holds the player, then reports them as still in the hold's ADVENTURE mode. */
    private void holdPlayer() {
        guard.hold(player);
        verify(player).setGameMode(GameMode.ADVENTURE);
        verify(player).setInvulnerable(true);
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
    }

    @Test
    void releaseHandsBackTheWorldsGameSettingsMode() {
        JoinLoadingGuard settingsGuard = new JoinLoadingGuard(plugin, permissible, p -> GameMode.CREATIVE);
        settingsGuard.hold(player);
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);

        settingsGuard.release(player);

        verify(player).setGameMode(GameMode.CREATIVE);
        verify(player, never()).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    void aMissingGameSettingsModeMeansSurvival() {
        JoinLoadingGuard settingsGuard = new JoinLoadingGuard(plugin, permissible, p -> null);
        settingsGuard.hold(player);
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);

        settingsGuard.release(player);

        verify(player).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    void releaseRestoresSurvivalAndClearsInvulnerability() {
        holdPlayer();

        guard.release(player);

        assertFalse(guard.isLoading(uuid));
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setInvulnerable(false);
        verify(reminder).cancel();
    }

    @Test
    void quittingDuringTheHoldRestoresThePlayerBeforeTheyAreSaved() {
        holdPlayer();

        guard.forget(player);

        assertFalse(guard.isLoading(uuid));
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setInvulnerable(false);
        verify(reminder).cancel();
    }

    @Test
    void quittingLeavesAGamemodeSomethingElseAlreadyChangedAlone() {
        holdPlayer();
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        guard.forget(player);

        verify(player, never()).setGameMode(GameMode.SURVIVAL);
        verify(player).setInvulnerable(false);
    }

    @Test
    void quittingWithoutBeingHeldTouchesNothing() {
        guard.forget(player);

        verify(player, never()).setGameMode(any());
        verify(player, never()).setInvulnerable(anyBoolean());
    }

    @Test
    void anAccountStuckInvulnerableFromAnEarlierLeakIsHealedOnRelease() {
        // Saved with Invulnerable:1b by the old quit path - nothing in the plugin sets it on purpose.
        when(player.isInvulnerable()).thenReturn(true);
        holdPlayer();

        guard.release(player);

        verify(player).setInvulnerable(false);
    }

    @Test
    void releaseAllRestoresEveryoneStillHeldOnDisable() {
        Player other = mock(Player.class);
        UUID otherUuid = UUID.randomUUID();
        when(other.getUniqueId()).thenReturn(otherUuid);
        holdPlayer();
        guard.hold(other);
        when(other.getGameMode()).thenReturn(GameMode.ADVENTURE);
        bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
        bukkit.when(() -> Bukkit.getPlayer(otherUuid)).thenReturn(other);

        guard.releaseAll();

        assertFalse(guard.isLoading(uuid));
        assertFalse(guard.isLoading(otherUuid));
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setInvulnerable(false);
        verify(other).setGameMode(GameMode.SURVIVAL);
        verify(other).setInvulnerable(false);
    }

    @Test
    void releaseAllDropsPlayersNoLongerOnline() {
        holdPlayer();

        guard.releaseAll();

        assertFalse(guard.isLoading(uuid));
        verify(reminder).cancel();
    }

    @Test
    void ownersAreNeverHeld() {
        when(permissible.hasPermission(player, "knk.mode.owner")).thenReturn(true);

        guard.hold(player);
        guard.forget(player);

        assertFalse(guard.isLoading(uuid));
        verify(player, never()).setGameMode(any());
        verify(player, never()).setInvulnerable(anyBoolean());
    }

    @Test
    void holdMarksThePlayerAsLoading() {
        holdPlayer();

        assertTrue(guard.isLoading(uuid));
    }
}
