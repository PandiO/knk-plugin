package net.knightsandkings.knk.paper.admission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Fills the {@code admission.*} templates: {@code &} colour codes, {@code \n} (or a real line break) starts a new
 * line, {@code {name}} placeholders. {@code {url}} without a configured link page reads "the website".
 */
public final class AdmissionMessages {
    /** Stands in for {@code {url}} when neither {@code admission.link-url} nor {@code web.public-url} is set. */
    public static final String NO_URL = "the website";

    private AdmissionMessages() {
    }

    public static List<String> lines(String template, Map<String, String> placeholders) {
        List<String> lines = new ArrayList<>();
        for (String line : template.split("\\R|\\\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String filled = line;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                filled = filled.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
            lines.add(filled);
        }
        return lines;
    }

    /** One component, lines joined with newlines: for a disconnect screen. */
    public static Component screen(String template, Map<String, String> placeholders) {
        Component screen = Component.empty();
        boolean first = true;
        for (String line : lines(template, placeholders)) {
            if (!first) {
                screen = screen.append(Component.newline());
            }
            screen = screen.append(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            first = false;
        }
        return screen;
    }

    /** {@code url} for the {@code {url}} placeholder. */
    public static String url(String linkUrl) {
        return linkUrl == null || linkUrl.isBlank() ? NO_URL : linkUrl;
    }
}
