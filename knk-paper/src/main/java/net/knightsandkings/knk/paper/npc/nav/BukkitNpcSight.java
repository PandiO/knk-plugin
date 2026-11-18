package net.knightsandkings.knk.paper.npc.nav;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcSight;

/**
 * Whether a player sees a place (implementation plan 4c.4 item 7, link M7: a pushed NPC teleports home only out of
 * every player's sight): a player in the same world within {@link #RANGE} blocks with a line of sight to the body's
 * head or feet there. Spectators count. Main thread; never loads a chunk (a place in an unloaded chunk has no player
 * near it).
 */
public final class BukkitNpcSight implements NpcSight {

    /** Further than this no player is taken to see a body (a generous render distance for an NPC). */
    static final double RANGE = 64;

    @Override
    public boolean seen(NavPoint feet) {
        World world = Bukkit.getWorld(feet.world());
        if (world == null) {
            return false;
        }
        Location head = new Location(world, feet.x(), feet.y() + 1.6, feet.z());
        Location low = new Location(world, feet.x(), feet.y() + 0.2, feet.z());
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && p.isDead()) {
                continue;
            }
            if (p.getLocation().distanceSquared(head) > RANGE * RANGE) {
                continue;
            }
            if (p.hasLineOfSight(head) || p.hasLineOfSight(low)) {
                return true;
            }
        }
        return false;
    }
}
