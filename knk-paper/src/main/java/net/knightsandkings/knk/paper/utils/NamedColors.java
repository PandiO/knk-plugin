package net.knightsandkings.knk.paper.utils;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Parses the Minecraft color names knk-web-api stores on PermissionGroup (KNG-7 —
 * ChatPrimaryColor/ChatSecondaryColor/NameColor, e.g. "YELLOW", "DARK_RED"). Those are Bukkit
 * ChatColor names, which lower-cased are exactly Adventure's {@link NamedTextColor#NAMES} keys.
 */
public final class NamedColors {
    private NamedColors() {}

    /**
     * @return the named color, or {@code fallback} when {@code name} is null, blank or not one of
     * the 16 Minecraft colors (the API validates on write, so this is only a safety net).
     */
    public static NamedTextColor parse(String name, NamedTextColor fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        NamedTextColor color = NamedTextColor.NAMES.value(name.trim().replace(' ', '_').toLowerCase(java.util.Locale.ROOT));
        return color != null ? color : fallback;
    }
}
