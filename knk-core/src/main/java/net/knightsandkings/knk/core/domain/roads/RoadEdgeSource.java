package net.knightsandkings.knk.core.domain.roads;

/**
 * Where a road edge came from (DESIGN §3.6, plan D7). Mirrors the web-api enum
 * {@code RoadEdgeSource}; serialised by name.
 */
public enum RoadEdgeSource {
    /** Traced by the tile builder. */
    DETECTED,
    /** Walked by an admin (DESIGN §5.10); survives rebuilds. */
    RECORDED,
    /**
     * A 1-block edge the API creates between two tiles' Boundary nodes (plan D7). Plumbing: the
     * router treats it like any edge, the maneuver builder ignores it.
     */
    STITCH;

    /** The name the web-api uses ({@code Detected}, {@code Recorded}, {@code Stitch}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadEdgeSource fromApiName(String apiName) {
        return valueOf(apiName.trim().toUpperCase());
    }
}
