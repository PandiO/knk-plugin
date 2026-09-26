package net.knightsandkings.knk.paper.enchantbook;

import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.core.enchantbook.EnchantBookText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The permanent-book item chooser (KNG-5; ported from {@code SiegeEnchantMenu} on
 * {@code claude/siege-minigame}, itself v2's {@code PlayerEnchantMenu}): right-click a book in hand to see
 * every item in your inventory the enchantment could go on by type. Items it can go on now say "Click to apply";
 * the others stay listed with the reason (grade cap, conflict, already as high), so nothing silently goes
 * missing. This is the only way to apply a book:
 * clicking a book from the cursor onto an item was removed after the 2026-09-26 manual test (too easy to do by
 * accident). A plain Bukkit inventory with its own holder.
 * <p>
 * When the item's grade cap (KNG-6) would give less than the book teaches, the click opens a confirmation
 * screen ({@link ConfirmHolder}) that explains the cap first, so nobody spends a Sharpness III book on a
 * Common sword expecting Sharpness III.
 */
public final class EnchantBookMenu {

    /** Player inventory index of the off-hand slot. */
    private static final int OFF_HAND_INDEX = 40;
    private static final int MAX_ITEMS = 54;

    private static final int CONFIRM_SIZE = 27;
    private static final int CONFIRM_APPLY_SLOT = 11;
    private static final int CONFIRM_ITEM_SLOT = 13;
    private static final int CONFIRM_CANCEL_SLOT = 15;

    private final EnchantBooks books;
    private final Plugin plugin;

    public EnchantBookMenu(EnchantBooks books, Plugin plugin) {
        this.books = books;
        this.plugin = plugin;
    }

    /** Any inventory this menu opened (chooser or confirmation); every click in it is cancelled. */
    public static boolean isMenu(InventoryHolder holder) {
        return holder instanceof Holder || holder instanceof ConfirmHolder;
    }

    /** The open chooser: which player-inventory index holds the book, and which each shown slot stands for. */
    public static final class Holder implements InventoryHolder {
        private final int bookIndex;
        private final EquipmentSlot hand;
        private final Map<Integer, Integer> inventoryIndexBySlot = new HashMap<>();
        private Inventory inventory;

        Holder(int bookIndex, EquipmentSlot hand) {
            this.bookIndex = bookIndex;
            this.hand = hand;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * The open "grade limit" confirmation: the book and target as they were when it opened, and the level the
     * player is agreeing to. Confirming re-checks all three, so nothing that changed meanwhile gets applied.
     */
    public static final class ConfirmHolder implements InventoryHolder {
        private final int bookIndex;
        private final EquipmentSlot hand;
        private final int targetIndex;
        private final ItemStack bookSnapshot;
        private final ItemStack targetSnapshot;
        private final int level;
        private Inventory inventory;

        ConfirmHolder(int bookIndex, EquipmentSlot hand, int targetIndex, ItemStack bookSnapshot, ItemStack targetSnapshot, int level) {
            this.bookIndex = bookIndex;
            this.hand = hand;
            this.targetIndex = targetIndex;
            this.bookSnapshot = bookSnapshot;
            this.targetSnapshot = targetSnapshot;
            this.level = level;
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

        // Every item the enchantment could go on by type (the developer's request after the 2026-09-26 manual
        // test), not only those it can go on right now: each says whether it can, and if not, why - so an item
        // that is missing (e.g. held back by the grade cap) no longer looks like a bug. Usable items first.
        List<Integer> usable = new ArrayList<>();
        List<Integer> blocked = new ArrayList<>();
        Map<Integer, EnchantBooks.Decision> decisions = new HashMap<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (i == bookIndex || contents[i] == null || contents[i].getType().isAir()) continue;
            EnchantBooks.Decision decision = books.decide(book, contents[i]);
            if (decision.facts() == null || !decision.facts().usable() || !decision.facts().compatible()) continue;
            decisions.put(i, decision);
            (decision.result() == ApplyResult.APPLIED ? usable : blocked).add(i);
        }
        String enchantName = books.describeBook(book);
        if (decisions.isEmpty()) {
            return Component.text("Nothing in your inventory can hold " + books.describeEnchantment(book) + ".", NamedTextColor.RED);
        }
        List<Integer> shownIndexes = new ArrayList<>(usable);
        shownIndexes.addAll(blocked);
        if (shownIndexes.size() > MAX_ITEMS) shownIndexes = shownIndexes.subList(0, MAX_ITEMS);

        Holder holder = new Holder(bookIndex, hand);
        int size = Math.min(MAX_ITEMS, ((shownIndexes.size() + 8) / 9) * 9);
        Inventory gui = Bukkit.createInventory(holder, size, Component.text("Apply " + enchantName, NamedTextColor.DARK_PURPLE));
        holder.inventory = gui;
        for (int slot = 0; slot < shownIndexes.size(); slot++) {
            int index = shownIndexes.get(slot);
            EnchantBooks.Decision decision = decisions.get(index);
            ItemStack shown = contents[index].clone();
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.addAll(status(book, contents[index], decision, enchantName));
            meta.lore(lore);
            shown.setItemMeta(meta);
            gui.setItem(slot, shown);
            holder.inventoryIndexBySlot.put(slot, index);
        }
        player.openInventory(gui);
        return null;
    }

    /** The chooser lines under an item: "Click to apply", "Grade limit ... / Click to review", or why not. */
    private List<Component> status(ItemStack book, ItemStack target, EnchantBooks.Decision decision, String enchantName) {
        List<Component> lines = new ArrayList<>();
        if (decision.result() == ApplyResult.APPLIED && decision.capped()) {
            // KNG-6: the grade cap lowers what this item gets; the click asks first.
            lines.add(line("Grade limit: only up to " + EnchantBookText.roman(decision.level()), NamedTextColor.GOLD));
            lines.add(line("Click to review", NamedTextColor.YELLOW));
        } else if (decision.result() == ApplyResult.APPLIED) {
            lines.add(line("Click to apply " + enchantName, NamedTextColor.GREEN));
        } else {
            lines.add(line("✘ Can't apply " + enchantName, NamedTextColor.RED));
            for (String reason : reasons(book, target, decision)) {
                lines.add(line(reason, NamedTextColor.GRAY));
            }
        }
        return lines;
    }

    private List<String> reasons(ItemStack book, ItemStack target, EnchantBooks.Decision decision) {
        Optional<KnkGrade> grade = books.capGrade(target);
        String gradeLabel = grade.map(g -> EnchantBookText.gradeLabel(g.name(), g.stars())).orElse("?");
        return EnchantBookText.cannotApply(
                decision.result(),
                books.describeEnchantment(book),
                decision.payload() != null ? decision.payload().level() : 0,
                decision.facts() != null ? decision.facts().existingLevel() : 0,
                decision.facts() != null ? decision.facts().levelCap() : null,
                gradeLabel,
                EnchantBooks.ungraded(target),
                books.conflictsWith(book, target).orElse(null));
    }

    /** A click in one of this menu's own slots. The caller has cancelled the click. */
    public void click(Player player, InventoryHolder holder, int slot) {
        if (holder instanceof Holder chooser) {
            clickChooser(player, chooser, slot);
        } else if (holder instanceof ConfirmHolder confirm) {
            clickConfirm(player, confirm, slot);
        }
    }

    /** Re-checks the clicked item; applies it straight away, or asks first when the grade cap lowers the level. */
    private void clickChooser(Player player, Holder holder, int slot) {
        Integer index = holder.inventoryIndexBySlot.get(slot);
        if (index == null) return;
        PlayerInventory inv = player.getInventory();
        ItemStack book = inv.getItem(holder.bookIndex);
        ItemStack target = inv.getItem(index);
        EnchantBooks.Decision decision = books.decide(book, target);
        if (decision.result() != ApplyResult.APPLIED) {
            // A listed item that can't take the book: say why (it's also in its lore) and keep the chooser open.
            List<String> why = reasons(book, target, decision);
            player.sendActionBar(Component.text(why.isEmpty() ? EnchantBooks.describe(decision.result()) : String.join(" ", why),
                    NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (decision.capped()) {
            // Opening another inventory from inside a click event is only safe on the next tick.
            ItemStack bookSnapshot = book.clone();
            ItemStack targetSnapshot = target.clone();
            nextTick(player, () -> openConfirm(player, holder.bookIndex, holder.hand, index, bookSnapshot, targetSnapshot, decision));
            return;
        }
        applyAndClose(player, holder.bookIndex, index);
    }

    private void openConfirm(Player player, int bookIndex, EquipmentSlot hand, int targetIndex,
                             ItemStack book, ItemStack target, EnchantBooks.Decision decision) {
        ConfirmHolder holder = new ConfirmHolder(bookIndex, hand, targetIndex, book, target, decision.level());
        Inventory gui = Bukkit.createInventory(holder, CONFIRM_SIZE, Component.text("Grade limit - apply anyway?", NamedTextColor.DARK_RED));
        holder.inventory = gui;

        String bookName = books.describeBook(book);                    // "Sharpness III"
        String enchantment = books.describeEnchantment(book);          // "Sharpness"
        String result = enchantment + " " + EnchantBookText.roman(decision.level()); // "Sharpness I"
        List<Component> explanation = explanation(target, bookName, enchantment, result, decision);

        ItemStack shown = target.clone();
        ItemMeta shownMeta = shown.getItemMeta();
        List<Component> shownLore = shownMeta.hasLore() && shownMeta.lore() != null ? new ArrayList<>(shownMeta.lore()) : new ArrayList<>();
        shownLore.add(Component.empty());
        shownLore.addAll(explanation);
        shownMeta.lore(shownLore);
        shown.setItemMeta(shownMeta);
        gui.setItem(CONFIRM_ITEM_SLOT, shown);

        List<Component> applyLore = new ArrayList<>(explanation);
        applyLore.add(Component.empty());
        applyLore.add(line("The book is used up.", NamedTextColor.GRAY));
        gui.setItem(CONFIRM_APPLY_SLOT, button(Material.LIME_CONCRETE, "Apply " + result, NamedTextColor.GREEN, applyLore));
        gui.setItem(CONFIRM_CANCEL_SLOT, button(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED,
                List.of(line("Keep the book and go back.", NamedTextColor.GRAY))));

        player.openInventory(gui);
    }

    /** "This item's grade (Common, 1★) allows Sharpness up to I. Your Sharpness III book will give Sharpness I." */
    private List<Component> explanation(ItemStack target, String bookName, String enchantment, String result,
                                        EnchantBooks.Decision decision) {
        Optional<KnkGrade> grade = books.capGrade(target);
        String gradeLabel = grade.map(g -> EnchantBookText.gradeLabel(g.name(), g.stars())).orElse("?");
        String whose = EnchantBooks.ungraded(target)
                ? "This item has no grade, so it counts as " + gradeLabel + "."
                : "This item's grade is " + gradeLabel + ".";
        List<Component> lines = new ArrayList<>();
        lines.add(line(whose, NamedTextColor.GOLD));
        lines.add(line("It allows " + enchantment + " up to " + EnchantBookText.roman(decision.level()) + ".", NamedTextColor.GOLD));
        lines.add(line("Your " + bookName + " book will only give " + result + ".", NamedTextColor.YELLOW));
        return lines;
    }

    /** Confirm: re-check that nothing changed, then apply. Cancel: back to the chooser. */
    private void clickConfirm(Player player, ConfirmHolder holder, int slot) {
        if (slot == CONFIRM_CANCEL_SLOT) {
            nextTick(player, () -> {
                Component problem = open(player, holder.hand);
                if (problem != null) {
                    player.closeInventory();
                    player.sendActionBar(problem);
                }
            });
            return;
        }
        if (slot != CONFIRM_APPLY_SLOT) return;

        PlayerInventory inv = player.getInventory();
        ItemStack book = inv.getItem(holder.bookIndex);
        ItemStack target = inv.getItem(holder.targetIndex);
        EnchantBooks.Decision decision = books.decide(book, target);
        boolean unchanged = book != null && holder.bookSnapshot.isSimilar(book)
                && holder.targetSnapshot.equals(target)
                && decision.result() == ApplyResult.APPLIED && decision.level() == holder.level;
        if (!unchanged) {
            player.closeInventory();
            player.sendActionBar(Component.text("Something changed - nothing was applied. Try again.", NamedTextColor.RED));
            return;
        }
        applyAndClose(player, holder.bookIndex, holder.targetIndex);
    }

    private void applyAndClose(Player player, int bookIndex, int targetIndex) {
        PlayerInventory inv = player.getInventory();
        ItemStack book = inv.getItem(bookIndex);
        ItemStack target = inv.getItem(targetIndex);
        EnchantBooks.Outcome outcome = books.apply(book, target);
        if (outcome.result() == ApplyResult.APPLIED) {
            inv.setItem(targetIndex, outcome.updated());
            if (book.getAmount() > 1) {
                ItemStack rest = book.clone();
                rest.setAmount(book.getAmount() - 1);
                inv.setItem(bookIndex, rest);
            } else {
                inv.setItem(bookIndex, null);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        }
        player.closeInventory();
        player.sendActionBar(message(outcome));
    }

    private void nextTick(Player player, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) task.run();
        });
    }

    private static ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component message(ApplyResult result) {
        return Component.text(EnchantBooks.describe(result), result == ApplyResult.APPLIED ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    public static Component message(EnchantBooks.Outcome outcome) {
        return Component.text(EnchantBooks.describe(outcome), outcome.result() == ApplyResult.APPLIED ? NamedTextColor.GREEN : NamedTextColor.RED);
    }
}
