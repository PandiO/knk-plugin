package net.knightsandkings.knk.paper.enchantbook;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.enchantbook.EnchantBookPayload;
import net.knightsandkings.knk.core.enchantbook.EnchantBookText;
import net.knightsandkings.knk.paper.mapper.EnchantmentDefinitionBukkitMapper;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Permanent enchantment books as items (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.1;
 * Linear KNG-5). A book is an {@link KnkItemBlueprint} with material {@code minecraft:enchanted_book} and at
 * least one default enchantment; {@link net.knightsandkings.knk.paper.mapper.ItemBlueprintBukkitMapper#fromBlueprint}
 * calls {@link #decorate} so kits, the item catalog and {@code /knk itemblueprints give} all hand out
 * working books.
 * <p>
 * The book carries its enchantment only as a {@link #BOOK_KEY} PDC tag ({@link EnchantBookPayload}) - no
 * vanilla stored enchantment, so the vanilla anvil can't use it - plus display lore and a forced glint.
 */
public final class EnchantBookItems {

    private static final Logger LOGGER = Logger.getLogger(EnchantBookItems.class.getName());

    /** Same namespace {@code new NamespacedKey(plugin, ...)} gives this plugin ("KnightsAndKings"). */
    public static final NamespacedKey BOOK_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_enchant_book"));

    /**
     * The enchantment definition's max level, copied from the blueprint when the book is built (KNG-6): the
     * grade cap divides it, as v1 divided its own {@code Enchantments.MaxLevel}. Books built before KNG-6
     * don't have it; {@link EnchantBooks} then falls back to the vanilla / registry max level.
     */
    public static final NamespacedKey MAX_LEVEL_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_enchant_book_max"));

    private EnchantBookItems() {
    }

    /** What the book teaches and how to show it. */
    public record Resolved(EnchantBookPayload payload, String displayName) {
    }

    public static boolean isBookBlueprint(KnkItemBlueprint blueprint, Material material) {
        return material == Material.ENCHANTED_BOOK && blueprint != null
                && blueprint.defaultEnchantments() != null && !blueprint.defaultEnchantments().isEmpty();
    }

    /**
     * Turns the item built from a book blueprint into a permanent enchantment book: tag, "Teaches" lore and
     * glint. The first default enchantment is the one it teaches. When that one can't be resolved on this
     * server the item stays a plain (useless) book and a warning is logged.
     */
    public static void decorate(ItemStack book, KnkItemBlueprint blueprint) {
        List<KnkItemBlueprintDefaultEnchantment> enchantments = blueprint.defaultEnchantments();
        if (enchantments.size() > 1) {
            LOGGER.warning("Enchantment book blueprint " + blueprint.id() + " has " + enchantments.size()
                    + " default enchantments; a book teaches only the first");
        }
        Optional<Resolved> resolved = resolve(enchantments.get(0));
        if (resolved.isEmpty()) {
            LOGGER.warning("Enchantment book blueprint " + blueprint.id() + ": its enchantment can't be resolved on this server; not a usable book");
            return;
        }

        ItemMeta meta = book.getItemMeta();
        if (meta == null) {
            return;
        }
        EnchantBookPayload payload = resolved.get().payload();
        meta.getPersistentDataContainer().set(BOOK_KEY, PersistentDataType.STRING, payload.encode());
        Integer definitionMax = enchantments.get(0).enchantmentMaxLevel();
        if (definitionMax != null && definitionMax > 0) {
            meta.getPersistentDataContainer().set(MAX_LEVEL_KEY, PersistentDataType.INTEGER, definitionMax);
        }
        meta.setEnchantmentGlintOverride(true);

        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(0, ChatColor.GRAY + EnchantBookText.teaches(ChatColor.LIGHT_PURPLE + resolved.get().displayName(), payload.level()));
        lore.add(ChatColor.DARK_GRAY + "Right-click to choose an item to enchant.");
        meta.setLore(lore);
        book.setItemMeta(meta);
    }

    /**
     * Resolves a blueprint default enchantment through {@link EnchantmentDefinitionBukkitMapper} - the same
     * path {@code /knk itemblueprints give} and {@code EnchantmentDefinitionsDebugCommand} use.
     */
    public static Optional<Resolved> resolve(KnkItemBlueprintDefaultEnchantment relation) {
        KnkEnchantmentDefinition definition = EnchantmentDefinitionBukkitMapper.fromDefaultEnchantment(relation);
        if (definition == null) {
            return Optional.empty();
        }
        Integer requested = relation.level() != null && relation.level() > 0 ? relation.level() : null;

        if (Boolean.TRUE.equals(definition.isCustom())) {
            EnchantmentDefinitionBukkitMapper.CustomEnchantmentResolution custom = EnchantmentDefinitionBukkitMapper.toCustom(definition);
            if (!custom.isValid()) {
                return Optional.empty();
            }
            int level = requested != null ? requested : custom.defaultLevel();
            if (level > custom.maxLevel()) {
                LOGGER.warning("Enchantment book: custom " + custom.enchantmentId() + " level " + level + " exceeds max " + custom.maxLevel());
                return Optional.empty();
            }
            String name = EnchantmentRegistry.getInstance().getById(custom.enchantmentId())
                    .map(net.knightsandkings.knk.core.domain.enchantment.Enchantment::displayName)
                    .orElse(EnchantBookText.displayNameFromKey(custom.enchantmentId()));
            return Optional.of(new Resolved(EnchantBookPayload.custom(custom.enchantmentId(), level), name));
        }

        EnchantmentDefinitionBukkitMapper.BukkitEnchantmentResolution vanilla = EnchantmentDefinitionBukkitMapper.toBukkit(definition);
        if (!vanilla.isValid()) {
            return Optional.empty();
        }
        int level = requested != null ? requested : vanilla.defaultLevel();
        String name = definition.displayName() != null && !definition.displayName().isBlank()
                ? definition.displayName()
                : EnchantBookText.displayNameFromKey(vanilla.namespaceKey());
        return Optional.of(new Resolved(EnchantBookPayload.vanilla(vanilla.enchantment().getKey().toString(), level), name));
    }

    /** The payload of a permanent enchantment book, or empty for any other item. */
    public static Optional<EnchantBookPayload> payload(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return EnchantBookPayload.decode(item.getItemMeta().getPersistentDataContainer().get(BOOK_KEY, PersistentDataType.STRING));
    }

    /** The definition max level stamped on the book ({@link #MAX_LEVEL_KEY}), or empty for older books. */
    public static Optional<Integer> definitionMaxLevel(ItemStack book) {
        if (book == null || !book.hasItemMeta()) {
            return Optional.empty();
        }
        Integer max = book.getItemMeta().getPersistentDataContainer().get(MAX_LEVEL_KEY, PersistentDataType.INTEGER);
        return max != null && max > 0 ? Optional.of(max) : Optional.empty();
    }

    public static boolean isBook(ItemStack item) {
        return payload(item).isPresent();
    }
}
