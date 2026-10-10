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
