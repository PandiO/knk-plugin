package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

/**
 * Decides, for one player and one route request, whether an edge is usable right now
 * (DESIGN §6.7): gate doors, domain entry/exit, static flags. Implementations cache their
 * per-gate / per-domain decisions for the request's lifetime, so an instance is built per request
 * and never shared between players. The {@code Oneway} flag is directional and therefore the
 * router's, not a policy's. Bukkit-free; the paper side implements the ports the policies take.
 */
@FunctionalInterface
public interface AccessPolicy {

    /** Ignores availability entirely - what the {@link BlockedExplainer} reruns the search with. */
    AccessPolicy ALL_OPEN = edge -> EdgeVerdict.open();

    EdgeVerdict check(RoadEdge edge);
}
