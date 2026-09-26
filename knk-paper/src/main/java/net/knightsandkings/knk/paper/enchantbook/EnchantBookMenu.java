package net.knightsandkings.knk.paper.enchantbook;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
 * The permanent-book item chooser (KNG-5; ported from {@code SiegeEnchantMenu} on
 * {@code claude/siege-minigame}, itself v2's {@code PlayerEnchantMenu}): right-click a book in hand to see
 * every item in your inventory it can go on, click one to apply it. A plain Bukkit inventory with its own
 * holder. Clicking the book on the cursor onto an item keeps working alongside it.
 */
public final class EnchantBookMenu {

    /** Player inventory index of the off-hand slot. */
    private static final int OFF_HAND_INDEX = 40;
    private static final int MAX_ITEMS = 54;

    private final EnchantBooks books;

    public EnchantBookMenu(EnchantBooks books) {
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
     * Opens the chooser for the book in {@code hand}.
     *
     * @return why it didn't open (for the action bar), or null when it opened or the item isn't a book
     */
    public Component open(Player player, EquipmentSlot hand) {
        PlayerInventory inv = player.getInventory();
        int bookIndex = hand == EquipmentSlot.OFF_HAND ? OFF_HAND_INDEX : inv.getHeldItemSlot();
        ItemStack book = inv.getItem(bookIndex);
        if (!EnchantBookItems.isBook(book)) return null;
        if (books.book(book) == null) {
            return Component.text(EnchantBooks.describe(ApplyResult.INVALID_BOOK), NamedTextColor.RED);
        }

        List<Integer> eligible = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && eligible.size() < MAX_ITEMS; i++) {
            if (i == bookIndex || contents[i] == null || contents[i].getType().isAir()) continue;
            if (books.evaluate(book, contents[i]) == ApplyResult.APPLIED) eligible.add(i);
        }
        String enchantName = books.describeBook(book);
        if (eligible.isEmpty()) {
            return Component.text("Nothing in your inventory can take " + enchantName + ".", NamedTextColor.RED);
        }

        Holder holder = new Holder(bookIndex);
        int size = Math.min(MAX_ITEMS, ((eligible.size() + 8) / 9) * 9);
        Inventory gui = Bukkit.createInventory(holder, size, Component.text("Apply " + enchantName, NamedTextColor.DARK_PURPLE));
        holder.inventory = gui;
        for (int slot = 0; slot < eligible.size(); slot++) {
            int index = eligible.get(slot);
            ItemStack shown = contents[index].clone();
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("Click to apply " + enchantName, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
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
        EnchantBooks.Outcome outcome = books.apply(book, target);
        if (outcome.result() == ApplyResult.APPLIED) {
            inv.setItem(index, outcome.updated());
            if (book.getAmount() > 1) {
                ItemStack rest = book.clone();
                rest.setAmount(book.getAmount() - 1);
                inv.setItem(holder.bookIndex, rest);
            } else {
                inv.setItem(holder.bookIndex, null);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        }
        player.closeInventory();
        player.sendActionBar(message(outcome.result()));
    }

    public static Component message(ApplyResult result) {
        return Component.text(EnchantBooks.describe(result), result == ApplyResult.APPLIED ? NamedTextColor.GREEN : NamedTextColor.RED);
    }
}
