package net.knightsandkings.knk.paper.clan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkClan;
import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Builds banner items from a {@link KnkBannerDesign} (Siege Phase 1,
 * docs/specs/siege-minigame/DESIGN.md §3.1). Goes through {@link BannerPatternSpec} - the
 * InventoryMenu engine's banner form - so siege menus and item building render a design
 * identically. Layers are applied bottom to top; a pattern key the running server doesn't know
 * (e.g. authored for a newer version) is skipped and reported via {@code warn}, never thrown.
 */
public final class BannerDesignBukkitMapper {
    private BannerDesignBukkitMapper() {}

    /** Plain banner item for a design (no display name). */
    public static ItemStack toItemStack(KnkBannerDesign design, Consumer<String> warn) {
        return toItemStack(design.toPatternSpec(), warn, "banner design " + design.id());
    }

    /** The clan's banner, named after the clan in its chat colour. Plain white banner if the clan came without one. */
    public static ItemStack toItemStack(KnkClan clan, Consumer<String> warn) {
        ItemStack item = clan.bannerDesign() != null
                ? toItemStack(clan.bannerDesign(), warn)
                : new ItemStack(Material.WHITE_BANNER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && clan.name() != null) {
            meta.displayName(Component.text(clan.name(), chatColor(clan.chatColor()))
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack toItemStack(BannerPatternSpec spec, Consumer<String> warn, String context) {
        spec.errors().forEach(error -> warn.accept(context + ": " + error));
        ItemStack item = new ItemStack(bannerMaterial(spec.baseColor()));
        if (item.getItemMeta() instanceof BannerMeta meta) {
            meta.setPatterns(toLayers(spec, BannerDesignBukkitMapper::resolvePatternType, Pattern::new,
                    key -> warn.accept(context + ": unknown banner pattern '" + key + "' - layer skipped")));
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * The spec's layers as Bukkit patterns, bottom first, for a banner <em>block</em> (Siege Phase 5:
     * the objective banner's capture gradient); unknown keys are skipped and reported via {@code warn}.
     */
    public static List<Pattern> toPatterns(BannerPatternSpec spec, Consumer<String> warn) {
        spec.errors().forEach(warn);
        return toLayers(spec, BannerDesignBukkitMapper::resolvePatternType, Pattern::new,
                key -> warn.accept("unknown banner pattern '" + key + "' - layer skipped"));
    }

    /** The team's banner base colour (a dye colour name), or null when the team came without a banner. */
    public static String baseColor(KnkBannerDesign design) {
        return design == null ? null : design.toPatternSpec().baseColor();
    }

    /** {@code <COLOR>_BANNER}; white when the colour is missing or unknown. */
    public static Material bannerMaterial(String baseColor) {
        if (baseColor == null) return Material.WHITE_BANNER;
        Material material = Material.matchMaterial(baseColor.toUpperCase(Locale.ROOT) + "_BANNER");
        return material != null ? material : Material.WHITE_BANNER;
    }

    /**
     * Layers in spec order (bottom first); keys the resolver doesn't know go to {@code onUnknown}.
     * Generic over the pattern type only so the ordering/skip logic is testable without a server
     * (PatternType can't even be mocked outside one - its class init reads the registry).
     */
    static <P, L> List<L> toLayers(BannerPatternSpec spec, Function<String, P> resolver,
                                   BiFunction<DyeColor, P, L> factory, Consumer<String> onUnknown) {
        List<L> result = new ArrayList<>(spec.layers().size());
        for (BannerPatternSpec.Layer layer : spec.layers()) {
            P type = resolver.apply(layer.patternKey());
            if (type == null) {
                onUnknown.accept(layer.patternKey());
                continue;
            }
            result.add(factory.apply(DyeColor.valueOf(layer.color()), type));
        }
        return result;
    }

    private static PatternType resolvePatternType(String patternKey) {
        NamespacedKey key = NamespacedKey.fromString(patternKey);
        return key != null ? Registry.BANNER_PATTERN.get(key) : null;
    }

    private static NamedTextColor chatColor(String name) {
        NamedTextColor color = name != null ? NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT)) : null;
        return color != null ? color : NamedTextColor.WHITE;
    }
}
