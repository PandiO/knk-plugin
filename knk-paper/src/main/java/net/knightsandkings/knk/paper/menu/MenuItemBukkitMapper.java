package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.MenuItemPresentation;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.paper.mapper.MaterialNamespaceResolver;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Converts a rendered {@link RuntimeMenuItem} + its resolved
 * {@link MenuItemPresentation} into a Bukkit {@link ItemStack}.
 * <p>
 * InventoryMenu Phase 9: binding resolution, E7 colour precedence and E8 lore
 * omission/expansion moved into knk-core ({@link MenuItemPresentation},
 * {@code MenuSectionRenderer}) so they are unit-tested there; this class now
 * only maps the resolved values onto Bukkit types - including the E6
 * item-meta bindings ({@code Material}, {@code Amount}, {@code BannerPatterns},
 * {@code SkullOwner}; {@code DisplayMode} arrives as the item's effective
 * display mode). Every data problem is logged <em>once</em> per item and value,
 * not once per render tick.
 * <p>
 * Kept in knk-paper (not knk-core) because it's the one place that needs
 * {@code Material}/{@code ItemStack} - DESIGN_REVIEW.md's "keep
 * Bukkit-specific types out of the Model layer" isolation point.
 */
public final class MenuItemBukkitMapper {

    private static final Material FALLBACK_MATERIAL = Material.PAPER;
    private static final String DISABLED_LORE_LINE = ChatColor.GRAY + "(Unavailable)";
    private static final int MAX_STACK_SIZE_COMPONENT = 99;

    private MenuItemBukkitMapper() {
    }

    /**
     * @param item                  the rendered snapshot (effective display mode, Render-filtered actions)
     * @param presentation          the item's resolved bindings for this render
     * @param materialRefNamespaceKey the resolved {@code materialRefId} namespace key, or null -
     *                              used when there is no {@code Material} binding or it doesn't
     *                              resolve; PAPER after that (NFR-3.2.3's "safe fallback")
     * @param permissionChecker     the viewer's {@code hasPermission} (Phase 4 {@code actionPermission})
     */
    public static ItemStack toItemStack(RuntimeMenuItem item, MenuItemPresentation presentation,
                                        String materialRefNamespaceKey, Predicate<String> permissionChecker) {
        Material material = resolveMaterial(item, presentation, materialRefNamespaceKey);
        BannerPatternSpec banner = presentation.banner();
        if (banner != null && banner.baseColor() != null) {
            material = withBannerBaseColor(item, material, banner.baseColor());
        }

        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();

        int amount;
        if (presentation.amount() != null) {
            // E6: 1-64 (already clamped in core). A counter above the material's
            // own max stack (banners: 16) raises the stack's max-stack-size
            // component so the number actually shows.
            amount = presentation.amount();
            if (meta != null && amount > material.getMaxStackSize()) {
                meta.setMaxStackSize(Math.min(MAX_STACK_SIZE_COMPONENT, amount));
            }
        } else {
            amount = Math.max(1, Math.min(item.amount(), material.getMaxStackSize()));
        }

        if (meta != null) {
            boolean actionDenied = !item.isActionAllowedFor(permissionChecker);
            applyName(presentation, meta);
            applyLore(item, presentation, meta, actionDenied);
            applyDisplayModeStyling(item, meta);
            if (banner != null) {
                applyBannerPatterns(item, meta, banner);
            }
            if (presentation.skullOwner() != null) {
                try {
                    applySkullOwner(item, meta, presentation.skullOwner());
                } catch (RuntimeException e) {
                    MenuRenderer.warnOnce("skull|" + item.id() + "|" + presentation.skullOwner(), "Menu item (id "
                            + item.id() + "): SkullOwner '" + presentation.skullOwner() + "' rejected - " + e.getMessage());
                }
            }
            itemStack.setItemMeta(meta);
        }
        itemStack.setAmount(amount);

        for (String warning : presentation.warnings()) {
            MenuRenderer.warnOnce("item|" + item.id() + "|" + warning, "Menu item (id " + item.id() + "): " + warning);
        }
        return itemStack;
    }

    private static Material resolveMaterial(RuntimeMenuItem item, MenuItemPresentation presentation, String refKey) {
        if (presentation.materialKey() != null) {
            Material bound = MaterialNamespaceResolver.resolve(presentation.materialKey());
            if (bound != null && bound.isItem() && !bound.isAir()) {
                return bound;
            }
            MenuRenderer.warnOnce("material|" + item.id() + "|" + presentation.materialKey(), "Menu item (id " + item.id()
                    + "): Material binding value '" + presentation.materialKey()
                    + "' is not an item material - falling back to MaterialRefId/" + FALLBACK_MATERIAL);
        }

        Material fromRef = MaterialNamespaceResolver.resolve(refKey);
        if (fromRef != null) {
            return fromRef;
        }
        if (refKey != null) {
            MenuRenderer.warnOnce("materialref|" + item.id() + "|" + refKey, "Menu item (id " + item.id()
                    + ") has unresolvable material namespace key '" + refKey + "'; falling back to " + FALLBACK_MATERIAL);
        }
        return FALLBACK_MATERIAL;
    }

    /** E6/J10: a banner's base colour is its material ({@code <COLOR>_BANNER}); only applied to banners. */
    private static Material withBannerBaseColor(RuntimeMenuItem item, Material material, String baseColor) {
        if (!isBanner(material)) {
            return material;
        }
        Material colored = Material.matchMaterial(baseColor + "_BANNER");
        if (colored == null) {
            MenuRenderer.warnOnce("bannerbase|" + item.id() + "|" + baseColor,
                    "Menu item (id " + item.id() + "): no banner material for base colour " + baseColor);
            return material;
        }
        return colored;
    }

    private static boolean isBanner(Material material) {
        return material.name().endsWith("_BANNER") && !material.name().endsWith("_WALL_BANNER");
    }

    private static void applyName(MenuItemPresentation presentation, ItemMeta meta) {
        if (presentation.name() != null) {
            meta.setDisplayName(DisplayTextFormatter.translateToLegacy(presentation.name()));
        }
    }

    private static void applyLore(RuntimeMenuItem item, MenuItemPresentation presentation, ItemMeta meta,
                                  boolean actionDenied) {
        List<String> lore = new ArrayList<>(presentation.lore().size() + 1);
        for (String line : presentation.lore()) {
            lore.add(DisplayTextFormatter.translateToLegacy(line));
        }

        // actionPermission reuses the same "(Unavailable)" DISABLED styling as an
        // explicit DISABLED displayMode (IMPLEMENTATION_PLAN.md Phase 4).
        if (item.displayMode() == MenuDisplayMode.DISABLED || actionDenied) {
            lore.add(DISABLED_LORE_LINE);
        }

        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
    }

    /**
     * HIGHLIGHT (FR-2.4.1 "render with emphasis/glow") uses the common
     * add-a-hidden-enchant trick, since Bukkit has no first-class "glowing
     * item" API. DISABLED styling is the lore line added in {@link #applyLore}.
     */
    private static void applyDisplayModeStyling(RuntimeMenuItem item, ItemMeta meta) {
        if (item.displayMode() == MenuDisplayMode.HIGHLIGHT) {
            Enchantment glow = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
    }

    /** E6: layers in order; bad pattern keys are skipped (warned once). Pattern tooltip lines are hidden. */
    private static void applyBannerPatterns(RuntimeMenuItem item, ItemMeta meta, BannerPatternSpec banner) {
        if (!(meta instanceof BannerMeta bannerMeta)) {
            MenuRenderer.warnOnce("bannermeta|" + item.id(), "Menu item (id " + item.id()
                    + "): BannerPatterns binding on a non-banner material - ignored");
            return;
        }
        List<Pattern> patterns = new ArrayList<>(banner.layers().size());
        for (BannerPatternSpec.Layer layer : banner.layers()) {
            NamespacedKey key = NamespacedKey.fromString(layer.patternKey());
            PatternType type = key != null ? Registry.BANNER_PATTERN.get(key) : null;
            if (type == null) {
                MenuRenderer.warnOnce("bannerpattern|" + item.id() + "|" + layer.patternKey(), "Menu item (id " + item.id()
                        + "): unknown banner pattern '" + layer.patternKey() + "' - layer skipped");
                continue;
            }
            patterns.add(new Pattern(DyeColor.valueOf(layer.color()), type));
        }
        bannerMeta.setPatterns(patterns);
        bannerMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
    }

    /**
     * E6: UUID or player name. Never blocks the main thread on a Mojang
     * lookup: an online player's own profile, a cached offline player, or a
     * bare profile the client resolves itself.
     */
    private static void applySkullOwner(RuntimeMenuItem item, ItemMeta meta, String owner) {
        if (!(meta instanceof SkullMeta skullMeta)) {
            MenuRenderer.warnOnce("skullmeta|" + item.id(), "Menu item (id " + item.id()
                    + "): SkullOwner binding on a non-skull material - ignored");
            return;
        }
        UUID uuid = parseUuid(owner);
        if (uuid != null) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                skullMeta.setOwningPlayer(online);
            } else {
                skullMeta.setPlayerProfile(Bukkit.createProfile(uuid));
            }
            return;
        }
        Player online = Bukkit.getPlayerExact(owner);
        if (online != null) {
            skullMeta.setOwningPlayer(online);
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(owner);
        if (cached != null) {
            skullMeta.setOwningPlayer(cached);
        } else {
            skullMeta.setPlayerProfile(Bukkit.createProfile(owner));
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
