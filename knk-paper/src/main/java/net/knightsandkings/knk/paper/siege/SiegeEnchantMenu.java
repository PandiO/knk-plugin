package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.paper.siege.SiegeEnchantBooks.ApplyResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The siege-book item chooser (v2 {@code PlayerEnchantMenu}; developer request 2026-09-26): right-click a
 * siege book in hand to see every item in your inventory it can go on, click one to apply it. A plain
 * Bukkit inventory with its own holder - the InventoryMenu versions of the siege menus are Phase 8b. The
 * cursor-onto-item application keeps working alongside it.
 */
public final class SiegeEnchantMenu {

    /** Player inventory index of the off-hand slot. */
    private static final int OFF_HAND_INDEX = 40;
    private static final int MAX_ITEMS = 54;

    private final SiegeEnchantBooks books;

    public SiegeEnchantMenu(SiegeEnchantBooks books) {
        this.books = books;
    }

    /** The open chooser: which player-inventory index holds the book, and which each shown slot stands for. */
    public static final class Holder implements InventoryHolder {
        private final int bookIndex;
        private final Map<Integer, Integer> inventoryIndexBySlot = new HashMap<>();
        private Inventory inventory;

        Holder(int bookIndex) {
            this.bookIndex = bookIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Opens the chooser for the siege book in {@code hand}.
     *
     * @return why it didn't open (for the action bar), or null when it opened or the item isn't a siege book
     */
    public Component open(Player player, EquipmentSlot hand) {
        PlayerInventory inv = player.getInventory();
        int bookIndex = hand == EquipmentSlot.OFF_HAND ? OFF_HAND_INDEX : inv.getHeldItemSlot();
        ItemStack book = inv.getItem(bookIndex);
        Map.Entry<Enchantment, Integer> stored = books.storedEnchant(book);
        if (stored == null) return null;

        ApplyResult matchCheck = books.evaluate(player, book, null);
        if (matchCheck == ApplyResult.NOT_IN_MATCH || matchCheck == ApplyResult.WRONG_MATCH) {
            inv.setItem(bookIndex, null);
            return Component.text(SiegeEnchantBooks.describe(matchCheck) + " The book crumbles.", SiegeMessages.BAD);
        }

        List<Integer> eligible = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && eligible.size() < MAX_ITEMS; i++) {
            if (i == bookIndex || contents[i] == null || contents[i].getType().isAir()) continue;
            if (books.evaluate(player, book, contents[i]) == ApplyResult.APPLIED) eligible.add(i);
        }
        Component enchantName = stored.getKey().displayName(stored.getValue());
        if (eligible.isEmpty()) {
            return Component.text("Nothing in your inventory can take ", SiegeMessages.BAD)
                    .append(enchantName.colorIfAbsent(SiegeMessages.BAD)).append(Component.text(".", SiegeMessages.BAD));
        }

        Holder holder = new Holder(bookIndex);
        int size = Math.min(MAX_ITEMS, ((eligible.size() + 8) / 9) * 9);
        Inventory gui = Bukkit.createInventory(holder, size,
                Component.text("Apply ", NamedTextColor.DARK_PURPLE).append(enchantName.colorIfAbsent(NamedTextColor.DARK_PURPLE)));
        holder.inventory = gui;
        for (int slot = 0; slot < eligible.size(); slot++) {
            int index = eligible.get(slot);
            ItemStack shown = contents[index].clone();
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("Click to apply ", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                    .append(enchantName.colorIfAbsent(NamedTextColor.GREEN)));
            meta.lore(lore);
            shown.setItemMeta(meta);
            gui.setItem(slot, shown);
            holder.inventoryIndexBySlot.put(slot, index);
        }
        player.openInventory(gui);
        return null;
    }

    /** A click in the chooser's own slots: re-checks and applies. The caller has cancelled the click. */
    public void click(Player player, Holder holder, int slot) {
        Integer index = holder.inventoryIndexBySlot.get(slot);
        if (index == null) return;
        PlayerInventory inv = player.getInventory();
        ItemStack book = inv.getItem(holder.bookIndex);
        ItemStack target = inv.getItem(index);
        ItemStack[] updated = new ItemStack[1];
        ApplyResult result = books.apply(player, book, target, updated);
        if (result == ApplyResult.APPLIED) {
            inv.setItem(index, updated[0]);
            if (book.getAmount() > 1) {
                ItemStack rest = book.clone();
                rest.setAmount(book.getAmount() - 1);
                inv.setItem(holder.bookIndex, rest);
            } else {
                inv.setItem(holder.bookIndex, null);
            }
        }
        player.closeInventory();
        player.sendActionBar(Component.text(SiegeEnchantBooks.describe(result),
                result == ApplyResult.APPLIED ? SiegeMessages.GOOD : SiegeMessages.BAD));
    }
}
