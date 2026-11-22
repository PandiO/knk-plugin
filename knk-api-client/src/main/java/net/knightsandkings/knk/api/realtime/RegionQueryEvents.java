package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PandiO/knk-workspace#988: the API's questions about a WorldGuard region, with an answer over the game hub, as gate
 * commands ({@link GateCommandEvents}). API → game server {@code RegionGeometryQuery} v1 and {@code SpotSiteCheckQuery}
 * v1 ({@code RegionQueryPayload} in {@code knk-web-api/Realtime/Contracts/RegionQueryContracts.cs}, camelCase
 * {@code {queryId, regionId, world}}), sent to every connected game server; game server → API the hub methods
 * {@code RegionGeometryResult} and {@code SpotSiteCheckResult}, each naming the query id. Nothing in the world changes.
 */
public final class RegionQueryEvents {

    public static final String REGION_GEOMETRY_QUERY = "RegionGeometryQuery";
    public static final String REGION_GEOMETRY_RESULT = "RegionGeometryResult";
    public static final String SPOT_SITE_CHECK_QUERY = "SpotSiteCheckQuery";
    public static final String SPOT_SITE_CHECK_RESULT = "SpotSiteCheckResult";

    /** The region was found; the answer holds its data. */
    public static final String FOUND = "found";
    /** No region with that id in the world (or, without a world, in any loaded world). */
    public static final String NOT_FOUND = "not-found";
    /** The game server failed to answer; see the message. */
    public static final String ERROR = "error";

    private RegionQueryEvents() {
    }

    /**
     * One query.
     *
     * @param queryId  unique per query; the answer names it and a receiver answers once per id
     * @param regionId the WorldGuard region id
     * @param world    the world to look in, or null for the first loaded world that has the region
     */
    public record RegionQuery(String queryId, String regionId, String world) {

        /** The query, or null when the payload has no query id or region id. */
        public static RegionQuery parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope == null ? null : envelope.payload();
            if (p == null || !p.isObject()) {
                return null;
            }
            String queryId = text(p, "queryId");
            String regionId = text(p, "regionId");
            if (queryId == null || queryId.isBlank() || regionId == null || regionId.isBlank()) {
                return null;
            }
            String world = text(p, "world");
            return new RegionQuery(queryId.trim(), regionId.trim(), world == null || world.isBlank() ? null : world.trim());
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode node = p.get(field);
        return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
    }
}
