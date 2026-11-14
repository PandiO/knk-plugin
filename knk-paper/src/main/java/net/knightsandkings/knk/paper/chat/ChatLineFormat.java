package net.knightsandkings.knk.paper.chat;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.knightsandkings.knk.paper.utils.LegacyStyles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds a player's public chat line — v1's {@code <rank-tag>-{<Title>}- <Username>: <message>}
 * format (KNG-8), styled per premium tier (KNG-7). Kept free of Bukkit types so it is unit
 * testable; PlayerListener.onChat decides the {@link Rank} and supplies the cached summary.
 * <ul>
 *   <li>Owner/staff: {@code [OWNER]-{Title}- Name: msg} in the fixed ColorOptions owner/staff
 *   colors, title bold (as v1). PandiO/knk-workspace#260: a chat color set by one of the player's
 *   groups (not the Default group's; {@code chat*ColorGroupId} non-null) replaces the fixed one,
 *   field by field, so setting the Staff group's chat colors changes staff chat.</li>
 *   <li>Everyone else: {@code -{Noble Title}- Name: msg} — the premium tier name, if any, goes
 *   inside the braces before the title. Brackets use the summary's chatSecondaryColor, title and
 *   name its chatPrimaryColor ("&amp;" codes, so a tier can also be bold or hex-colored; the API
 *   fills both from the tier, else the Default group), with ColorOptions.defaultformat/
 *   defaultsubjects as the fallback.</li>
 * </ul>
 * The message itself takes the name's color (as in v1) but not its formats, so a bold tier style
 * doesn't bold every message. The {@code -{…}-} segment is left out when there is nothing to put
 * in it (e.g. summary not cached yet).
 */
public final class ChatLineFormat {
    private ChatLineFormat() {}

    public enum Rank { OWNER, STAFF, MEMBER }

    public static Component render(Rank rank, String playerName, UserSummary user, Component message) {
        String title = user != null ? blankToNull(user.titleName()) : null;

        String tag;
        Style bracketStyle;
        Style textStyle;
        Style bracedStyle;
        String braced;
        switch (rank) {
            case OWNER -> {
                tag = "OWNER";
                bracketStyle = groupStyle(user, true, Style.style(ColorOptions.ownerformat));
                textStyle = groupStyle(user, false, Style.style(ColorOptions.ownersubjects));
                bracedStyle = textStyle.decorate(TextDecoration.BOLD);
                braced = title;
            }
            case STAFF -> {
                tag = "STAFF";
                bracketStyle = groupStyle(user, true, Style.style(ColorOptions.staffformat));
                textStyle = groupStyle(user, false, Style.style(ColorOptions.staffsubjects));
                bracedStyle = textStyle.decorate(TextDecoration.BOLD);
                braced = title;
            }
            default -> {
                tag = null;
                bracketStyle = LegacyStyles.parse(user != null ? user.chatSecondaryColor() : null, Style.style(ColorOptions.defaultformat));
                textStyle = LegacyStyles.parse(user != null ? user.chatPrimaryColor() : null, Style.style(ColorOptions.defaultsubjects));
                bracedStyle = textStyle;
                String tier = user != null ? blankToNull(user.premiumTierName()) : null;
                braced = tier == null ? title : (title == null ? tier : tier + " " + title);
            }
        }

        TextComponent.Builder line = Component.text();
        if (tag != null) {
            line.append(Component.text("[", bracketStyle))
                .append(Component.text(tag, textStyle))
                .append(Component.text("]", bracketStyle));
        }
        if (braced != null) {
            line.append(Component.text("-{", bracketStyle))
                .append(Component.text(braced, bracedStyle))
                .append(Component.text("}-", bracketStyle));
        }
        String namePrefix = (tag != null || braced != null) ? " " : "";
        line.append(Component.text(namePrefix + playerName + ": ", textStyle))
            .append(message.colorIfAbsent(textStyle.color()));
        return line.build();
    }

    /**
     * #260: the owner/staff style, unless one of the player's groups (not the Default group) set this chat color:
     * then that color. {@code bracket} = the secondary (bracket) color, else the primary (title and name).
     */
    private static Style groupStyle(UserSummary user, boolean bracket, Style fixed) {
        if (user == null) {
            return fixed;
        }
        Integer from = bracket ? user.chatSecondaryColorGroupId() : user.chatPrimaryColorGroupId();
        String codes = bracket ? user.chatSecondaryColor() : user.chatPrimaryColor();
        return from == null ? fixed : LegacyStyles.parse(codes, fixed);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
