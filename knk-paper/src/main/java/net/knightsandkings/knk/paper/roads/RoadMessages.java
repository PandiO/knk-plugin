package net.knightsandkings.knk.paper.roads;

import java.util.Locale;
import java.util.concurrent.CompletionException;

import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.roads.RoadApiError;
import net.knightsandkings.knk.core.exception.ApiException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Chat pieces of the road admin tools (road navigation plan §2 R26: the {@code SiegeMessages.command}
 * pattern copied into a feature-local class - clickable commands, a prefix, a few colours). No Bukkit
 * imports: Adventure only, so the formatting is unit-testable.
 */
public final class RoadMessages {
    public static final NamedTextColor INFO = NamedTextColor.GRAY;
    public static final NamedTextColor GOOD = NamedTextColor.GREEN;
    public static final NamedTextColor BAD = NamedTextColor.RED;
    public static final NamedTextColor WARN = NamedTextColor.YELLOW;
    public static final NamedTextColor HIGHLIGHT = NamedTextColor.GOLD;
    public static final NamedTextColor VALUE = NamedTextColor.WHITE;

    private static final Component PREFIX = Component.text("[Road] ", NamedTextColor.DARK_AQUA);

    private RoadMessages() {
    }

    public static Component prefixed(Component body) {
        return PREFIX.append(body);
    }

    public static Component info(String text) {
        return prefixed(Component.text(text, INFO));
    }

    public static Component good(String text) {
        return prefixed(Component.text(text, GOOD));
    }

    public static Component bad(String text) {
        return prefixed(Component.text(text, BAD));
    }

    public static Component warn(String text) {
        return prefixed(Component.text(text, WARN));
    }

    public static Component usage(String usage) {
        return prefixed(Component.text("Usage: " + usage, WARN));
    }

    /** {@code label} in white after a grey {@code key: }. */
    public static Component field(String key, String value) {
        return Component.text(key + ": ", INFO).append(Component.text(value, VALUE));
    }

    /** A clickable {@code /command} in gold (runs on click). */
    public static Component command(String command) {
        return command(command, command);
    }

    /** Clickable text that runs {@code command}. */
    public static Component command(String label, String command) {
        return Component.text(label, HIGHLIGHT)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to run " + command, INFO)));
    }

    /** Clickable text that puts {@code command} into the chat box for editing. */
    public static Component suggest(String label, String command) {
        return Component.text(label, HIGHLIGHT)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to edit: " + command, INFO)));
    }

    /** {@code [x y z]} that teleports the clicking admin there ({@code /knk road goto}). */
    public static Component teleport(int x, int y, int z) {
        String label = "[" + x + " " + y + " " + z + "]";
        return Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/knk road goto " + x + " " + y + " " + z))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport", INFO)));
    }

    /** "12.3 m", "1.2 km". */
    public static String distance(double blocks) {
        if (blocks >= 1000) {
            return String.format(Locale.ROOT, "%.1f km", blocks / 1000);
        }
        return String.format(Locale.ROOT, "%.0f m", blocks);
    }

    public static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    /**
     * The API's own message for a refused call (Phase 2e: {@code CompletionException} → {@code RuntimeException}
     * → {@code ApiException} with an {@code {error, message}} body), else the exception's message.
     */
    public static String describeError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ApiException api) {
                String fromBody = RoadMapper.error(api).map(RoadApiError::message).filter(m -> !m.isBlank()).orElse(null);
                if (fromBody != null) {
                    return fromBody;
                }
                if (api.getStatusCode() == 404) {
                    return "not found";
                }
                return api.getMessage();
            }
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
                continue;
            }
            cause = cause.getCause();
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** True when the failure is the API's 404 (e.g. a never-built tile). */
    public static boolean isNotFound(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ApiException api && api.getStatusCode() == 404) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
