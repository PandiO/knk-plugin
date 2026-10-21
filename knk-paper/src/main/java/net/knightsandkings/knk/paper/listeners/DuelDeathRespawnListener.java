package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.duel.DuelService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Duel death and respawn (arena duels DESIGN §8.4–8.6), at {@code HIGHEST}, for duelists only:
 * <ul>
 *   <li>death: keepInventory and keepLevel, no drops, no vanilla death message (the duelists are told). In drop mode
 *       the loser's drops are written to the escrow as spoils first and then cleared (the inventory goes); the
 *       runtime records the loss and decides it on the next tick;</li>
 *   <li>respawn: at the arena exit; a loser of a finished match gets their gear back right after.</li>
 * </ul>
 */
public class DuelDeathRespawnListener implements Listener {

    private final DuelService service;
    private final Consumer<Runnable> nextTick;

    public DuelDeathRespawnListener(DuelService service, Consumer<Runnable> nextTick) {
        this.service = service;
        this.nextTick = nextTick;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        DuelService.DeathHandling handling = service.onDeath(victim, List.copyOf(event.getDrops()));
        if (!handling.handled()) return;
        event.setKeepInventory(handling.keepInventory());
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        if (handling.clearDrops()) event.getDrops().clear();
        event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        service.respawnLocation(player).ifPresent(event::setRespawnLocation);
        nextTick.accept(() -> {
            if (player.isOnline()) service.afterRespawn(player);
        });
    }
}
