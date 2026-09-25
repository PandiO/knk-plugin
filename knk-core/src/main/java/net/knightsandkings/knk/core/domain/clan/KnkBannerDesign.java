package net.knightsandkings.knk.core.domain.clan;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A reusable multi-layer banner (Siege Phase 1, docs/specs/siege-minigame/DESIGN.md §3.1).
 * {@link #layers()} is always ordered bottom to top by (sortOrder, id), whatever order the
 * caller passed them in.
 *
 * @param baseColor dye colour name of the banner itself (maps to the {@code <COLOR>_BANNER} material)
 */
public record KnkBannerDesign(int id, String name, String baseColor, List<KnkBannerLayer> layers) {

    private static final Comparator<KnkBannerLayer> BOTTOM_TO_TOP =
            Comparator.comparingInt(KnkBannerLayer::sortOrder).thenComparingInt(KnkBannerLayer::id);

    public KnkBannerDesign {
        List<KnkBannerLayer> sorted = new ArrayList<>(layers == null ? List.of() : layers);
        sorted.sort(BOTTOM_TO_TOP);
        layers = List.copyOf(sorted);
    }

    /**
     * The same design in the InventoryMenu engine's banner form (Phase 9 E6), so menus
     * (Siege Phase 8b) and item building share one representation and one Bukkit mapping.
     * Unknown colours end up in {@link BannerPatternSpec#errors()}, never thrown.
     */
    public BannerPatternSpec toPatternSpec() {
        StringBuilder raw = new StringBuilder(baseColor == null ? "" : baseColor).append('|');
        for (int i = 0; i < layers.size(); i++) {
            KnkBannerLayer layer = layers.get(i);
            if (i > 0) raw.append(',');
            raw.append(layer.patternKey()).append(':').append(layer.color());
        }
        return BannerPatternSpec.parse(raw.toString());
    }
}
