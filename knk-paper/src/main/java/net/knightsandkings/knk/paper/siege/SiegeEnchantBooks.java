package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.siege.EnchantDropPlanner;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeEnchantMarkers;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Siege enchantment books (DESIGN §9.4, D7; siege IMPLEMENTATION_PLAN Phase 5c).
 * <ul>
 *   <li><b>Drops:</b> each second of a running match, {@link EnchantDropPlanner} may drop one book inside
 *       a random objective's capture radius (chance, cap, keys and levels from {@code SiegeConfiguration};
 *       only when the scenario has {@code EnchantDropsEnabled}). Books are tagged
 *       {@code siege_book = <matchToken>} in their PDC, non-persistent, and tracked.</li>
 *   <li><b>Pickup:</b> members of that match only (the listener un-cancels above {@code PlayerListener}).</li>
 *   <li><b>Applying:</b> click the book on the cursor onto an item in your own inventory; vanilla validity
 *       ({@link Enchantment#canEnchantItem}, conflicts), level {@code max(existing, book)}; the book is
 *       consumed and the item records {@code siege_enchants} markers.</li>
 *   <li><b>Cleanup:</b> books still on the ground are removed at match end.</li>
 *   <li><b>Stripping sweep:</b> after every vault restore and on join, siege markers of matches the player
 *       isn't playing are reverted and stray books deleted (inventory, ender chest, cursor).</li>
 * </ul>
 */
public final class SiegeEnchantBooks implements SiegeMatchObserver {

    private final SiegeService service;
    private final Logger logger;
    private final RandomGenerator random;
    private final NamespacedKey bookKey;
    private final NamespacedKey markerKey;
    private final Map<String, Set<Item>> booksByMatch = new HashMap<>();
    private final Set<String> warnedKeys = new HashSet<>();

    public SiegeEnchantBooks(Plugin plugin, SiegeService service, RandomGenerator random) {
        this.service = service;
        this.logger = plugin.getLogger();
        this.random = random;
        this.bookKey = new NamespacedKey(plugin, "siege_book");
        this.markerKey = new NamespacedKey(plugin, "siege_enchants");
    }

    // ==================== Drops ====================

    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step, Map<Integer, List<Presence>> presence) {
        if (!match.scenario().enchantDropsEnabled()) return;
        KnkSiegeConfiguration config = match.configuration();
        Set<Item> alive = booksByMatch.computeIfAbsent(match.matchToken(), k -> new HashSet<>());
        alive.removeIf(item -> !item.isValid());

        List<Location> centers = new ArrayList<>();
        List<Double> radii = new ArrayList<>();
        for (KnkSiegeObjective objective : match.scenario().objectives()) {
            SiegeBukkit.toLocation(objective.captureLocation()).ifPresent(center -> {
                centers.add(center);
                radii.add(objective.captureRadius());
            });
        }
        Optional<EnchantDropPlanner.Drop> drop = EnchantDropPlanner.roll(random, config.enchantDropChancePerMille(),
                alive.size(), config.maxBooksAlive(), radii, config.allowedEnchantmentKeys(),
                config.enchantLevelMin(), config.enchantLevelMax());
        if (drop.isEmpty()) return;

        Enchantment enchantment = enchantment(drop.get().enchantmentKey());
        if (enchantment == null) return;
        int level = Math.max(1, Math.min(drop.get().level(), enchantment.getMaxLevel()));
        Location at = centers.get(drop.get().objectiveIndex()).clone().add(drop.get().offsetX(), 0.5, drop.get().offsetZ());
        Item item = at.getWorld().dropItem(at, book(enchantment, level, match.matchToken()), i -> {
            i.setPersistent(false);
            i.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        });
        alive.add(item);
    }

    private Enchantment enchantment(String key) {
        NamespacedKey namespaced = key == null ? null : NamespacedKey.fromString(key.trim().toLowerCase(java.util.Locale.ROOT));
        Enchantment enchantment = namespaced == null ? null : Registry.ENCHANTMENT.get(namespaced);
        if (enchantment == null && warnedKeys.add(String.valueOf(key))) {
            logger.warning("[Siege] Unknown enchantment key '" + key + "' in SiegeConfiguration.AllowedEnchantmentKeys; skipped");
        }
        return enchantment;
    }

    private ItemStack book(Enchantment enchantment, int level, String matchToken) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        meta.displayName(Component.text("Siege Enchantment", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Pick it up and click it onto an item", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("in your inventory. Removed after the siege.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, matchToken);
        book.setItemMeta(meta);
        return book;
    }

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Set<Item> books = booksByMatch.remove(match.matchToken());
        if (books != null) books.forEach(item -> {
            if (item.isValid()) item.remove();
        });
    }

    @Override
    public void shutdown() {
        booksByMatch.values().forEach(set -> set.forEach(item -> {
            if (item.isValid()) item.remove();
        }));
        booksByMatch.clear();
    }

    // ==================== Tags ====================

    /** The match token a siege book belongs to, or null for any other item. */
    public String bookToken(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
    }

    /** The token of the match the player is playing right now, or null. */
    public String runningTokenOf(Player player) {
        return service.runningMatchOf(player.getUniqueId()).map(SiegeMatch::matchToken).orElse(null);
    }

    // ==================== Applying ====================

    public enum ApplyResult { APPLIED, NOT_IN_MATCH, WRONG_MATCH, NOT_ENCHANTABLE, CONFLICT, NO_IMPROVEMENT, UNKNOWN }

    /**
     * Applies the siege book on the player's cursor to {@code target}; returns the updated target in
     * {@code result[0]} when applied. The caller cancels the click and writes the item back.
     */
    public ApplyResult apply(Player player, ItemStack book, ItemStack target, ItemStack[] result) {
        String token = bookToken(book);
        String running = runningTokenOf(player);
        if (running == null) return ApplyResult.NOT_IN_MATCH;
        if (!running.equals(token)) return ApplyResult.WRONG_MATCH;
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta meta) || meta.getStoredEnchants().isEmpty()) {
            return ApplyResult.UNKNOWN;
        }
        if (target == null || target.getType().isAir() || target.getType() == Material.ENCHANTED_BOOK
                || target.getType() == Material.BOOK) {
            return ApplyResult.NOT_ENCHANTABLE;
        }
        Map.Entry<Enchantment, Integer> stored = meta.getStoredEnchants().entrySet().iterator().next();
        Enchantment enchantment = stored.getKey();
        if (!enchantment.canEnchantItem(target)) return ApplyResult.NOT_ENCHANTABLE;
        for (Enchantment existing : target.getEnchantments().keySet()) {
            if (!existing.equals(enchantment) && enchantment.conflictsWith(existing)) return ApplyResult.CONFLICT;
        }
        int existingLevel = target.getEnchantmentLevel(enchantment);
        int newLevel = SiegeEnchantMarkers.appliedLevel(existingLevel, stored.getValue());
        if (newLevel <= existingLevel) return ApplyResult.NO_IMPROVEMENT;

        ItemStack updated = target.clone();
        String key = enchantment.getKey().toString();
        ItemMeta targetMeta = updated.getItemMeta();
        PersistentDataContainer pdc = targetMeta.getPersistentDataContainer();
        List<SiegeEnchantMarkers.Entry> markers = SiegeEnchantMarkers.recordApplication(
                SiegeEnchantMarkers.decode(pdc.get(markerKey, PersistentDataType.STRING)), token, key, existingLevel);
        pdc.set(markerKey, PersistentDataType.STRING, SiegeEnchantMarkers.encode(markers));
        targetMeta.addEnchant(enchantment, newLevel, true);
        updated.setItemMeta(targetMeta);
        result[0] = updated;
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        return ApplyResult.APPLIED;
    }

    public static String describe(ApplyResult result) {
        return switch (result) {
            case APPLIED -> "Enchantment applied. It is removed again after the siege.";
            case NOT_IN_MATCH -> "Siege books only work during the siege they dropped in.";
            case WRONG_MATCH -> "This siege book belongs to another match.";
            case NOT_ENCHANTABLE -> "That enchantment can't go on this item.";
            case CONFLICT -> "That enchantment conflicts with one the item already has.";
            case NO_IMPROVEMENT -> "The item already has that enchantment at this level or higher.";
            case UNKNOWN -> "This siege book is empty.";
        };
    }

    // ==================== Stripping sweep ====================

    /**
     * Reverts siege markers of every match the player isn't playing now and deletes stray siege books,
     * in the inventory, the ender chest and on the cursor (DESIGN §9.4 defence in depth).
     *
     * @return how many items were changed or removed
     */
    public int sweep(Player player) {
        String keep = runningTokenOf(player);
        int changed = 0;
        changed += sweep(player.getInventory(), keep);
        changed += sweep(player.getEnderChest(), keep);
        ItemStack cursor = player.getItemOnCursor();
        ItemStack cleaned = clean(cursor, keep);
        if (cleaned != cursor) {
            player.setItemOnCursor(cleaned);
            changed++;
        }
        if (changed > 0) {
            logger.info("[Siege] Stripped siege enchantments/books from " + changed + " item(s) of " + player.getName());
        }
        return changed;
    }

    private int sweep(Inventory inventory, String keep) {
        int changed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack cleaned = clean(contents[i], keep);
            if (cleaned != contents[i]) {
                inventory.setItem(i, cleaned);
                changed++;
            }
        }
        return changed;
    }

    /** The same instance when nothing changed; null to delete; else a cleaned copy. */
    private ItemStack clean(ItemStack item, String keep) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return item;
        String bookToken = bookToken(item);
        if (bookToken != null) return SiegeEnchantMarkers.keepBook(bookToken, keep) ? item : null;

        String raw = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        if (raw == null) return item;
        Map<String, Integer> current = new LinkedHashMap<>();
        item.getEnchantments().forEach((e, level) -> current.put(e.getKey().toString(), level));
        SiegeEnchantMarkers.Reversion reversion = SiegeEnchantMarkers.revert(current, SiegeEnchantMarkers.decode(raw),
                token -> token.equals(keep));
        if (!reversion.changed() && !reversion.remaining().isEmpty()) return item;

        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        for (String key : current.keySet()) {
            Enchantment e = enchantmentQuiet(key);
            if (e != null && !reversion.enchantments().containsKey(key)) meta.removeEnchant(e);
        }
        reversion.enchantments().forEach((key, level) -> {
            Enchantment e = enchantmentQuiet(key);
            if (e != null && !level.equals(current.get(key))) meta.addEnchant(e, level, true);
        });
        if (reversion.remaining().isEmpty()) meta.getPersistentDataContainer().remove(markerKey);
        else meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, SiegeEnchantMarkers.encode(reversion.remaining()));
        copy.setItemMeta(meta);
        return copy;
    }

    private static Enchantment enchantmentQuiet(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : Registry.ENCHANTMENT.get(namespaced);
    }
}
