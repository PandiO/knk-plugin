package net.knightsandkings.knk.core.regions;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides whether the temp-region cleanup may delete one WorldGuard region. A world task draws a region as
 * {@code tempregion_worldtask_<taskId>}; once the domain is saved the API renames it to {@code domain_<id>} (KNG-43), so a
 * region that is still temp-named after the retention period is normally an abandoned draft. It is only deleted when
 * every check says so:
 * <ol>
 *   <li>the name starts with {@link #TEMP_REGION_PREFIX};</li>
 *   <li>it is not a domain's region according to the last managed-region repair (cheap, no API call);</li>
 *   <li>it has a valid creation timestamp older than the cutoff (regions without one are never deleted);</li>
 *   <li>a fresh API lookup, made right before deleting, finds no domain using it. If the lookup fails the region is
 *       kept: a domain whose rename failed lives in a temp-named region, and an unreachable API must not cost it.</li>
 * </ol>
 * PandiO/knk-workspace#34: lootbox spawn areas draw their regions through the same task and are renamed to
 * {@code lootbox_area_<id>}; the fresh lookup asks them too ({@link #inUseByAny}), so an area whose rename failed keeps
 * its region like a domain does.
 * <p>
 * PandiO/knk-workspace#269: a region the API released (its draft was deleted, its task closed without a result, or its
 * session was swept) skips only the age check: {@link #decideRelease}.
 */
public final class TempRegionCleanupPolicy {

    public static final String TEMP_REGION_PREFIX = "tempregion_worldtask_";

    /** What the fresh API lookup said about a region. */
    public enum Usage { IN_USE, UNUSED, UNKNOWN }

    public enum Decision {
        DELETE(true),
        NOT_TEMPORARY(false),
        KEEP_KNOWN_DOMAIN_REGION(false),
        KEEP_NO_TIMESTAMP(false),
        KEEP_INVALID_TIMESTAMP(false),
        KEEP_TOO_RECENT(false),
        KEEP_IN_USE(false),
        KEEP_USAGE_UNKNOWN(false);

        private final boolean delete;

        Decision(boolean delete) {
            this.delete = delete;
        }

        public boolean delete() {
            return delete;
        }
    }

    private TempRegionCleanupPolicy() {
    }

    /**
     * #269: what to do with a region the API released. {@link #settles()} says whether the API is told (it then deletes the
     * release); the others are asked again on the next pass.
     */
    public enum ReleaseDecision {
        /** Every check passed: remove it, then settle as removed. */
        REMOVE(true),
        /** No such region in any loaded world (removed or renamed already, or never drawn here). */
        MISSING(true),
        /** Not the release's own {@code tempregion_worldtask_<taskId>} name: never removed. */
        KEEP_NOT_ITS_TEMP_REGION(true),
        /** The id is in more than one world: not sure which one the task drew. */
        KEEP_IN_SEVERAL_WORLDS(true),
        /** No (valid) creation timestamp: not drawn by the region task, so never removed. */
        KEEP_NO_TIMESTAMP(true),
        /** A domain or lootbox area uses it according to a fresh API lookup. */
        KEEP_IN_USE(true),
        /** A player on this server is still running the task. */
        WAIT_TASK_ACTIVE(false),
        /** The last managed-region repair saw a domain use it, or hasn't run yet. */
        WAIT_KNOWN_DOMAIN_REGION(false),
        /** The fresh lookup could not ask the API. */
        WAIT_USAGE_UNKNOWN(false);

        private final boolean settles;

        ReleaseDecision(boolean settles) {
            this.settles = settles;
        }

        public boolean settles() {
            return settles;
        }

        public boolean remove() {
            return this == REMOVE;
        }
    }

    /**
     * #269: the checks for a released region, in order; like {@link #decide} without the age check, and nothing is removed
     * while unsure.
     *
     * @param release           the API's release
     * @param worldsHoldingIt   how many loaded worlds have a region with that id
     * @param creationTimestamp its {@code knk-creation-timestamp} flag (in the one world holding it), or null
     * @param taskActiveHere    whether a player on this server is still running the release's task
     * @param knownDomainRegion as for {@link #decide}
     * @param freshUsage        as for {@link #decide}; only called when every other check allows removing it
     */
    public static ReleaseDecision decideRelease(TempRegionRelease release, int worldsHoldingIt, String creationTimestamp,
                                                boolean taskActiveHere, Predicate<String> knownDomainRegion,
                                                Function<String, Usage> freshUsage) {
        if (release == null || !release.namesItsOwnTaskRegion() || !isTemporary(release.regionId().trim())) {
            return ReleaseDecision.KEEP_NOT_ITS_TEMP_REGION;
        }
        String regionId = release.regionId().trim();
        if (worldsHoldingIt <= 0) {
            return ReleaseDecision.MISSING;
        }
        if (worldsHoldingIt > 1) {
            return ReleaseDecision.KEEP_IN_SEVERAL_WORLDS;
        }
        if (taskActiveHere) {
            return ReleaseDecision.WAIT_TASK_ACTIVE;
        }
        if (knownDomainRegion.test(regionId)) {
            return ReleaseDecision.WAIT_KNOWN_DOMAIN_REGION;
        }
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            return ReleaseDecision.KEEP_NO_TIMESTAMP;
        }
        try {
            Long.parseLong(creationTimestamp.trim());
        } catch (NumberFormatException e) {
            return ReleaseDecision.KEEP_NO_TIMESTAMP;
        }
        Usage usage;
        try {
            usage = freshUsage.apply(regionId);
        } catch (RuntimeException e) {
            usage = Usage.UNKNOWN;
        }
        return switch (usage == null ? Usage.UNKNOWN : usage) {
            case IN_USE -> ReleaseDecision.KEEP_IN_USE;
            case UNKNOWN -> ReleaseDecision.WAIT_USAGE_UNKNOWN;
            case UNUSED -> ReleaseDecision.REMOVE;
        };
    }

    /**
     * #34: one fresh lookup over several owners (domains, lootbox spawn areas): {@link Usage#IN_USE} as soon as one uses
     * the region, {@link Usage#UNUSED} only when every one answered unused, else {@link Usage#UNKNOWN} (an owner that
     * could not be asked, or threw, keeps the region). Owners after the first one that uses it are not asked.
     */
    @SafeVarargs
    public static Function<String, Usage> inUseByAny(Function<String, Usage>... owners) {
        return regionId -> {
            boolean allUnused = true;
            for (Function<String, Usage> owner : owners) {
                Usage usage;
                try {
                    usage = owner == null ? Usage.UNKNOWN : owner.apply(regionId);
                } catch (RuntimeException e) {
                    usage = Usage.UNKNOWN;
                }
                if (usage == Usage.IN_USE) {
                    return Usage.IN_USE;
                }
                if (usage != Usage.UNUSED) {
                    allUnused = false;
                }
            }
            return allUnused ? Usage.UNUSED : Usage.UNKNOWN;
        };
    }

    /**
     * KNG-258: the same checks for a domain sub-region ({@code domain_<d>_sub_<s>}) that no domain lists any more
     * ({@link OrphanedSubRegions}). The retention period counts from when it was orphaned ({@code knk-orphaned-since}),
     * not from its creation: a sub-region removed today was often drawn long ago.
     * <ol>
     *   <li>the name is a sub-region's, else {@link Decision#NOT_TEMPORARY};</li>
     *   <li>the last access sync listed it (or hasn't run): {@link Decision#KEEP_KNOWN_DOMAIN_REGION};</li>
     *   <li>it was never marked orphaned: {@link Decision#KEEP_NO_TIMESTAMP} (the next sync marks it);</li>
     *   <li>marked before the cutoff, and a fresh API lookup finds no domain using it.</li>
     * </ol>
     */
    public static Decision decideOrphanedSubRegion(String regionId, String orphanedSince, long cutoffMillis,
                                                   Predicate<String> listedSubRegion, Function<String, Usage> freshUsage) {
        if (!SubRegionMirror.isSubRegionName(regionId)) {
            return Decision.NOT_TEMPORARY;
        }
        if (listedSubRegion.test(regionId)) {
            return Decision.KEEP_KNOWN_DOMAIN_REGION;
        }
        return decideByAge(regionId, orphanedSince, cutoffMillis, freshUsage);
    }

    public static boolean isTemporary(String regionId) {
        return regionId != null && regionId.toLowerCase(Locale.ROOT).startsWith(TEMP_REGION_PREFIX);
    }

    /**
     * @param creationTimestamp   the region's {@code knk-creation-timestamp} flag (epoch millis as text), or null
     * @param cutoffMillis        regions created before this moment are old enough to delete
     * @param knownDomainRegion   whether the last repair saw a domain using the region (or has not run yet)
     * @param freshUsage          looks the region up in the API now; only called when every other check allows deletion
     */
    public static Decision decide(String regionId, String creationTimestamp, long cutoffMillis,
                                  Predicate<String> knownDomainRegion, Function<String, Usage> freshUsage) {
        if (!isTemporary(regionId)) {
            return Decision.NOT_TEMPORARY;
        }
        if (knownDomainRegion.test(regionId)) {
            return Decision.KEEP_KNOWN_DOMAIN_REGION;
        }
        return decideByAge(regionId, creationTimestamp, cutoffMillis, freshUsage);
    }

    private static Decision decideByAge(String regionId, String creationTimestamp, long cutoffMillis,
                                        Function<String, Usage> freshUsage) {
        if (creationTimestamp == null || creationTimestamp.isBlank()) {
            return Decision.KEEP_NO_TIMESTAMP;
        }
        long createdAt;
        try {
            createdAt = Long.parseLong(creationTimestamp.trim());
        } catch (NumberFormatException e) {
            return Decision.KEEP_INVALID_TIMESTAMP;
        }
        if (createdAt >= cutoffMillis) {
            return Decision.KEEP_TOO_RECENT;
        }
        Usage usage;
        try {
            usage = freshUsage.apply(regionId);
        } catch (RuntimeException e) {
            usage = Usage.UNKNOWN;
        }
        return switch (usage == null ? Usage.UNKNOWN : usage) {
            case IN_USE -> Decision.KEEP_IN_USE;
            case UNKNOWN -> Decision.KEEP_USAGE_UNKNOWN;
            case UNUSED -> Decision.DELETE;
        };
    }
}
