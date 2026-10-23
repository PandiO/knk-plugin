package net.knightsandkings.knk.core.navigation.compass;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which item counts as the navigation compass (KNG-234): any item whose material and plain display name
 * match this rule, whatever made it (an item blueprint, an admin command, a kit). The rule is data from
 * the web app's navigation settings, so the behaviour is not tied to one hard-coded item.
 *
 * <p>Matching is forgiving on purpose: the material is compared without a {@code minecraft:} namespace and in
 * any case, the display name without colour codes ({@code §a}, {@code &a}, hex forms) and in any case, so
 * {@code "&6Navigation Compass"} typed in the web app matches the {@code §6Navigation Compass} a blueprint
 * renders.
 *
 * @param enabled     master switch: false means no item is a compass
 * @param material    the material, e.g. {@code COMPASS} or {@code minecraft:recovery_compass}
 * @param displayName the display name to match, plain text; blank means "any name" (material only)
 * @param nameMatch   whether the item's name must be exactly it or merely contain it
 */
public record CompassItemRule(boolean enabled, String material, String displayName, NameMatch nameMatch) {

    /** How the display name is compared. */
    public enum NameMatch {
        EXACT,
        CONTAINS;

        /** Any case; null for anything else. */
        public static NameMatch parse(String value) {
            if (value == null) {
                return null;
            }
            for (NameMatch match : values()) {
                if (match.name().equalsIgnoreCase(value.trim())) {
                    return match;
                }
            }
            return null;
        }
    }

    public static final String DEFAULT_MATERIAL = "COMPASS";
    public static final String DEFAULT_DISPLAY_NAME = "Navigation Compass";

    /** Legacy colour/format codes with either marker, and the {@code §x§r§r§g§g§b§b} hex form. */
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&]x(?:[§&][0-9a-f]){6}|[§&][0-9a-fk-or]");

    public CompassItemRule {
        material = normalizeMaterial(material);
        if (material.isEmpty()) {
            material = DEFAULT_MATERIAL;
        }
        displayName = displayName == null ? "" : displayName.trim();
        nameMatch = nameMatch == null ? NameMatch.EXACT : nameMatch;
    }

    /** A compass is a plain {@code COMPASS} called "Navigation Compass" (the web app's initial settings). */
    public static CompassItemRule defaults() {
        return new CompassItemRule(true, DEFAULT_MATERIAL, DEFAULT_DISPLAY_NAME, NameMatch.EXACT);
    }

    /**
     * Whether an item is the compass.
     *
     * @param itemMaterial the item's material name, e.g. {@code Material.name()}
     * @param itemName     the item's display name, or null when it has none
     */
    public boolean matches(String itemMaterial, String itemName) {
        if (!enabled || itemMaterial == null || !normalizeMaterial(itemMaterial).equals(material)) {
            return false;
        }
        if (displayName.isEmpty()) {
            return true;
        }
        String wanted = plain(displayName);
        if (wanted.isEmpty()) {
            return true;
        }
        String actual = plain(itemName);
        return nameMatch == NameMatch.EXACT ? actual.equals(wanted) : actual.contains(wanted);
    }

    /** {@code minecraft:Recovery_Compass} → {@code RECOVERY_COMPASS}. */
    public static String normalizeMaterial(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim();
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    /** The text without colour codes, trimmed and lower-cased. */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return COLOR_CODES.matcher(text).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }
}
