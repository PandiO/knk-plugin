package net.knightsandkings.knk.core.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * InventoryMenu Phase 9 (E6): the one serialized form of banner patterns a
 * {@code BannerPatterns} variable binding resolves to, parsed Bukkit-free.
 * <pre>
 *   spec   := [BASE_COLOR] "|" layers      (base colour given - may be empty)
 *           | layers                       (no base colour)
 *   layers := layer ("," layer)*           (may be empty)
 *   layer  := pattern ":" COLOR
 * </pre>
 * {@code pattern} is a banner-pattern registry key, bare or namespaced
 * ({@code stripe_bottom}, {@code minecraft:border}); {@code COLOR}/{@code BASE_COLOR}
 * is one of the 16 dye colour names, case-insensitive ({@code RED},
 * {@code light_blue}). Whitespace around tokens is ignored. Layers keep their
 * order (bottom first, like the in-game loom). Examples:
 * {@code WHITE|stripe_bottom:RED,border:BLACK}, {@code stripe_top:GREEN},
 * {@code RED|} (plain red banner).
 * <p>
 * Parsing never throws: an invalid base colour or layer is left out and
 * described in {@link #errors()} (knk-paper logs those once and renders the
 * rest). The base colour maps to the banner <em>material</em>
 * ({@code <BASE_COLOR>_BANNER}), since modern banners carry no separate base
 * colour field.
 */
public record BannerPatternSpec(String baseColor, List<Layer> layers, List<String> errors) {

    public record Layer(String patternKey, String color) {
    }

    public static final Set<String> DYE_COLORS = Set.of(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK");

    private static final Pattern PATTERN_KEY = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    public BannerPatternSpec {
        layers = List.copyOf(layers);
        errors = List.copyOf(errors);
    }

    public boolean isEmpty() {
        return baseColor == null && layers.isEmpty();
    }

    /** @return the parsed spec, or null when {@code raw} is null/blank (binding "not set"). */
    public static BannerPatternSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> errors = new ArrayList<>();
        String baseColor = null;
        String layerPart = raw;

        int bar = raw.indexOf('|');
        if (bar >= 0) {
            String base = raw.substring(0, bar).trim();
            layerPart = raw.substring(bar + 1);
            if (!base.isEmpty()) {
                String normalized = normalizeColor(base);
                if (normalized != null) {
                    baseColor = normalized;
                } else {
                    errors.add("unknown base colour '" + base + "'");
                }
            }
        }

        List<Layer> layers = new ArrayList<>();
        for (String token : layerPart.split(",")) {
            String layer = token.trim();
            if (layer.isEmpty()) {
                continue;
            }
            int colon = layer.lastIndexOf(':');
            if (colon <= 0 || colon == layer.length() - 1) {
                errors.add("layer '" + layer + "' is not pattern:COLOR");
                continue;
            }
            String patternKey = layer.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String color = normalizeColor(layer.substring(colon + 1));
            if (!PATTERN_KEY.matcher(patternKey).matches()) {
                errors.add("layer '" + layer + "' has an invalid pattern key");
                continue;
            }
            if (color == null) {
                errors.add("layer '" + layer + "' has an unknown colour");
                continue;
            }
            layers.add(new Layer(patternKey, color));
        }

        return new BannerPatternSpec(baseColor, layers, errors);
    }

    private static String normalizeColor(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return DYE_COLORS.contains(normalized) ? normalized : null;
    }
}
