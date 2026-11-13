package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.kit.KitGrantDelivery;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Function;

/**
 * Shortly after a join, hands over the kits a staff member granted from the web app while the player was offline
 * (PandiO/knk-workspace#261). This is the path that never depends on a notification: the API's claim rows are read
 * directly. Waits for the player's account to be cached (the delivery needs their user id), a few tries at most.
 */
public final class KitDeliveryJoinListener implements Listener {

    static final long JOIN_DELAY_TICKS = 100L; // after the account load and the lootbox join hand-overs
    static final int ATTEMPTS = 4;

    private final Plugin plugin;
    private final KitGrantDelivery delivery;
    private final Function<Player, Integer> userIdOf;

    public KitDeliveryJoinListener(Plugin plugin, KitGrantDelivery delivery, Function<Player, Integer> userIdOf) {
        this.plugin = plugin;
        this.delivery = delivery;
        this.userIdOf = userIdOf;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        schedule(event.getPlayer().getUniqueId(), 1);
    }

    private void schedule(UUID uuid, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (userIdOf.apply(player) == null) {
                if (attempt < ATTEMPTS) {
                    schedule(uuid, attempt + 1);
                }
                return;
            }
            delivery.deliverPending(player);
        }, JOIN_DELAY_TICKS);
    }
}
