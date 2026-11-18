package net.knightsandkings.knk.core.roads.links;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;
import net.knightsandkings.knk.core.roads.route.WalkLinkSource;

/**
 * The walk links of one route request (off-road navigation DESIGN § 5.2, decisions O12, O28), for the road A*
 * ({@code AStarRouter.route(request, planner)}): at a node it expands, the candidates of {@link WalkLinkCandidates};
 * those with a found link in the {@link WalkLinkCache} that the request's {@link AccessPolicy} lets the mover take are
 * offered, those with no answer yet are collected as wanted ({@link #wanted(int, double)}), and those with "none" or a
 * running search are left out. Built per request on the routing thread; not shared.
 *
 * <p>A link's routing cost is its walk cost (length plus the walk profile's penalties) times
 * {@code max(1, cheapest road factor)}: walking off the road is never cheaper per block than the cheapest road (the
 * heuristic stays admissible), and with today's class costs (all at least 1) a link costs what it is walked.
 */
public final class WalkLinkPlanner implements WalkLinkSource {

    private final WalkLinkCandidates candidates;
    private final WalkLinkCache cache;
    private final String world;
    private final String mover;
    private final AccessPolicy policy;
    private final double factor;
    private final Map<WalkLinkId, Wanted> wanted = new HashMap<>();
    private final List<WalkLinkCache.Key> offered = new ArrayList<>();

    /** An unsearched candidate the A* met, with the least cost a route through it could have. */
    public record Wanted(Candidate candidate, double lowerBound) {
    }

    /**
     * @param minFactor the request's cheapest road cost factor ({@code RoadNetworkSnapshot.minCostFactor})
     */
    public WalkLinkPlanner(WalkLinkCandidates candidates, WalkLinkCache cache, String world, String mover,
                           AccessPolicy policy, double minFactor) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.world = Objects.requireNonNull(world, "world");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.factor = Math.max(1, minFactor);
    }

    @Override
    public Links at(int nodeId) {
        List<Candidate> here = candidates.from(nodeId);
        if (here.isEmpty()) {
            return Links.EMPTY;
        }
        List<Option> usable = new ArrayList<>();
        List<Candidate> unknown = new ArrayList<>();
        for (Candidate c : here) {
            WalkLinkCache.Key key = new WalkLinkCache.Key(world, mover, c.id());
            Optional<WalkLinkCache.Entry> entry = cache.lookup(key);
            if (entry.isEmpty()) {
                unknown.add(c);
                continue;
            }
            if (!entry.get().found()) {
                continue; // none, or a search still running
            }
            EdgeVerdict verdict = policy.checkWalkLink(entry.get().link());
            if (verdict.isUsable()) {
                usable.add(new Option(entry.get().link(), entry.get().link().walkCost() * factor));
                offered.add(key);
            }
        }
        return new Links(usable, unknown);
    }

    @Override
    public void wanted(Candidate candidate, double lowerBound) {
        WalkLinkId id = candidate.id();
        Wanted known = wanted.get(id);
        if (known == null || lowerBound < known.lowerBound()) {
            wanted.put(id, new Wanted(candidate, lowerBound));
        }
    }

    /**
     * The candidates worth a search after the A* ran: those whose least route cost is below {@code routeCost} (the
     * found route's; infinite when none was found) - a link there could improve the route (DESIGN § 5.2.2) - cheapest
     * bound first, at most {@code limit}.
     */
    public List<Wanted> wanted(int limit, double routeCost) {
        List<Wanted> out = new ArrayList<>();
        for (Wanted w : wanted.values()) {
            if (w.lowerBound() < routeCost - 1e-9) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparingDouble(Wanted::lowerBound).thenComparing(w -> w.candidate().id()));
        return out.size() <= limit ? out : List.copyOf(out.subList(0, limit));
    }

    /** How many usable links the A* was offered (for the counters). */
    public int offeredCount() {
        return offered.size();
    }

    public String world() {
        return world;
    }

    public String mover() {
        return mover;
    }
}
