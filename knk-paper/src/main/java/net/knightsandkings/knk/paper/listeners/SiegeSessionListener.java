package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.siege.SiegeService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Siege session handling (DESIGN §6.8, §9.2):
 * <ul>
 *   <li><b>Quit</b> while a member: leave the lobby. During HUB/IN_PROGRESS the inventory and stats
 *       are restored right here (the player is still valid inside {@code PlayerQuitEvent}); the
 *       return location waits for the next join. No mid-match rejoin (DESIGN §13 Q5).</li>
 *   <li><b>Join</b> with a leftover vault file and no membership: finish the quit restore, or after a
 *       crash restore everything. Runs two ticks later so it lands after {@code PlayerListener.onJoin}'s
 *       spawn teleport and the other join handlers.</li>
 * </ul>
 * Phase 7 adds the scenario-area lockdown (region entry, teleports) here.
 */
public class SiegeSessionListener implements Listener {

    private final SiegeService service;

    public SiegeSessionListener(SiegeService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.later(2L, () -> {
            if (player.isOnline()) service.handleJoin(player);
        });
    }
}
