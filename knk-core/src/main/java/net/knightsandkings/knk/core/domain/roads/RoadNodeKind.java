package net.knightsandkings.knk.core.domain.roads;

/**
 * Kind of a road node (DESIGN §2, §3.5). Mirrors the web-api enum {@code RoadNodeKind}; serialised by
 * name. {@code Virtual} nodes exist only at routing time and are never stored, so they are not here.
 */
public enum RoadNodeKind {
    /** Detected: three or more branches meet, or a plaza collapsed into one node. */
    JUNCTION,
    /** Detected: a dead end or the end of the road. */
    ENDPOINT,
    /** The road crosses the tile border here; the API stitches it to the neighbour tile's node (plan D7). */
    BOUNDARY,
    /** Placed by an admin; chains are split at it and it survives rebuilds. */
    ANCHOR;

    /** The name the web-api uses ({@code Junction}, {@code Endpoint}, {@code Boundary}, {@code Anchor}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadNodeKind fromApiName(String apiName) {
        return valueOf(apiName.trim().toUpperCase());
    }
}
