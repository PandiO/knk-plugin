package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.siege.SiegeService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Siege death and respawn (DESIGN §6.6). Runs at {@code HIGHEST}, after {@code PlayerListener}'s
 * stub handlers, and only for siege members - everyone else is left to {@code PlayerListener}.
 * <ul>
 *   <li>Death of a member away in a match (HUB or IN_PROGRESS): keepInventory + keepLevel, no item or
 *       XP drops (they would duplicate on restore, D2), no vanilla death message. In a running match
 *       the kill, death and streak are credited and announced (null-safe for non-PvP deaths).</li>
 *   <li>Respawn: at the member's current spawn choice (fallback: the team's default spawnpoint), at the
 *       hub during HUB, or - for a member whose match ended while they were dead - at their pre-siege
 *       location. The spawn picker opens after {@code SpawnPickerDelayTicks} when the team has 2+
 *       options.</li>
 * </ul>
 */
public class SiegeDeathRespawnListener implements Listener {

    private final SiegeService service;

    public SiegeDeathRespawnListener(SiegeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (service.activeLobbyOf(victim.getUniqueId()).isEmpty()) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        service.handleMemberDeath(victim, victim.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        service.respawnLocation(player).ifPresent(event::setRespawnLocation);
        service.afterRespawn(player);
    }
}
