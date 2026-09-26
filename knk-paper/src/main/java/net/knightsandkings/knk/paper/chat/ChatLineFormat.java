package net.knightsandkings.knk.paper.chat;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.knightsandkings.knk.paper.utils.NamedColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds a player's public chat line — v1's {@code <rank-tag>-{<Title>}- <Username>: <message>}
 * format (KNG-8), colored per premium tier (KNG-7). Kept free of Bukkit types so it is unit
 * testable; PlayerListener.onChat decides the {@link Rank} and supplies the cached summary.
 * <ul>
 *   <li>Owner/staff: {@code [OWNER]-{Title}- Name: msg} in the fixed ColorOptions owner/staff
 *   colors, title bold (as v1).</li>
 *   <li>Everyone else: {@code -{Noble Title}- Name: msg} — the premium tier name, if any, goes
 *   inside the braces before the title. Brackets use the summary's chatSecondaryColor, title and
 *   name its chatPrimaryColor (the API fills both from the tier, else the Default group), with
 *   ColorOptions.defaultformat/defaultsubjects as the fallback. Not bold (as v1).</li>
 * </ul>
 * The message itself inherits the name's color, as it did in v1. The {@code -{…}-} segment is
 * left out when there is nothing to put in it (e.g. summary not cached yet).
 */
public final class ChatLineFormat {
    private ChatLineFormat() {}

    public enum Rank { OWNER, STAFF, MEMBER }

    public static Component render(Rank rank, String playerName, UserSummary user, Component message) {
        String title = user != null ? blankToNull(user.titleName()) : null;

        String tag;
        NamedTextColor bracketColor;
        NamedTextColor textColor;
        boolean boldTitle;
        String braced;
        switch (rank) {
            case OWNER -> {
                tag = "OWNER";
                bracketColor = ColorOptions.ownerformat;
                textColor = ColorOptions.ownersubjects;
                boldTitle = true;
                braced = title;
            }
            case STAFF -> {
                tag = "STAFF";
                bracketColor = ColorOptions.staffformat;
                textColor = ColorOptions.staffsubjects;
                boldTitle = true;
                braced = title;
            }
            default -> {
                tag = null;
                bracketColor = NamedColors.parse(user != null ? user.chatSecondaryColor() : null, ColorOptions.defaultformat);
                textColor = NamedColors.parse(user != null ? user.chatPrimaryColor() : null, ColorOptions.defaultsubjects);
                boldTitle = false;
                String tier = user != null ? blankToNull(user.premiumTierName()) : null;
                braced = tier == null ? title : (title == null ? tier : tier + " " + title);
            }
        }

        TextComponent.Builder line = Component.text();
        if (tag != null) {
            line.append(Component.text("[", bracketColor))
                .append(Component.text(tag, textColor))
                .append(Component.text("]", bracketColor));
        }
        if (braced != null) {
            Component bracedText = Component.text(braced, textColor);
            if (boldTitle) {
                bracedText = bracedText.decorate(TextDecoration.BOLD);
            }
            line.append(Component.text("-{", bracketColor))
                .append(bracedText)
                .append(Component.text("}-", bracketColor));
        }
        String namePrefix = (tag != null || braced != null) ? " " : "";
        line.append(Component.text(namePrefix + playerName + ": ", textColor).append(message));
        return line.build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
