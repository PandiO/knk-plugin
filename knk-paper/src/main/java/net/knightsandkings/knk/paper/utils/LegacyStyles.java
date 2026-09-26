package net.knightsandkings.knk.paper.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Turns the "&amp;" formatting codes knk-web-api stores on PermissionGroup (KNG-7 —
 * ChatPrimaryColor/ChatSecondaryColor/NameColor, e.g. "&amp;e", "&amp;6&amp;l",
 * "&amp;x&amp;f&amp;f&amp;a&amp;a&amp;0&amp;0") into an Adventure {@link Style}. Same codes as
 * {@link DisplayTextFormatter} and the web-app's "Minecraft text coloring" preview, parsed with
 * '&amp;' directly so no Bukkit class is needed.
 */
public final class LegacyStyles {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private LegacyStyles() {}

    /**
     * @return the style the codes leave in effect (as Minecraft applies them: a color code resets
     * earlier formats), or {@code fallback} when {@code codes} is null, blank or sets nothing.
     */
    public static Style parse(String codes, Style fallback) {
        if (codes == null || codes.isBlank()) {
            return fallback;
        }
        // Probe with a trailing character: the style that applies to it is the style in effect.
        Component parsed = AMPERSAND.deserialize(codes.trim().replace('§', '&') + "x");
        Style style = styleOfLastText(parsed, Style.empty());
        return style == null || style.isEmpty() ? fallback : style;
    }

    /**
     * For a scoreboard team, which only takes one of the 16 named colors: the codes' color, a hex
     * color mapped to the nearest named one, or {@code fallback} when no color is set. Formats
     * such as bold are ignored — a team color can't carry them.
     */
    public static NamedTextColor nameColor(String codes, NamedTextColor fallback) {
        Style style = parse(codes, null);
        TextColor color = style != null ? style.color() : null;
        if (color == null) {
            return fallback;
        }
        return color instanceof NamedTextColor named ? named : NamedTextColor.nearestTo(color);
    }

    private static Style styleOfLastText(Component component, Style inherited) {
        Style here = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        Style result = component instanceof TextComponent text && !text.content().isEmpty() ? here : null;
        for (Component child : component.children()) {
            Style childStyle = styleOfLastText(child, here);
            if (childStyle != null) {
                result = childStyle;
            }
        }
        return result;
    }
}
