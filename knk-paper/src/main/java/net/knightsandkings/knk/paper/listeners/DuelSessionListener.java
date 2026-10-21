package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.duel.DuelService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.function.BiConsumer;

/**
 * Duel sessions (arena duels DESIGN D10, §7.3, §8.4):
 * <ul>
 *   <li><b>quit</b>: negotiations close; in a match the gear comes back inside the quit event, and the match is
 *       cancelled (before the countdown) or forfeited (from the countdown on, drop mode: the inventory is taken as
 *       spoils);</li>
 *   <li><b>join</b> (two ticks later, after the spawn teleport and the other join handlers): stray loaned kit items
 *       are swept, a vault left by a crash or a quit is restored, and the escrow delivers what is owed.</li>
 * </ul>
 */
public class DuelSessionListener implements Listener {

    private final DuelService service;
    private final BiConsumer<Long, Runnable> later;

    public DuelSessionListener(DuelService service, BiConsumer<Long, Runnable> later) {
        this.service = service;
        this.later = later;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        later.accept(2L, () -> {
            if (player.isOnline()) service.onJoin(player);
        });
    }
}
