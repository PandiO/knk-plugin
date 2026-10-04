package net.knightsandkings.knk.paper.siege;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-28: members are put into survival without flight when they are sent to the hub, so nobody
 * fights the match immune to damage (a creative player's hits never reach the siege combat rules,
 * which is why the damager got no "inside their spawn area" message).
 */
class SiegeMatchPreparationTest {

    @Test
    void creativeMemberIsSwitchedToSurvival() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        SiegeService.prepareForMatch(player);

        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setFlying(false);
        verify(player).setAllowFlight(false);
    }

    @Test
    void spectatorAndAdventureMembersAreSwitchedToSurvival() {
        for (GameMode mode : new GameMode[] {GameMode.SPECTATOR, GameMode.ADVENTURE}) {
            Player player = mock(Player.class);
            when(player.getGameMode()).thenReturn(mode);

            SiegeService.prepareForMatch(player);

            verify(player).setGameMode(GameMode.SURVIVAL);
        }
    }

    @Test
    void survivalMemberKeepsGameModeButLosesFlight() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

        SiegeService.prepareForMatch(player);

        verify(player, never()).setGameMode(GameMode.SURVIVAL);
        verify(player).setFlying(false);
        verify(player).setAllowFlight(false);
    }
}
