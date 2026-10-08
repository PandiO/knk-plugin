package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines policies: the first BLOCKED verdict wins, else the first PASS_THROUGH, else OPEN.
 * Verdicts are cached per edge id for the request (each policy is asked once per edge).
 */
public final class CompositeAccessPolicy implements AccessPolicy {

    private final List<AccessPolicy> policies;
    private final Map<Integer, EdgeVerdict> cache = new HashMap<>();

    public CompositeAccessPolicy(List<AccessPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public static CompositeAccessPolicy of(AccessPolicy... policies) {
        return new CompositeAccessPolicy(List.of(policies));
    }

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        EdgeVerdict cached = cache.get(edge.id());
        if (cached != null) {
            return cached;
        }
        EdgeVerdict result = EdgeVerdict.open();
        for (AccessPolicy policy : policies) {
            EdgeVerdict v = policy.check(edge);
            if (v.isBlocked()) {
                result = v;
                break;
            }
            result = EdgeVerdict.stricter(result, v);
        }
        cache.put(edge.id(), result);
        return result;
    }

    public List<AccessPolicy> policies() {
        return policies;
    }
}
