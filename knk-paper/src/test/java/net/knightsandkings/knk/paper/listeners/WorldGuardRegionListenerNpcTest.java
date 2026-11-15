package net.knightsandkings.knk.paper.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.npc.nav.NpcEntities;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionTracker;

/** NAV-08: player-shaped NPCs never reach region tracking (each move would resolve regions through the API). */
class WorldGuardRegionListenerNpcTest {

    private final World world = mock(World.class);

    private PlayerMoveEvent move(Player player) {
        return new PlayerMoveEvent(player, new Location(world, 0, 64, 0), new Location(world, 1, 64, 0));
    }

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    @Test
    void anNpcMoveDoesNotReachTheTracker() {
        WorldGuardRegionTracker tracker = mock(WorldGuardRegionTracker.class);
        new WorldGuardRegionListener(tracker).onPlayerMove(move(player(true)));
        verify(tracker, never()).handleMove(any(), any(), any());
    }

    @Test
    void aPlayerMoveStillDoes() {
        WorldGuardRegionTracker tracker = mock(WorldGuardRegionTracker.class);
        Player player = player(false);
        new WorldGuardRegionListener(tracker).onPlayerMove(move(player));
        verify(tracker).handleMove(any(), any(), any());
    }
}
