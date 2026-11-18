package net.knightsandkings.knk.core.navigation.compass;

import java.util.List;

/**
 * Which entry a right-click on the compass selects next (KNG-234): the one after the current in the
 * list as it is now, wrapping round. The list can change between clicks (a siege objective comes under
 * attack and moves to the top), so the position is found again by key each time; a current entry that is
 * no longer listed restarts at the top.
 */
public final class CompassCycle {
    private CompassCycle() {
    }

    /** Index of the next entry in {@code keys}, or -1 when there is none. */
    public static int next(List<String> keys, String currentKey) {
        if (keys == null || keys.isEmpty()) {
            return -1;
        }
        int current = currentKey == null ? -1 : keys.indexOf(currentKey);
        return current < 0 ? 0 : (current + 1) % keys.size();
    }
}
