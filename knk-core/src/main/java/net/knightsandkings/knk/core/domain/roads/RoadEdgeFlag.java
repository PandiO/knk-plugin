package net.knightsandkings.knk.core.domain.roads;

/**
 * Static admin flags of a road edge (DESIGN §3.6, §6.7). Mirrors the web-api {@code [Flags]
 * RoadEdgeFlags}; the API exposes the set as a string array of these names ({@code Oneway},
 * {@code NoGps}, {@code Closed}), never the bit values.
 */
public enum RoadEdgeFlag {
    /** Only traversable from the edge's From node to its To node. */
    ONEWAY("Oneway"),
    /** Never used by the router (a secret path, a shortcut the admins keep off the map). */
    NO_GPS("NoGps"),
    /** Closed for navigation by an admin. */
    CLOSED("Closed");

    private final String apiName;

    RoadEdgeFlag(String apiName) {
        this.apiName = apiName;
    }

    /** The name the web-api uses. */
    public String apiName() {
        return apiName;
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadEdgeFlag fromApiName(String apiName) {
        String wanted = apiName.trim();
        for (RoadEdgeFlag flag : values()) {
            if (flag.apiName.equalsIgnoreCase(wanted) || flag.name().equalsIgnoreCase(wanted)) {
                return flag;
            }
        }
        throw new IllegalArgumentException("Unknown road edge flag: " + apiName);
    }
}
