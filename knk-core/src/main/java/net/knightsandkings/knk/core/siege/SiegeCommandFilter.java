package net.knightsandkings.knk.core.siege;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The in-match command filter (DESIGN §6.9, fixes N10: v2 declared the list but never enforced it).
 * While a member is in HUB/IN_PROGRESS only {@code SiegeConfiguration.AllowedCommands} run.
 * <ul>
 *   <li>Matching is on the command label only, case-insensitive: {@code /msg} allows
 *       {@code /msg Bob hi}, not {@code /msgall}.</li>
 *   <li>A namespace is ignored on both sides ({@code /knightsandkings:siege} = {@code /siege}), so the
 *       namespaced form can't be used to get around the list.</li>
 *   <li>Aliases are resolved on both sides (private-messages DESIGN §4 D9): with a resolver that
 *       maps a label to its command's primary name, listing {@code /msg} allows {@code /tell},
 *       {@code /w}, {@code /minecraft:tell} …, and listing {@code /r} allows {@code /reply}. The
 *       plugin passes the server's command map ({@code SiegeCommandAliases}).</li>
 *   <li>{@code /siege} is always allowed, even if the list forgets it: a member must be able to
 *       leave, vote and pick a spawn. So are its menu shortcuts {@code /siegemenu} and {@code /sgm}.</li>
 * </ul>
 */
public final class SiegeCommandFilter {
    private SiegeCommandFilter() {}

    public static final String ALWAYS_ALLOWED = "siege";

    /** {@link #ALWAYS_ALLOWED} plus the {@code /siege menu} shortcuts (playtest 2026-09-26). */
    public static final java.util.Set<String> ALWAYS_ALLOWED_LABELS = java.util.Set.of(ALWAYS_ALLOWED, "siegemenu", "sgm");

    /** Without alias resolution: only the exact labels on the list (namespace still ignored). */
    public static boolean isAllowed(String message, List<String> allowedCommands) {
        return isAllowed(message, allowedCommands, UnaryOperator.identity());
    }

    /**
     * @param message the raw command message, e.g. {@code "/msg Bob hi"}
     * @param canonical maps a {@link #label} to the primary name of the command it runs (lowercase,
     *        no namespace), or returns it unchanged when no such command is registered
     */
    public static boolean isAllowed(String message, List<String> allowedCommands, UnaryOperator<String> canonical) {
        String label = label(message);
        if (label.isEmpty()) return true;
        String command = canonicalOf(label, canonical);
        if (ALWAYS_ALLOWED_LABELS.contains(label) || ALWAYS_ALLOWED_LABELS.contains(command)) return true;
        if (allowedCommands == null) return false;
        for (String allowed : allowedCommands) {
            String allowedLabel = label(allowed);
            if (allowedLabel.isEmpty()) continue;
            if (label.equals(allowedLabel) || command.equals(canonicalOf(allowedLabel, canonical))) return true;
        }
        return false;
    }

    /** The resolver's answer, or the label itself when it has none. */
    private static String canonicalOf(String label, UnaryOperator<String> canonical) {
        String resolved = canonical == null ? null : canonical.apply(label);
        resolved = label(resolved);
        return resolved.isEmpty() ? label : resolved;
    }

    /** The lowercase command label without slash or namespace: {@code "/KnK:Siege join"} → {@code "siege"}. */
    public static String label(String command) {
        if (command == null) return "";
        String s = command.trim();
        while (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
