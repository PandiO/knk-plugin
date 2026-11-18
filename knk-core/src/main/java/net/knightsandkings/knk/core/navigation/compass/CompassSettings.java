package net.knightsandkings.knk.core.navigation.compass;

import java.util.List;

/**
 * The navigation compass settings from the web app's navigation page (KNG-234): which item is the compass,
 * where it works, and what it cycles through outside a siege.
 *
 * @param item           which item is the compass
 * @param siegeEnabled   the compass works in a running siege match
 * @param generalEnabled the compass works outside a siege, cycling {@code generalTargets}
 * @param generalTargets destinations outside a siege, one {@code /navigate} argument each
 *                       ({@code town:Kardenna}, {@code Market Square spawn}); in cycle order
 */
public record CompassSettings(CompassItemRule item, boolean siegeEnabled, boolean generalEnabled, List<String> generalTargets) {

    public CompassSettings {
        item = item == null ? CompassItemRule.defaults() : item;
        generalTargets = generalTargets == null ? List.of() : generalTargets.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    /** What applies before the API answered (or when it never does): the default item, siege on, nothing outside. */
    public static CompassSettings defaults() {
        return new CompassSettings(CompassItemRule.defaults(), true, false, List.of());
    }

    /** The multi-line form the web app edits: one destination per line, blank lines and {@code #} comments skipped. */
    public static List<String> parseTargets(String text) {
        if (text == null) {
            return List.of();
        }
        return text.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
