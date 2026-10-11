package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;

/**
 * The static admin flags (DESIGN §6.7 last row): {@code Closed} and {@code NoGps} block an edge for
 * everyone. {@code Oneway} is directional and handled by the router. Stateless.
 */
public final class StaticFlagsAvailability implements AccessPolicy {

    public static final String CLOSED_MESSAGE = "the road is closed";
    public static final String NO_GPS_MESSAGE = "the road is not on the map";

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        if (edge.hasFlag(RoadEdgeFlag.CLOSED)) {
            return EdgeVerdict.blocked(CLOSED_MESSAGE,
                EdgeVerdict.Cause.flag(RoadEdgeFlag.CLOSED.apiName(), "closed road"));
        }
        if (edge.hasFlag(RoadEdgeFlag.NO_GPS)) {
            return EdgeVerdict.blocked(NO_GPS_MESSAGE,
                EdgeVerdict.Cause.flag(RoadEdgeFlag.NO_GPS.apiName(), "unmapped road"));
        }
        return EdgeVerdict.open();
    }
}
