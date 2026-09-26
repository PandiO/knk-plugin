package net.knightsandkings.knk.core.domain.discovery;

/**
 * Why the plugin asks for a discovery (knk-web-api's DiscoverySource; docs/specs/domain-discovery
 * DESIGN.md §3.4). The server also has Ancestor and Admin, which the plugin never sends.
 */
public enum DiscoverySource {
    /** The player walked or teleported into the region. */
    REGION_ENTER("RegionEnter"),
    /** The player was already standing inside the region when they joined. */
    JOIN_INSIDE("JoinInside"),
    /** A spooled request sent again after the API was unreachable. */
    REPLAY("Replay");

    private final String apiName;

    DiscoverySource(String apiName) {
        this.apiName = apiName;
    }

    /** The value of the request's "source" field. */
    public String apiName() {
        return apiName;
    }

    /** Parses {@link #apiName()} (or the enum name), ignoring case; {@code REGION_ENTER} for anything else. */
    public static DiscoverySource fromApiName(String value) {
        if (value != null) {
            for (DiscoverySource source : values()) {
                if (source.apiName.equalsIgnoreCase(value) || source.name().equalsIgnoreCase(value)) {
                    return source;
                }
            }
        }
        return REGION_ENTER;
    }
}
