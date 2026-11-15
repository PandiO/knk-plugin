package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;

/**
 * The road policy part of a subject's own deny list ({@link AccessSubject#regionOverride}): an edge through a region
 * the subject never enters is BLOCKED, unless the subject already stands in that region. As for domain entry, an
 * edge with known lanes is open when one lane is free of denied regions. The verdicts are decided when the policy
 * is built (main thread), so the routing thread only reads them.
 */
public final class SubjectRegionAvailability implements AccessPolicy {

    public static final String MESSAGE = "%s may not enter %s";

    private final String subjectName;
    private final java.util.Set<String> current;
    private final java.util.function.Predicate<String> denied;

    public SubjectRegionAvailability(AccessSubject subject) {
        Objects.requireNonNull(subject, "subject");
        this.subjectName = subject.name();
        this.current = java.util.Set.copyOf(subject.currentRegions());
        this.denied = regionId -> subject.regionOverride(regionId) == AccessSubject.RegionOverride.DENY;
    }

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        String first = null;
        for (List<String> lane : edge.lanes().isEmpty() ? List.of(edge.regionIds()) : edge.lanes()) {
            String blocked = deniedIn(lane);
            if (blocked == null) {
                return EdgeVerdict.open();
            }
            if (first == null) {
                first = blocked;
            }
        }
        return EdgeVerdict.blocked(String.format(MESSAGE, subjectName, first), EdgeVerdict.Cause.domain(-1, first));
    }

    private String deniedIn(List<String> lane) {
        for (String regionId : lane) {
            if (!current.contains(regionId) && denied.test(regionId)) {
                return regionId;
            }
        }
        return null;
    }
}
