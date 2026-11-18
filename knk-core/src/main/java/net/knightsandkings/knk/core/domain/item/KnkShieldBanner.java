package net.knightsandkings.knk.core.domain.item;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;

import java.util.List;

/**
 * A shield's banner as an item blueprint or item instance stores it (PandiO/knk-workspace#232): the shield's base
 * dye colour and its pattern layers <b>bottom to top</b>, the same data Minecraft keeps on the item. Mirrors
 * knk-web-api's {@code ShieldBannerBaseColor} + {@code ShieldBannerLayers}. {@link #isEmpty()} is a plain shield.
 *
 * @param baseColor dye colour name (org.bukkit.DyeColor), e.g. {@code RED}; null when the API sent none
 * @param layers    the pattern layers in order, bottom first
 */
public record KnkShieldBanner(String baseColor, List<Layer> layers) {

    /**
     * One pattern layer.
     *
     * @param patternKey banner-pattern registry key, e.g. {@code minecraft:stripe_top}
     * @param color      dye colour name, e.g. {@code YELLOW}
     */
    public record Layer(String patternKey, String color) {
    }

    public KnkShieldBanner {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }

    public boolean isEmpty() {
        return baseColor == null && layers.isEmpty();
    }

    /**
     * The same banner in the InventoryMenu engine's form ({@link BannerPatternSpec}), the one representation
     * {@code BannerDesignBukkitMapper} maps to Bukkit patterns; unknown colours end up in
     * {@link BannerPatternSpec#errors()}, never thrown. Null for an empty banner.
     */
    public BannerPatternSpec toPatternSpec() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder raw = new StringBuilder(baseColor == null ? "" : baseColor).append('|');
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (i > 0) raw.append(',');
            raw.append(layer.patternKey()).append(':').append(layer.color());
        }
        return BannerPatternSpec.parse(raw.toString());
    }
}
