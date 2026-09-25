package net.knightsandkings.knk.core.menu;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * InventoryMenu Phase 9 (E7): inline colour for resolved {@code Name}/{@code Lore}
 * text, Bukkit-free so the rule is unit-tested in knk-core.
 * <p>
 * <b>Rule.</b> {@code &}-codes ({@code &0-9}, {@code &a-f}, {@code &k-o},
 * {@code &r}, and {@code &x} for knk-paper's hex form; case-insensitive) are
 * translated to the {@code §} form Minecraft renders - anywhere in the line,
 * including text a getter returned (Siege's view getters return
 * {@code &a…}/{@code &c…} strings on purpose). <b>Precedence:</b> the item's
 * whole-line {@code ChatColorName}/{@code ChatColorDescription} colour is
 * applied <em>first</em>, as a prefix; inline codes <em>after</em> it override
 * it from that point on; {@code &r} resets to Minecraft's default styling, not
 * to the prefix colour. An {@code &} not followed by a valid code is left as-is.
 */
public final class MenuTextColors {

    public static final char SECTION = '§';
    private static final String CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";
    private static final Pattern STRIP = Pattern.compile("(?i)[&" + SECTION + "][0-9a-fk-orx]");

    /** ChatColor enum names (Bukkit's) → legacy code, so a prefix name resolves without Bukkit. */
    private static final Map<String, Character> NAMED = Map.ofEntries(
            Map.entry("BLACK", '0'), Map.entry("DARK_BLUE", '1'), Map.entry("DARK_GREEN", '2'),
            Map.entry("DARK_AQUA", '3'), Map.entry("DARK_RED", '4'), Map.entry("DARK_PURPLE", '5'),
            Map.entry("GOLD", '6'), Map.entry("GRAY", '7'), Map.entry("DARK_GRAY", '8'),
            Map.entry("BLUE", '9'), Map.entry("GREEN", 'a'), Map.entry("AQUA", 'b'),
            Map.entry("RED", 'c'), Map.entry("LIGHT_PURPLE", 'd'), Map.entry("YELLOW", 'e'),
            Map.entry("WHITE", 'f'), Map.entry("MAGIC", 'k'), Map.entry("OBFUSCATED", 'k'),
            Map.entry("BOLD", 'l'), Map.entry("STRIKETHROUGH", 'm'), Map.entry("UNDERLINE", 'n'),
            Map.entry("ITALIC", 'o'), Map.entry("RESET", 'r')
    );

    private MenuTextColors() {
    }

    /** Translates every valid {@code &}-code in {@code text} to its {@code §} form. */
    public static String translate(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && CODES.indexOf(chars[i + 1]) > -1) {
                chars[i] = SECTION;
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    /** The {@code §}-prefix for a ChatColor name ("GRAY", "dark_red", …), or {@code ""} if null/unknown. */
    public static String prefix(String chatColorName) {
        if (chatColorName == null || chatColorName.isBlank()) {
            return "";
        }
        Character code = NAMED.get(chatColorName.trim().toUpperCase(Locale.ROOT));
        return code != null ? "" + SECTION + code : "";
    }

    /** Whether {@code chatColorName} is a known ChatColor name (null/blank counts as "no prefix", i.e. valid). */
    public static boolean isKnownColorName(String chatColorName) {
        return chatColorName == null || chatColorName.isBlank()
                || NAMED.containsKey(chatColorName.trim().toUpperCase(Locale.ROOT));
    }

    /** The E7 rule: whole-line prefix colour first, then inline codes translated (they override from where they appear). */
    public static String apply(String chatColorName, String text) {
        if (text == null) {
            return null;
        }
        return prefix(chatColorName) + translate(text);
    }

    /** Removes every {@code &}- and {@code §}-code. */
    public static String strip(String text) {
        return text == null ? null : STRIP.matcher(text).replaceAll("");
    }
}
