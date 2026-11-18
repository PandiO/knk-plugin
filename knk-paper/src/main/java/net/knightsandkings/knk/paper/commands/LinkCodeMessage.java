package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Formats {@code messages.link-code-generated} (the reply to {@code /account link} that tells the player their
 * code and where to enter it) and {@code messages.link-code-entered-in-game}.
 *
 * <p>Placeholders: {@code {code}}, {@code {minutes}} and {@code {url}} ({@code web.public-url}, no trailing
 * slash). The template may span several lines ({@code \n}); each line is sent as its own chat line. When no
 * public URL is configured, every line that uses {@code {url}} is left out, so a template like the default still
 * reads well without a link. A template without {@code {url}} (a config.yml from before the web registration
 * flow) gets {@link #REGISTER_LINE} appended, so players always learn where to use the code.
 *
 * <p>{@link #toComponent} makes the line with the code click-to-copy and a line with the website click-to-open.
 */
public final class LinkCodeMessage {
    public static final String DEFAULT_TEMPLATE =
        "&aYour link code is &6{code}&a. It expires in {minutes} minutes.\n"
            + "&aNew to the website? Register at &e{url}/auth/register\n"
            + "&aEnter the code when you register on the website, or on your Account page if you already have a"
            + " web login.\n"
            + "&cNever give this code to anyone; staff never ask for it.";

    /** {@code messages.link-code-entered-in-game}: the reply to {@code /account link <code>}. */
    public static final String DEFAULT_ENTERED_IN_GAME_TEMPLATE =
        "&eLink codes are entered on the website, not in game.\n"
            + "&eType &6/account link &e(without a code) to get yours.\n"
            + "&eThen enter it at &6{url}/auth/register&e, or on your Account page if you already have a web login.";

    /** Appended to a {@code link-code-generated} template that never mentions {@code {url}}. */
    public static final String REGISTER_LINE = "&aNew to the website? Register at &e{url}/auth/register";

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private LinkCodeMessage() {
    }

    /** {@link #format}, plus {@link #REGISTER_LINE} when the template has no {@code {url}} and a URL is known. */
    public static List<String> formatGenerated(String template, String code, int minutes, String publicUrl) {
        boolean hasUrl = publicUrl != null && !publicUrl.isBlank();
        String effective = hasUrl && !template.contains("{url}") ? template + "\n" + REGISTER_LINE : template;
        return format(effective, code, minutes, publicUrl);
    }

    /**
     * One chat line ({@code &} colour codes) as a component: the line showing {@code code} copies it to the
     * clipboard when clicked, a line with a web address opens it. Other lines are plain.
     */
    public static Component toComponent(String legacyLine, String code) {
        Component line = LegacyComponentSerializer.legacyAmpersand().deserialize(legacyLine);
        if (code != null && !code.isBlank() && legacyLine.contains(code)) {
            return line.clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy the code")));
        }
        Matcher url = URL.matcher(legacyLine.replaceAll("&[0-9a-fk-orA-FK-OR]", ""));
        if (url.find()) {
            return line.clickEvent(ClickEvent.openUrl(url.group()))
                .hoverEvent(HoverEvent.showText(Component.text("Open " + url.group())));
        }
        return line;
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
