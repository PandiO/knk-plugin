package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.kyori.adventure.bossbar.BossBar;

/** The boss bar lifecycle and the action-bar arrow maths (DESIGN §6.4). */
class NavigationHudTest {

    private final NavigationHud hud = new NavigationHud(EtaEstimator.defaults());

    @Test
    void arrowsFollowTheRelativeBearing() {
        // Bukkit yaw 0 = south (+z). Facing south, a target further south is ahead, west (-x) is right.
        assertEquals("⬆", NavigationHud.arrow(0, 0, 10));
        assertEquals("➡", NavigationHud.arrow(0, -10, 0));
        assertEquals("⬅", NavigationHud.arrow(0, 10, 0));
        assertEquals("⬇", NavigationHud.arrow(0, 0, -10));
        // Facing east (yaw 270): a target east is ahead, south is right.
        assertEquals("⬆", NavigationHud.arrow(270, 10, 0));
        assertEquals("➡", NavigationHud.arrow(270, 0, 10));
        assertEquals("⬈", NavigationHud.arrow(0, -10, 10));
    }

    @Test
    void arrowIndexBandsAre45Degrees() {
        assertEquals(0, NavigationHud.arrowIndex(0));
        assertEquals(0, NavigationHud.arrowIndex(22));
        assertEquals(1, NavigationHud.arrowIndex(23));
        assertEquals(2, NavigationHud.arrowIndex(90));
        assertEquals(6, NavigationHud.arrowIndex(-90));
        assertEquals(4, NavigationHud.arrowIndex(180));
        assertEquals(0, NavigationHud.arrowIndex(359));
        assertEquals(0, NavigationHud.arrowIndex(720));
    }

    @Test
    void theBossBarIsShownOnceUpdatedInPlaceAndHiddenOnEnd() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);

        hud.update(player, "Kardenna", 340, 0.2);
        hud.update(player, "Turn left onto Main Street", 300, 0.3);

        verify(player, times(1)).showBossBar(any(BossBar.class));
        assertTrue(hud.isShowing(id));

        hud.hide(player);

        verify(player).hideBossBar(any(BossBar.class));
        assertFalse(hud.isShowing(id));
    }

    @Test
    void theArrowIsSentAsAnActionBar() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 0, 65, 0, 0f, 0f));

        hud.arrowTowards(player, 0, 10);

        verify(player).sendActionBar(any(net.kyori.adventure.text.Component.class));
    }
}
