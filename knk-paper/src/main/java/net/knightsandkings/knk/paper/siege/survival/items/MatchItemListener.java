package net.knightsandkings.knk.paper.siege.survival.items;

import net.knightsandkings.knk.core.survival.items.MatchItemRules;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.UUID;
import java.util.function.Function;

/**
 * The drop guards of DESIGN §8b for match items: only members of the item's match pick it up (the Siege guard cancels every
 * pickup for members at {@code HIGH}; like the enchant-book listener this one decides at {@code HIGHEST} for match items),
 * mobs, hoppers and other inventories never take one, and leftovers from an earlier run are removed when their chunk
 * loads. Containers, item frames, drops and death drops are already blocked by the Siege guards (S40).
 */
public final class MatchItemListener implements Listener {

    private final MatchItems items;
    /** The running survival match token of a player, or null. */
    private final Function<UUID, String> matchOfPlayer;

    public MatchItemListener(MatchItems items, Function<UUID, String> matchOfPlayer) {
        this.items = items;
        this.matchOfPlayer = matchOfPlayer;
    }

    @SuppressWarnings("deprecation") // the same event the Siege inventory guard and the book listener use
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickup(PlayerPickupItemEvent event) {
        String tag = items.matchIdOf(event.getItem().getItemStack());
        MatchItemRules.Decision decision = MatchItemRules.pickup(tag, matchOfPlayer.apply(event.getPlayer().getUniqueId()));
        switch (decision) {
            case ALLOW -> event.setCancelled(false);
            case DENY -> {
                event.setCancelled(true);
                tell(event.getPlayer());
            }
            case NOT_A_MATCH_ITEM -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (MatchItemRules.deniedToNonPlayers(items.matchIdOf(event.getItem().getItemStack()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (MatchItemRules.deniedToNonPlayers(items.matchIdOf(event.getItem().getItemStack()))) event.setCancelled(true);
    }

    /** A tracked ground item despawning on its own is fine; nothing to keep. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(ItemDespawnEvent event) {
        // no-op: documented for completeness (the sweep tolerates missing entities)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        items.sweep(event.getEntities());
    }

    private static void tell(Player player) {
        player.sendActionBar(Component.text("That item belongs to another match.", SiegeMessages.BAD));
    }
}
