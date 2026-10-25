package net.knightsandkings.knk.core.regions;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a region rename ({@code POST /Regions/rename}) does in one world (KNG-103). A world task draws a region as
 * {@code tempregion_worldtask_<n>}; the API then renames it to the domain's final name {@code domain_<id>}. When the
 * region step of an existing domain is run again, the domain's previous region usually already holds that name, so the
 * API also names the region the new one replaces ({@code replaceRegionId}, the domain's WgRegionId before the save).
 *
 * <p>The API decides <em>which</em> region the domain had; the plugin only removes it when that is safe:
 * <ul>
 *   <li>The replaced region must be the target name itself (the domain's own {@code domain_<id>}) or a temp-named region
 *       ({@link TempRegionCleanupPolicy#isTemporary}). Anything else (a v1 region chosen with "select", another domain's
 *       {@code domain_<n>}) is never removed: the rename goes ahead and that region is left alone.</li>
 *   <li>An existing target that the API did not name as replaced is still refused (a legacy region that happens to carry
 *       the name, managed-worldguard-regions.md §8 item 9).</li>
 * </ul>
 * WorldGuard ids are case-insensitive, so names are compared ignoring case.
 */
public final class RegionRenamePlan {

    // KNG-255: a domain sub-region's final name, domain_<domainId>_sub_<id>, follows the same rules; so does an arena
    // area region's, arena_<arenaId>_<kind>_<id> (arena duels rev. 6).
    private static final Pattern FINAL_NAME = Pattern.compile("domain_\\d+(_sub_\\d+)?|arena_\\d+_(battleground|spectator|lobby)_\\d+");

    public enum Action {
        /** The region already has the final name: nothing to do (success). */
        ALREADY_NAMED,
        /** Plain rename; nothing is removed. */
        RENAME,
        /** The target name exists and is the domain's previous region: redefine it with the new region's shape. */
        REPLACE_TARGET,
        /** Rename, then remove the domain's previous (temp-named) region. */
        RENAME_AND_REMOVE_PREVIOUS,
        /** Do nothing (failure); {@link Plan#note()} says why. */
        REFUSE
    }

    /**
     * @param regionToRemove for {@link Action#REPLACE_TARGET} the target, for {@link Action#RENAME_AND_REMOVE_PREVIOUS} the
     *                       previous region; otherwise null
     * @param note           why a rename was refused, or why a named previous region is kept; null when there is nothing
     *                       to say
     */
    public record Plan(Action action, String regionToRemove, String note) {
        public boolean succeeds() {
            return action != Action.REFUSE;
        }
    }

    private RegionRenamePlan() {
    }

    /** Whether a region id is the final name of some domain ({@code domain_<id>}), domain sub-region or arena area region. */
    public static boolean isFinalName(String regionId) {
        return regionId != null && FINAL_NAME.matcher(regionId.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * @param oldRegionId     the region being renamed (normally temp-named)
     * @param newRegionId     the final name
     * @param replaceRegionId the domain's previous region according to the API, or null/blank when it had none
     * @param oldExists       whether {@code oldRegionId} exists in the world
     * @param targetExists    whether {@code newRegionId} exists in the world
     * @param replaceExists   whether {@code replaceRegionId} exists in the world (ignored when no replace was named)
     */
    public static Plan decide(String oldRegionId, String newRegionId, String replaceRegionId,
                              boolean oldExists, boolean targetExists, boolean replaceExists) {
        if (same(oldRegionId, newRegionId)) {
            return new Plan(Action.ALREADY_NAMED, null, null);
        }
        if (!oldExists) {
            return refuse("region not found: " + oldRegionId);
        }
        String replace = replaceRegionId == null || replaceRegionId.isBlank() ? null : replaceRegionId.trim();
        if (replace == null || same(replace, oldRegionId)) {
            return targetExists ? refuse("target region name already exists: " + newRegionId)
                    : new Plan(Action.RENAME, null, null);
        }
        if (same(replace, newRegionId)) {
            return targetExists ? new Plan(Action.REPLACE_TARGET, newRegionId, null)
                    : new Plan(Action.RENAME, null, "previous region " + replace + " no longer exists");
        }
        if (targetExists) {
            // The domain's previous region is something else, so whatever holds the final name isn't the domain's.
            return refuse("target region name already exists: " + newRegionId + " (and is not the region being replaced, "
                    + replace + ")");
        }
        if (!TempRegionCleanupPolicy.isTemporary(replace)) {
            return new Plan(Action.RENAME, null, "previous region " + replace
                    + " is kept: only the domain's own " + newRegionId + " or a temp-named region is removed");
        }
        return replaceExists ? new Plan(Action.RENAME_AND_REMOVE_PREVIOUS, replace, null)
                : new Plan(Action.RENAME, null, "previous region " + replace + " no longer exists");
    }

    private static Plan refuse(String note) {
        return new Plan(Action.REFUSE, null, note);
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
