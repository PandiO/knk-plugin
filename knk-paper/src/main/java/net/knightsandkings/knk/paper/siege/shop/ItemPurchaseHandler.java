package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseHandler;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseTicket;
import net.knightsandkings.knk.core.siege.shop.ShopRefusal;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sells {@link ShopEntryKind#ITEM} entries (siege shop DESIGN § 7.1.4, § 8, P22, P23; PandiO/knk-workspace#868): the
 * items are the match's blueprint items, built once when the shop opened (frozen like the catalogue). Room for the
 * whole quantity is checked before anything is charged, because Siege forbids item pickup for members and dropped items
 * would be lost (P22); then the charge, then the items. A give that still falls short is undone and the charge
 * reversed (§ 5.5.2). Bought items are ordinary items for the match: the PvP gear restore clears them (P23). Main thread.
 */
public final class ItemPurchaseHandler implements ShopPurchaseHandler {

    private static final Logger LOGGER = Logger.getLogger(ItemPurchaseHandler.class.getName());

    private final Function<UUID, Player> players;
    private final Map<Integer, ItemStack> items;

    /**
     * @param players online player lookup ({@code Bukkit::getPlayer})
     * @param items   one item per entry id (any stack size; the entry's quantity is what is given)
     */
    public ItemPurchaseHandler(Function<UUID, Player> players, Map<Integer, ItemStack> items) {
        this.players = Objects.requireNonNull(players, "players");
        this.items = Map.copyOf(items);
    }

    @Override
    public ShopEntryKind kind() {
        return ShopEntryKind.ITEM;
    }

    /** The built item of an entry (a copy), for its icon. */
    public Optional<ItemStack> item(int entryId) {
        ItemStack item = items.get(entryId);
        return item == null ? Optional.empty() : Optional.of(item.clone());
    }

    @Override
    public Optional<ShopRefusal> checkDelivery(UUID buyer, ShopEntry entry) {
        ItemStack template = items.get(entry.id());
        if (template == null) return Optional.of(ShopRefusal.notAvailable(entry.name()));
        Player player = players.apply(buyer);
        if (player == null) return Optional.of(ShopRefusal.notInMatch());
        return fits(player.getInventory(), template, entry.quantity()) ? Optional.empty() : Optional.of(ShopRefusal.inventoryFull());
    }

    @Override
    public ShopPurchaseResult fulfil(ShopPurchaseTicket ticket) {
        ShopPurchaseResult charged = ticket.charge();
        if (!(charged instanceof ShopPurchaseResult.Bought)) return charged;
        Player player = players.apply(ticket.buyer());
        ItemStack template = items.get(ticket.entry().id());
        List<ItemStack> stacks = template == null ? List.of() : stacks(template, ticket.entry().quantity());
        try {
            if (player == null || stacks.isEmpty()) throw new IllegalStateException("no player or no item");
            Map<Integer, ItemStack> left = player.getInventory().addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
            if (!left.isEmpty()) {
                // Should not happen after checkDelivery: take back what went in, so nothing is half delivered.
                List<ItemStack> added = new ArrayList<>();
                int missing = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                int given = ticket.entry().quantity() - missing;
                if (given > 0) added.addAll(stacks(template, given));
                if (!added.isEmpty()) player.getInventory().removeItem(added.toArray(ItemStack[]::new));
                throw new IllegalStateException(missing + " of " + ticket.entry().quantity() + " didn't fit");
            }
            return charged;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Siege shop] Delivering entry " + ticket.entry().id() + " to " + ticket.buyer()
                    + " failed after the charge; reversing it", e);
            ticket.reverse();
            return new ShopPurchaseResult.Refused(ShopRefusal.deliveryFailed());
        }
    }

    /** {@code quantity} of {@code template} as stacks of at most its maximum stack size. */
    static List<ItemStack> stacks(ItemStack template, int quantity) {
        int max = Math.max(1, template.getMaxStackSize());
        List<ItemStack> out = new ArrayList<>();
        for (int left = Math.max(0, quantity); left > 0; left -= max) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(max, left));
            out.add(stack);
        }
        return out;
    }

    /** Whether the storage slots (not armour or off hand) can take {@code quantity} of {@code template}. */
    static boolean fits(Inventory inventory, ItemStack template, int quantity) {
        int max = Math.max(1, template.getMaxStackSize());
        ItemStack[] slots = inventory instanceof org.bukkit.inventory.PlayerInventory p ? p.getStorageContents() : inventory.getContents();
        long room = 0;
        for (ItemStack slot : slots) {
            if (slot == null || slot.getType().isAir()) room += max;
            else if (slot.isSimilar(template)) room += Math.max(0, Math.min(max, slot.getMaxStackSize()) - slot.getAmount());
            if (room >= quantity) return true;
        }
        return room >= quantity;
    }
}
