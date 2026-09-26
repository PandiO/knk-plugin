package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Crash-safe delivery (docs/specs/lootboxes/DESIGN.md §3.4, §3.7): shortly after a join, the player's unconfirmed
 * claims ({@code GET pending}: undelivered and older than 30 s) are delivered again. An instanced item already in the
 * inventory or ender chest is only confirmed, never given twice; a stackable item can't be told apart and is given
 * again (at worst one low-value stack, logged {@code Redelivered}).
 */
public final class LootboxJoinListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LootboxJoinListener.class.getName());
    private static final long DELAY_TICKS = 60L; // the user cache fills at pre-login; give the join a moment

    private final Plugin plugin;
    private final LootboxesQueryApi queryApi;
    private final LootboxDelivery delivery;
    private final Function<Player, Integer> userIdOf;
    private final Executor mainThread;

    public LootboxJoinListener(Plugin plugin, LootboxesQueryApi queryApi, LootboxDelivery delivery,
                               Function<Player, Integer> userIdOf, Executor mainThread) {
        this.plugin = plugin;
        this.queryApi = queryApi;
        this.delivery = delivery;
        this.userIdOf = userIdOf;
        this.mainThread = mainThread;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                deliverPending(player);
            }
        }, DELAY_TICKS);
    }

    /** Main thread. */
    public void deliverPending(Player player) {
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            return;
        }
        queryApi.getPending(userId).whenComplete((claims, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                LOGGER.warning("Could not read pending lootbox claims for user " + userId + ": "
                        + LootboxRejectedException.unwrap(ex).getMessage());
                return;
            }
            deliverInOrder(player, claims, 0);
        }));
    }

    private void deliverInOrder(Player player, List<KnkLootboxClaimResult> claims, int index) {
        if (claims == null || index >= claims.size() || !player.isOnline()) {
            return;
        }
        KnkLootboxClaimResult claim = claims.get(index);
        CompletableFuture<LootboxDelivery.Outcome> next = delivery.deliver(player, claim, true);
        next.thenAccept(outcome -> {
            if (outcome.given() && player.isOnline()) {
                player.sendMessage(Component.text("A lootbox item you hadn't received yet: ", NamedTextColor.GREEN)
                        .append(LootboxAnnouncer.itemName(outcome.item(), claim)));
            }
            deliverInOrder(player, claims, index + 1);
        });
    }
}
