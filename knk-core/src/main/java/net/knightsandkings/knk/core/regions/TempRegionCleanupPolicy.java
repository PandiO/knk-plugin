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

    /**
     * Arena duels rev. 6: the same checks as {@link #decideOrphanedSubRegion} for an arena area region
     * ({@code arena_<a>_<kind>_<n>}, {@link ArenaAreaRegionNames}) that no arena lists any more; the fresh lookup asks the
     * arenas ({@code GET /Arenas/areas/by-region}).
     */
    public static Decision decideOrphanedArenaAreaRegion(String regionId, String orphanedSince, long cutoffMillis,
                                                         Predicate<String> listedAreaRegion, Function<String, Usage> freshUsage) {
        if (!ArenaAreaRegionNames.isAreaRegionName(regionId)) {
            return Decision.NOT_TEMPORARY;
        }
        if (listedAreaRegion.test(regionId)) {
            return Decision.KEEP_KNOWN_DOMAIN_REGION;
        }
        return decideByAge(regionId, orphanedSince, cutoffMillis, freshUsage);
    }

    /** Usage by either lookup: in use when one says so, unused only when both say so, otherwise unknown. */
    public static Function<String, Usage> either(Function<String, Usage> first, Function<String, Usage> second) {
        return regionId -> {
            Usage a = first.apply(regionId);
            if (a == Usage.IN_USE) {
                return Usage.IN_USE;
            }
            Usage b = second.apply(regionId);
            if (b == Usage.IN_USE) {
                return Usage.IN_USE;
            }
            return a == Usage.UNUSED && b == Usage.UNUSED ? Usage.UNUSED : Usage.UNKNOWN;
        };
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
