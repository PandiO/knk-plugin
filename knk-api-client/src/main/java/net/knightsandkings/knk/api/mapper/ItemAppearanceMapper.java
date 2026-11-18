package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.ItemBannerLayerDto;
import net.knightsandkings.knk.core.domain.item.KnkArmorTrim;
import net.knightsandkings.knk.core.domain.item.KnkShieldBanner;

import java.util.List;
import java.util.Objects;

/**
 * The #232 look fields of knk-web-api's item blueprint, item instance and lootbox claim DTOs
 * ({@code shieldBannerBaseColor}, {@code shieldBannerLayers}, {@code armorTrimPattern}, {@code armorTrimMaterial})
 * to the knk-core records. Null-safe: an API from before #232 sends none of them and maps to null.
 */
public final class ItemAppearanceMapper {
    private ItemAppearanceMapper() {}

    /** Null when the API sent neither a base colour nor a layer (a plain item). */
    public static KnkShieldBanner toShieldBanner(String baseColor, List<ItemBannerLayerDto> layers) {
        List<KnkShieldBanner.Layer> mapped = layers == null
                ? List.of()
                : layers.stream()
                        .filter(Objects::nonNull)
                        .filter(l -> l.patternKey() != null && !l.patternKey().isBlank())
                        .map(l -> new KnkShieldBanner.Layer(l.patternKey(), l.color()))
                        .toList();
        boolean hasBase = baseColor != null && !baseColor.isBlank();
        if (!hasBase && mapped.isEmpty()) {
            return null;
        }
        return new KnkShieldBanner(hasBase ? baseColor : null, mapped);
    }

    /** Null unless both the pattern and the material were sent (the API stores both or neither). */
    public static KnkArmorTrim toArmorTrim(String pattern, String material) {
        if (pattern == null || pattern.isBlank() || material == null || material.isBlank()) {
            return null;
        }
        return new KnkArmorTrim(pattern, material);
    }
}
