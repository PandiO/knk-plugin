package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.KnkArmorTrim;
import net.knightsandkings.knk.core.domain.item.KnkShieldBanner;
import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import net.knightsandkings.knk.paper.clan.BannerDesignBukkitMapper;
import org.bukkit.DyeColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.ShieldMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The look an item blueprint or item instance stores (PandiO/knk-workspace#232) on and off a Bukkit item:
 * <ul>
 *   <li><b>Shield banner</b> ({@link KnkShieldBanner}): on a shield ({@link ShieldMeta}) the base colour and the
 *       pattern layers, bottom to top; on a banner item ({@link BannerMeta}) the layers only - a banner's base colour
 *       is its material. Goes through {@link BannerPatternSpec} and {@link BannerDesignBukkitMapper#toLayers}, so
 *       shields, clan banners and menu banners render one way.</li>
 *   <li><b>Armour trim</b> ({@link KnkArmorTrim}): pattern and material on an {@link ArmorMeta}.</li>
 * </ul>
 * A pattern, trim or colour the running server doesn't know is skipped and reported via {@code warn}, never thrown
 * (an item authored for a newer version still builds). Reading is the inverse, for the item scan.
 */
public final class ItemAppearanceBukkitMapper {

    private static final Logger LOGGER = Logger.getLogger(ItemAppearanceBukkitMapper.class.getName());

    private ItemAppearanceBukkitMapper() {
    }

    /** Both of {@link #applyShieldBanner} and {@link #applyArmorTrim}, with the warnings logged. */
    public static void apply(ItemMeta meta, KnkShieldBanner shieldBanner, KnkArmorTrim armorTrim, String context) {
        Consumer<String> warn = message -> LOGGER.warning(context + ": " + message);
        applyShieldBanner(meta, shieldBanner, warn);
        applyArmorTrim(meta, armorTrim, warn);
    }

    /**
     * Puts {@code banner} on a shield or banner item. Nothing happens for a null/empty banner or a meta of another
     * kind (a sword blueprint with banner data is left alone). Layers already on the item are replaced.
     */
    public static void applyShieldBanner(ItemMeta meta, KnkShieldBanner banner, Consumer<String> warn) {
        if (meta == null || banner == null || banner.isEmpty() || !(meta instanceof BannerMeta bannerMeta)) {
            return;
        }
        BannerPatternSpec spec = banner.toPatternSpec();
        if (spec == null) {
            return;
        }
        spec.errors().forEach(error -> warn.accept("shield banner: " + error));
        if (meta instanceof ShieldMeta shieldMeta && spec.baseColor() != null) {
            shieldMeta.setBaseColor(DyeColor.valueOf(spec.baseColor()));
        }
        bannerMeta.setPatterns(BannerDesignBukkitMapper.toLayers(spec, BannerDesignBukkitMapper::resolvePatternType,
                Pattern::new, key -> warn.accept("shield banner: unknown banner pattern '" + key + "' - layer skipped")));
    }

    /** Puts {@code trim} on an armour piece; nothing for a null/incomplete trim or a non-armour meta. */
    public static void applyArmorTrim(ItemMeta meta, KnkArmorTrim trim, Consumer<String> warn) {
        if (meta == null || trim == null || !trim.isComplete() || !(meta instanceof ArmorMeta armorMeta)) {
            return;
        }
        TrimPattern pattern = resolve(Registry.TRIM_PATTERN, trim.pattern());
        TrimMaterial material = resolve(Registry.TRIM_MATERIAL, trim.material());
        if (pattern == null || material == null) {
            warn.accept("armour trim: unknown " + (pattern == null ? "pattern '" + trim.pattern() + "'" : "material '" + trim.material() + "'")
                    + " - trim skipped");
            return;
        }
        armorMeta.setTrim(new ArmorTrim(material, pattern));
    }

    /**
     * The shield banner on {@code meta} (the inverse of {@link #applyShieldBanner}, for the item scan): a shield's
     * base colour and layers, a banner item's layers (base colour null: it is the material). Null for anything else
     * and for a shield without a pattern (a plain shield stores nothing).
     */
    public static KnkShieldBanner readShieldBanner(ItemMeta meta) {
        if (!(meta instanceof BannerMeta bannerMeta)) {
            return null;
        }
        List<KnkShieldBanner.Layer> layers = new ArrayList<>();
        for (Pattern pattern : bannerMeta.getPatterns()) {
            if (pattern == null || pattern.getPattern() == null) {
                continue;
            }
            layers.add(new KnkShieldBanner.Layer(pattern.getPattern().getKey().toString(), pattern.getColor().name()));
        }
        String baseColor = meta instanceof ShieldMeta shieldMeta && shieldMeta.getBaseColor() != null
                ? shieldMeta.getBaseColor().name()
                : null;
        if (baseColor == null && layers.isEmpty()) {
            return null;
        }
        return new KnkShieldBanner(baseColor, layers);
    }

    /** The armour trim on {@code meta}, or null when it has none (or isn't armour). */
    public static KnkArmorTrim readArmorTrim(ItemMeta meta) {
        if (!(meta instanceof ArmorMeta armorMeta) || !armorMeta.hasTrim()) {
            return null;
        }
        ArmorTrim trim = armorMeta.getTrim();
        if (trim == null) {
            return null;
        }
        return new KnkArmorTrim(trim.getPattern().getKey().toString(), trim.getMaterial().getKey().toString());
    }

    private static <T extends org.bukkit.Keyed> T resolve(Registry<T> registry, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        NamespacedKey namespaced = NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT));
        return namespaced != null ? registry.get(namespaced) : null;
    }
}
