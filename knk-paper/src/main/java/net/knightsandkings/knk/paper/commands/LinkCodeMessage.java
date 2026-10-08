package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats {@code messages.link-code-generated} (the reply to {@code /account link} that tells the player their
 * code and where to enter it) and {@code messages.link-code-entered-in-game}.
 *
 * <p>Placeholders: {@code {code}}, {@code {minutes}} and {@code {url}} ({@code web.public-url}, no trailing
 * slash). The template may span several lines ({@code \n}); each line is sent as its own chat line. When no
 * public URL is configured, every line that uses {@code {url}} is left out, so a template like the default still
 * reads well without a link.
 */
public final class LinkCodeMessage {
    public static final String DEFAULT_TEMPLATE =
        "&aYour link code is &6{code}&a. It expires in {minutes} minutes.\n"
            + "&aNew to the website? Register at &e{url}/auth/register\n"
            + "&aEnter the code when you register on the website, or on your Account page if you already have a"
            + " web login.";

    /** {@code messages.link-code-entered-in-game}: the reply to {@code /account link <code>}. */
    public static final String DEFAULT_ENTERED_IN_GAME_TEMPLATE =
        "&eLink codes are entered on the website, not in game.\n"
            + "&eType &6/account link &e(without a code) to get yours.\n"
            + "&eThen enter it at &6{url}/auth/register&e, or on your Account page if you already have a web login.";

    private LinkCodeMessage() {
    }

    public static List<String> format(String template, String code, int minutes, String publicUrl) {
        String url = publicUrl == null ? "" : publicUrl.trim();
        List<String> lines = new ArrayList<>();
        for (String line : template.split("\\R|\\\\n")) {
            if (url.isEmpty() && line.contains("{url}")) {
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            lines.add(line
                .replace("{code}", code)
                .replace("{minutes}", String.valueOf(minutes))
                .replace("{url}", url));
        }
        return lines;
    }
}
