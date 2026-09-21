package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.MenuVariablePlaceholderText;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.paper.mapper.MaterialNamespaceResolver;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Converts an assembled {@link RuntimeMenuItem} into a Bukkit {@link ItemStack}
 * ready to place in an Inventory. Name/lore text is Phase 2's literal
 * placeholder (see {@link MenuVariablePlaceholderText}) - the binding's raw
 * Expression, not real variable resolution (Phase 3).
 * <p>
 * Kept in knk-paper (not knk-core) because it's the one place this phase
 * needs {@code Material}/{@code ItemStack} - DESIGN_REVIEW.md's "keep
 * Bukkit-specific types out of the Model layer" isolation point.
 */
public final class MenuItemBukkitMapper {

    private static final Logger LOGGER = Logger.getLogger(MenuItemBukkitMapper.class.getName());
    private static final Material FALLBACK_MATERIAL = Material.PAPER;
    private static final String DISABLED_LORE_LINE = ChatColor.GRAY + "(Unavailable)";

    private MenuItemBukkitMapper() {
    }

    /**
     * @param materialNamespaceKey the resolved {@code KnkMinecraftMaterialRef.namespaceKey}
     *                              for {@code item.materialRefId()}, or null if the item has
     *                              no material ref set / it couldn't be resolved - falls back
     *                              to {@link #FALLBACK_MATERIAL} rather than failing the whole
     *                              render (NFR-3.2.3's "safe fallback for missing data").
     */
    public static ItemStack toItemStack(RuntimeMenuItem item, String materialNamespaceKey) {
        if (item.displayMode() == MenuDisplayMode.HIDDEN) {
            return null;
        }

        Material material = MaterialNamespaceResolver.resolve(materialNamespaceKey);
        if (material == null) {
            if (materialNamespaceKey != null) {
                LOGGER.warning("Menu item (id " + item.id() + ") has unresolvable material namespace key '"
                        + materialNamespaceKey + "'; falling back to " + FALLBACK_MATERIAL);
            }
            material = FALLBACK_MATERIAL;
        }

        ItemStack itemStack = new ItemStack(material);
        itemStack.setAmount(Math.max(1, Math.min(item.amount(), itemStack.getMaxStackSize())));

        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            applyName(item, meta);
            applyLore(item, meta);
            applyDisplayModeStyling(item, meta);
            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }

    private static void applyName(RuntimeMenuItem item, ItemMeta meta) {
        String name = MenuVariablePlaceholderText.resolveName(item.variableBindings());
        if (name == null) {
            return;
        }

        String colored = item.chatColorName() != null ? prefixColor(item.chatColorName(), name) : name;
        meta.setDisplayName(DisplayTextFormatter.translateToLegacy(colored));
    }

    private static void applyLore(RuntimeMenuItem item, ItemMeta meta) {
        List<String> loreLines = MenuVariablePlaceholderText.resolveLore(item.variableBindings());
        List<String> lore = new ArrayList<>(loreLines.size() + 1);
        for (String line : loreLines) {
            String colored = item.chatColorDescription() != null ? prefixColor(item.chatColorDescription(), line) : line;
            lore.add(DisplayTextFormatter.translateToLegacy(colored));
        }

        if (item.displayMode() == MenuDisplayMode.DISABLED) {
            lore.add(DISABLED_LORE_LINE);
        }

        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
    }

    /**
     * HIGHLIGHT (FR-2.4.1 "render with emphasis/glow") uses the common
     * add-a-hidden-enchant trick, since Bukkit has no first-class "glowing
     * item" API. DISABLED styling is handled via the lore line added in
     * {@link #applyLore} - this phase doesn't grey out the icon itself
     * (Bukkit item rendering doesn't support that without a texture pack).
     */
    private static void applyDisplayModeStyling(RuntimeMenuItem item, ItemMeta meta) {
        if (item.displayMode() == MenuDisplayMode.HIGHLIGHT) {
            Enchantment glow = glowEnchantment();
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
    }

    /**
     * Any enchantment works for the hidden-enchant glow trick; UNBREAKING is
     * applicable to (and visually glows on) effectively every item type,
     * unlike enchantments restricted to specific item categories. Resolved
     * via {@link Registry#ENCHANTMENT} on each call (not cached in a static
     * field), matching this codebase's existing enchantment-lookup convention
     * (see {@code EnchantmentDefinitionBukkitMapper}) rather than a deprecated
     * static {@code Enchantment.*} field.
     */
    private static Enchantment glowEnchantment() {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
    }

    private static String prefixColor(String chatColorName, String text) {
        try {
            return ChatColor.valueOf(chatColorName.toUpperCase(Locale.ROOT)) + text;
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Unknown chat color name '" + chatColorName + "', ignoring");
            return text;
        }
    }
}
