package net.knightsandkings.knk.core.navigation.destinations;

import java.util.Objects;

/**
 * Why a list is empty or a session ends (destination engine DESIGN § 3, E15): a stable {@code code} and a default
 * {@code text}. The default text is today's {@code /navigate} wording; a client may show its own wording for a code
 * (the compass says "The navigation compass works once the siege match has started." for {@code siege-lobby} where
 * the command says "You can't navigate while you are in a siege."). Clients switch on the code, never on the text.
 * The codes of the initial scope table are in {@link InitialScopes}; the engine's own code is
 * {@link #NO_LONGER_LISTED}.
 *
 * @param code stable, lower-case words joined by hyphens ({@code siege-lobby}); never shown to players
 * @param text the default player-facing text
 */
public record Reason(String code, String text) {

    /** A running session's destination dropped out of a list that still lists others (§ 8). */
    public static final String NO_LONGER_LISTED = "no-longer-listed";

    public Reason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
        if (!code.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("a reason code is lower-case words joined by hyphens: " + code);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("a reason needs a default text (" + code + ")");
        }
    }

    public static Reason of(String code, String text) {
        return new Reason(code, text);
    }

    /** Whether this reason has {@code code}. */
    public boolean is(String code) {
        return this.code.equals(code);
    }

    @Override
    public String toString() {
        return code + ": " + text;
    }
}
