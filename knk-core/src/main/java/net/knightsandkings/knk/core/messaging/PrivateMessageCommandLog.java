package net.knightsandkings.knk.core.messaging;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises Paper's command-log line for a private message (DESIGN.md §5 Q3, developer decision
 * 2026-09-26): with the server-side PM log (30-day retention) in place, {@code logs/latest.log}
 * must not keep a second, never-expiring copy of every /msg. The line looks like
 * <pre>Alice issued server command: /msg Bob hi</pre>
 * and is matched on its command label, with or without a namespace ({@code /minecraft:tell},
 * {@code /knightsandkings:msg}). Bukkit-free; knk-paper's Log4j filter calls it.
 */
public final class PrivateMessageCommandLog {

    static final String MARKER = " issued server command: /";

    /** /msg and /reply with their plugin.yml aliases, plus the vanilla commands they replace. */
    static final Set<String> LABELS = Set.of("msg", "message", "tell", "whisper", "w", "m", "pm", "reply", "r");

    private PrivateMessageCommandLog() {
    }

    public static boolean isPrivateMessageCommandLine(String line) {
        if (line == null) {
            return false;
        }
        int marker = line.indexOf(MARKER);
        if (marker < 0) {
            return false;
        }
        String command = line.substring(marker + MARKER.length());
        int space = command.indexOf(' ');
        String label = (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        return LABELS.contains(label);
    }
}
