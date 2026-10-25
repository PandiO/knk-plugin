package net.knightsandkings.knk.core.regions;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The final name of an arena area's WorldGuard region (arena duels DESIGN.md §4.1, rev. 6):
 * {@code arena_<arenaId>_<kind>_<regionId>} with kind {@code battleground}, {@code spectator} or {@code lobby}. The API
 * renames a world task's region to it on save (KNG-103 rename and replace); a region with this name that no arena lists
 * any more is an orphan, handled like a KNG-253 orphaned sub-region ({@link OrphanedSubRegions}). v1's
 * {@code arena_2,<part>-battleground} regions don't match.
 */
public final class ArenaAreaRegionNames {

    private static final Pattern NAME = Pattern.compile("arena_\\d+_(battleground|spectator|lobby)_\\d+");

    private ArenaAreaRegionNames() {
    }

    /** Whether {@code regionId} is an arena area region's final name (any case). */
    public static boolean isAreaRegionName(String regionId) {
        return regionId != null && NAME.matcher(regionId.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
