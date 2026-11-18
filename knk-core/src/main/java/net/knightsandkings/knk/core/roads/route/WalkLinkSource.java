package net.knightsandkings.knk.core.roads.route;

import java.util.List;

/**
 * What the road A* asks about walk links (off-road navigation DESIGN § 5.2, decision O12): when it expands a road node,
 * the links it may take from there for this request, and the candidates nobody has searched yet. Built per request
 * (the knk-core implementation is {@code links.WalkLinkPlanner}); called on the routing thread only.
 *
 * <p>Without a source ({@link AStarRouter#route(RouteRequest)}) the router never asks and routes exactly as before.
 */
public interface WalkLinkSource {

    /** No links: the router behaves as without a source. */
    WalkLinkSource NONE = new WalkLinkSource() {
        @Override
        public Links at(int nodeId) {
            return Links.EMPTY;
        }

        @Override
        public void wanted(Candidate candidate, double lowerBound) {
        }
    };

    /**
     * A pair of road nodes the walk search could join (within {@code walk-link-range}, the road between more than
     * {@code walk-link-ratio} times the straight distance), not searched yet.
     *
     * @param from     the node the A* reached
     * @param to       the other end
     * @param straight straight 3D distance between their floors
     */
    record Candidate(int from, int to, double straight) {
        public WalkLinkId id() {
            return WalkLinkId.of(from, to);
        }
    }

    /**
     * A usable link from the node with the cost the router uses for it.
     *
     * @param link        the cached link
     * @param routingCost its cost on the road A*'s scale, at least its straight distance times the cheapest road cost
     *                    factor (so the heuristic stays admissible)
     */
    record Option(WalkLink link, double routingCost) {
    }

    /**
     * @param usable  cached links from the node the request may take (access checked)
     * @param unknown candidates from the node with no cached answer
     */
    record Links(List<Option> usable, List<Candidate> unknown) {
        public static final Links EMPTY = new Links(List.of(), List.of());

        public Links {
            usable = List.copyOf(usable);
            unknown = List.copyOf(unknown);
        }
    }

    /** The links at a road node the A* expands. */
    Links at(int nodeId);

    /**
     * The A* met an unsearched candidate: the cheapest route through it costs at least {@code lowerBound} (the cost to its
     * From node, its straight distance and the heuristic from its To node).
     */
    void wanted(Candidate candidate, double lowerBound);
}
