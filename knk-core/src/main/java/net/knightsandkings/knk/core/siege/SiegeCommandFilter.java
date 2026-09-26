package net.knightsandkings.knk.core.siege;

import java.util.List;
import java.util.Locale;

/**
 * The in-match command filter (DESIGN §6.9, fixes N10: v2 declared the list but never enforced it).
 * While a member is in HUB/IN_PROGRESS only {@code SiegeConfiguration.AllowedCommands} run.
 * <ul>
 *   <li>Matching is on the command label only, case-insensitive: {@code /msg} allows
 *       {@code /msg Bob hi}, not {@code /msgall}.</li>
 *   <li>A namespace is ignored on both sides ({@code /knightsandkings:siege} = {@code /siege}), so the
 *       namespaced form can't be used to get around the list.</li>
 *   <li>{@code /siege} is always allowed, even if the list forgets it: a member must be able to
 *       leave, vote and pick a spawn. So are its menu shortcuts {@code /siegemenu} and {@code /sgm}.</li>
 * </ul>
 * Aliases aren't resolved: list every spelling you want to allow (e.g. {@code /r} and {@code /reply}).
 */
public final class SiegeCommandFilter {
    private SiegeCommandFilter() {}

    public static final String ALWAYS_ALLOWED = "siege";

    /** {@link #ALWAYS_ALLOWED} plus the {@code /siege menu} shortcuts (playtest 2026-09-26). */
    public static final java.util.Set<String> ALWAYS_ALLOWED_LABELS = java.util.Set.of(ALWAYS_ALLOWED, "siegemenu", "sgm");

    /** @param message the raw command message, e.g. {@code "/msg Bob hi"} */
    public static boolean isAllowed(String message, List<String> allowedCommands) {
        String label = label(message);
        if (label.isEmpty()) return true;
        if (ALWAYS_ALLOWED_LABELS.contains(label)) return true;
        if (allowedCommands == null) return false;
        for (String allowed : allowedCommands) {
            if (label.equals(label(allowed))) return true;
        }
        return false;
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
