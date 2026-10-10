package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator.Denial;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Domain entry and exit rules on an edge (DESIGN §6.7 rows 2-3, plan 2d, D11, reuse R6/R7):
 * <ul>
 *   <li><b>Entry:</b> an edge that passes a region whose domain denies entry
 *       ({@link DomainAccessEvaluator#entry}) is BLOCKED - unless the player is already inside that
 *       region (they are not entering it).</li>
 *   <li><b>Exit:</b> for every region the player is in now whose domain denies leaving
 *       ({@link DomainAccessEvaluator#exit}), an edge that does not pass that region is BLOCKED
 *       (it leaves the domain). Phase 2d decision: an edge that lists the region counts as staying
 *       inside, so the route ends on the last edge inside the domain.</li>
 * </ul>
 * Domains are looked up <b>by WorldGuard region id</b> (D11) through the {@link DomainLookup}
 * port (paper: {@code RegionDomainResolver.getDomainByRegionIdNoRefresh}, falling back to
 * {@code resolveRegionsFromApi} off the main thread); the same evaluator instance the region
 * tracker uses is passed in (one source of truth). With {@code bypass} (staff, owner mode,
 * KNG-17's {@code knk.region.bypass}) everything is OPEN. Per-region decisions are cached for the
 * request.
 *
 * <p>Rev. 7 Part C (KNG-92, REV7_PROPOSAL §4): a domain whose {@link RoadRule} says its rule does not
 * apply to roads (a house or shop along a public street) is skipped for entry and exit alike. The rule
 * still holds at the border, for teleports and on the walk path to the door.
 *
 * <p>KNG-110 (P4): entry looks at the road's width. An edge whose {@link RoadEdge#lanes lanes} are known (a region
 * covers part of the road there) is open when the player may enter every region of one lane - a free gap a block
 * wide is enough. Exit stays on the centre line ({@code regionIds}, decided 2026-10-10): a stretch whose middle is in
 * the region still counts as inside it.
 */
public final class DomainAvailability implements AccessPolicy {

    /** Port: the domain owning a WorldGuard region, if any. */
    @FunctionalInterface
    public interface DomainLookup {
        Optional<DomainSnapshot> domainByRegionId(String regionId);
    }

    /** Port (rev. 7 Part C): whether a domain's entry/exit rule keeps routes off the roads in its region. */
    @FunctionalInterface
    public interface RoadRule {
        /** Every domain's rule applies: the behaviour before rev. 7 Part C, and when nothing is known. */
        RoadRule ALWAYS = domain -> true;

        boolean applies(DomainSnapshot domain);
    }

    public static final String ENTRY_MESSAGE = "you may not enter %s";
    public static final String EXIT_MESSAGE = "you may not leave %s";

    private final DomainAccessEvaluator evaluator;
    private final DomainLookup lookup;
    private final RoadRule roadRule;
    private final Set<String> currentRegionIds;
    private final boolean bypass;
    private final Map<String, Optional<Denial>> entryByRegion = new HashMap<>();
    private final List<Exit> exits;

    private record Exit(String regionId, Denial denial) {
    }

    /**
     * @param evaluator        the shared evaluator (R6)
     * @param lookup           domain by region id
     * @param currentRegionIds the WorldGuard regions the player stands in now (R8's {@code at})
     * @param bypass           whether the player may ignore entry/exit rules
     */
    public DomainAvailability(DomainAccessEvaluator evaluator, DomainLookup lookup, Set<String> currentRegionIds,
                              boolean bypass) {
        this(evaluator, lookup, RoadRule.ALWAYS, currentRegionIds, bypass);
    }

    /**
     * @param roadRule which domains' rules apply to roads (rev. 7 Part C); the others are skipped
     */
    public DomainAvailability(DomainAccessEvaluator evaluator, DomainLookup lookup, RoadRule roadRule,
                              Set<String> currentRegionIds, boolean bypass) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.roadRule = Objects.requireNonNull(roadRule, "roadRule");
        this.currentRegionIds = Set.copyOf(currentRegionIds);
        this.bypass = bypass;
        List<Exit> found = new ArrayList<>();
        if (!bypass) {
            for (String regionId : this.currentRegionIds) {
                lookup.domainByRegionId(regionId).filter(roadRule::applies).flatMap(evaluator::exit)
                    .ifPresent(denial -> found.add(new Exit(regionId, denial)));
            }
            found.sort((a, b) -> a.regionId.compareTo(b.regionId));
        }
        this.exits = List.copyOf(found);
    }

    /** The regions the player stands in whose domains forbid leaving (empty with bypass). */
    public List<String> exitDeniedRegions() {
        return exits.stream().map(Exit::regionId).toList();
    }

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        if (bypass) {
            return EdgeVerdict.open();
        }
        for (Exit exit : exits) {
            if (!edge.regionIds().contains(exit.regionId)) {
                DomainSnapshot d = exit.denial.domain();
                return EdgeVerdict.blocked(String.format(EXIT_MESSAGE, d.name()),
                    EdgeVerdict.Cause.domain(d.id() == null ? -1 : d.id(), d.name()));
            }
        }
        Optional<Denial> first = Optional.empty();
        for (List<String> lane : edge.lanes().isEmpty() ? List.of(edge.regionIds()) : edge.lanes()) {
            Optional<Denial> denial = laneDenial(lane);
            if (denial.isEmpty()) {
                return EdgeVerdict.open();
            }
            if (first.isEmpty()) {
                first = denial;
            }
        }
        DomainSnapshot d = first.orElseThrow().domain();
        return EdgeVerdict.blocked(String.format(ENTRY_MESSAGE, d.name()),
            EdgeVerdict.Cause.domain(d.id() == null ? -1 : d.id(), d.name()));
    }

    /**
     * KNG-110: whether the player may stand in {@code regionId} on a road - {@link #check}'s entry rule for one region,
     * so the trail keeps off the road cells of the others. True with bypass, when already inside, and for a domain
     * whose rule does not apply to roads.
     */
    public boolean mayEnter(String regionId) {
        return bypass || currentRegionIds.contains(regionId) || entryDenial(regionId).isEmpty();
    }

    /** The first region of a lane the player may not enter, if any. */
    private Optional<Denial> laneDenial(List<String> lane) {
        for (String regionId : lane) {
            if (currentRegionIds.contains(regionId)) {
                continue; // already inside: not an entry
            }
            Optional<Denial> denial = entryDenial(regionId);
            if (denial.isPresent()) {
                return denial;
            }
        }
        return Optional.empty();
    }

    private Optional<Denial> entryDenial(String regionId) {
        return entryByRegion.computeIfAbsent(regionId,
            id -> lookup.domainByRegionId(id).filter(roadRule::applies).flatMap(evaluator::entry));
    }
}
