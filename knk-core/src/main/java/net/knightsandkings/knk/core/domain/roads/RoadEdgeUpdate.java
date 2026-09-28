package net.knightsandkings.knk.core.domain.roads;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The body of {@code PUT api/road-edges/{id}} (DESIGN §7 review). Every field is optional:
 * {@code null} leaves that attribute as it is; the {@code clear…} flags distinguish "clear" from
 * "leave" (Phase 1 decision 11). Mirrors the web-api's {@code RoadEdgeUpdateDto}. Bukkit-free.
 *
 * @param streetId       label the edge with this street, or {@code null}
 * @param clearStreet    remove the street label
 * @param propagate      also label the edges that continue along the road (Phase 1 decision 6:
 *                       they become {@code Manual})
 * @param profileId      assign this profile, or {@code null}
 * @param clearProfile   remove the profile
 * @param costMultiplier new routing cost multiplier ({@code > 0}), or {@code null}
 * @param flags          replace the flag set (an empty set clears every flag), or {@code null}
 */
public record RoadEdgeUpdate(Integer streetId, boolean clearStreet, boolean propagate, Integer profileId,
                             boolean clearProfile, Double costMultiplier, Set<RoadEdgeFlag> flags) {
    public RoadEdgeUpdate {
        if (clearStreet && streetId != null) {
            throw new IllegalArgumentException("clearStreet and streetId are exclusive");
        }
        if (clearProfile && profileId != null) {
            throw new IllegalArgumentException("clearProfile and profileId are exclusive");
        }
        if (costMultiplier != null && !(costMultiplier > 0)) {
            throw new IllegalArgumentException("costMultiplier must be > 0");
        }
        if (flags != null) {
            flags = flags.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(flags));
        }
    }

    /** Label the edge (and, with {@code propagate}, the road it continues along) with a street. */
    public static RoadEdgeUpdate street(int streetId, boolean propagate) {
        return new RoadEdgeUpdate(streetId, false, propagate, null, false, null, null);
    }

    /** Remove the edge's street label. */
    public static RoadEdgeUpdate unlabelled() {
        return new RoadEdgeUpdate(null, true, false, null, false, null, null);
    }

    /** Assign a profile. */
    public static RoadEdgeUpdate profile(int profileId) {
        return new RoadEdgeUpdate(null, false, false, profileId, false, null, null);
    }

    /** Replace the edge's flags. */
    public static RoadEdgeUpdate flags(Set<RoadEdgeFlag> flags) {
        return new RoadEdgeUpdate(null, false, false, null, false, null, flags);
    }

    /** Change the routing cost multiplier. */
    public static RoadEdgeUpdate costMultiplier(double costMultiplier) {
        return new RoadEdgeUpdate(null, false, false, null, false, costMultiplier, null);
    }
}
