package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode;

/**
 * Whether a match's players get the navigation compass at the hub (KNG-234). Three levels, most specific
 * first: the <b>lobby</b>, then the <b>scenario</b> being played, then the <b>global</b> siege configuration.
 * A level that says {@link SiegeNavigationItemMode#INHERIT} defers to the next; the global level is a plain
 * on/off and so always decides.
 */
public final class SiegeNavigationItem {
    private SiegeNavigationItem() {
    }

    public static boolean resolve(SiegeNavigationItemMode lobby, SiegeNavigationItemMode scenario, boolean global) {
        SiegeNavigationItemMode decided = decide(lobby);
        if (decided == SiegeNavigationItemMode.INHERIT) {
            decided = decide(scenario);
        }
        return switch (decided) {
            case GIVE -> true;
            case DO_NOT_GIVE -> false;
            case INHERIT -> global;
        };
    }

    private static SiegeNavigationItemMode decide(SiegeNavigationItemMode mode) {
        return mode == null ? SiegeNavigationItemMode.INHERIT : mode;
    }
}
